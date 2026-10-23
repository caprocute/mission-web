package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.integ.application.port.InboxMessages;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages.Due;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages.Row;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.ErrorText;
import vn.viettel.okrkpi.integ.domain.OutboundStatus;
import vn.viettel.okrkpi.integ.domain.RetrySchedule;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Pipeline chiều ra dùng chung: consumer sự kiện ghi {@code outbound_message} ({@code CHO}, nội
 * dung mã hoá); worker gửi với thử lại luỹ thừa ({@code next_attempt_at}); chỉ coi xong khi bên
 * nhận xác nhận ({@code ack_ref}); hết lượt thì {@code LOI} và vào thư chết. Sự kiện do chính
 * gateway phát từ thông điệp vào của cùng kết nối không gửi ngược lại (chống vòng lặp hai chiều).
 */
public class OutboundPipeline {

  public static final String PAYLOAD_COLUMN = "integ.outbound_message.payload_enc";

  private static final Logger LOG = LoggerFactory.getLogger(OutboundPipeline.class);

  public enum Result {
    QUEUED,
    DUPLICATE,
    /** Sự kiện sinh từ thông điệp vào của chính kết nối đích. */
    ECHO
  }

  public record Enqueued(Result result, UUID id) {}

  private final Connectors connectors;
  private final OutboundMessages messages;
  private final InboxMessages inbox;
  private final DeadLetters deadLetters;
  private final PayloadCipher cipher;
  private final Map<String, OutboundSender> senders = new HashMap<>();
  private final RetrySchedule schedule;
  private final Duration ackTimeout;
  private final TransactionOperations tx;
  private final Clock clock;

  public OutboundPipeline(
      Connectors connectors,
      OutboundMessages messages,
      InboxMessages inbox,
      DeadLetters deadLetters,
      PayloadCipher cipher,
      Collection<OutboundSender> senders,
      RetrySchedule schedule,
      Duration ackTimeout,
      TransactionOperations tx,
      Clock clock) {
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    for (OutboundSender s : senders) {
      if (this.senders.putIfAbsent(s.connectorCode(), s) != null) {
        throw new IllegalStateException("Trùng adapter chiều ra cho kết nối " + s.connectorCode());
      }
    }
    this.schedule = Objects.requireNonNull(schedule, "schedule");
    this.ackTimeout = Objects.requireNonNull(ackTimeout, "ackTimeout");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Ghi thông điệp cần gửi cho sự kiện nguồn {@code sourceEventId}; gọi lại không ghi trùng. */
  public Enqueued enqueue(
      TenantId tenant,
      String connectorCode,
      UUID sourceEventId,
      String messageType,
      byte[] payload) {
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(payload, "payload");
    if (messageType == null || messageType.isBlank() || messageType.length() > 48) {
      throw new IllegalArgumentException("Loại thông điệp phải có từ 1 đến 48 ký tự");
    }
    return TenantScope.call(
        tenant,
        () ->
            tx.execute(
                s -> {
                  Connector c =
                      connectors
                          .findByCode(tenant, connectorCode)
                          .orElseThrow(
                              () ->
                                  new NoSuchElementException(
                                      "Tenant chưa cấu hình kết nối " + connectorCode));
                  if (!c.direction().outbound()) {
                    throw new IllegalStateException(
                        "Kết nối " + connectorCode + " không gửi thông điệp ra");
                  }
                  if (inbox.emitted(tenant, c.id(), sourceEventId)) {
                    return new Enqueued(Result.ECHO, null);
                  }
                  if (messages.exists(tenant, c.id(), sourceEventId)) {
                    return new Enqueued(Result.DUPLICATE, null);
                  }
                  UUID id = Ids.newUuidV7();
                  Instant now = clock.instant();
                  messages.insert(
                      tenant,
                      id,
                      LocalDate.now(clock),
                      c.id(),
                      sourceEventId,
                      messageType,
                      cipher.seal(tenant, PAYLOAD_COLUMN, id, payload),
                      now);
                  return new Enqueued(Result.QUEUED, id);
                }));
  }

  /** Gửi các thông điệp đến hạn của mọi tenant; trả số thông điệp đã xử lý. */
  public int dispatchDue(int limit) {
    return dispatch(messages.due(limit));
  }

  /**
   * Gửi các thông điệp đến hạn lọc theo mã kết nối (DV-13-05: Deployment riêng của route Trục chỉ
   * gửi mã của Trục, Deployment chính bỏ các mã đó); bộ lọc rỗng thì như {@link #dispatchDue(int)}.
   */
  public int dispatchDue(int limit, java.util.Set<String> codes, boolean include) {
    if (codes.isEmpty() && !include) {
      return dispatchDue(limit);
    }
    return dispatch(messages.due(limit, codes, include));
  }

  private int dispatch(List<Due> due) {
    int handled = 0;
    for (Due d : due) {
      try {
        if (dispatch(TenantId.of(d.tenantId()), d.createdDay(), d.id()).isPresent()) {
          handled++;
        }
      } catch (RuntimeException e) {
        LOG.warn("Không gửi được thông điệp ra {}: {}", d.id(), ErrorText.code(e));
      }
    }
    return handled;
  }

  /** Gửi một thông điệp; rỗng khi pod khác đang giữ hoặc thông điệp không còn chờ gửi. */
  public Optional<OutboundStatus> dispatch(TenantId tenant, LocalDate day, UUID id) {
    return TenantScope.call(
        tenant,
        () ->
            tx.execute(
                s -> {
                  Optional<Row> claimed = messages.claim(tenant, day, id);
                  if (claimed.isEmpty()) {
                    return Optional.<OutboundStatus>empty();
                  }
                  return Optional.of(send(tenant, claimed.get()));
                }));
  }

  private OutboundStatus send(TenantId tenant, Row row) {
    Instant now = clock.instant();
    int attempts = row.attempts() + 1;
    Throwable failure;
    try {
      Connector c =
          connectors
              .findById(tenant, row.connectorId())
              .orElseThrow(() -> new NoSuchElementException("Không có kết nối"));
      OutboundSender sender = senders.get(c.code());
      if (sender == null) {
        throw new IllegalStateException("Chưa có adapter chiều ra cho kết nối " + c.code());
      }
      byte[] plain = cipher.open(tenant, PAYLOAD_COLUMN, row.id(), row.payloadEnc());
      Optional<String> ack = sender.send(c, row.id(), row.messageType(), plain);
      if (ack.isPresent()) {
        messages.update(
            tenant,
            row.createdDay(),
            row.id(),
            OutboundStatus.DA_XAC_NHAN,
            attempts,
            null,
            ack.get());
        return OutboundStatus.DA_XAC_NHAN;
      }
      if (attempts < schedule.maxAttempts()) {
        messages.update(
            tenant,
            row.createdDay(),
            row.id(),
            OutboundStatus.DA_GUI,
            attempts,
            now.plus(ackTimeout),
            null);
        return OutboundStatus.DA_GUI;
      }
      failure = new IllegalStateException("Bên nhận không xác nhận sau " + attempts + " lần gửi");
    } catch (IOException | RuntimeException e) {
      failure = e;
      Optional<Instant> next = schedule.next(attempts, now);
      if (next.isPresent()) {
        messages.update(
            tenant, row.createdDay(), row.id(), OutboundStatus.CHO, attempts, next.get(), null);
        return OutboundStatus.CHO;
      }
    }
    messages.update(tenant, row.createdDay(), row.id(), OutboundStatus.LOI, attempts, null, null);
    deadLetters.insert(
        tenant,
        Ids.newUuidV7(),
        row.connectorId(),
        DeadLetterService.OUTBOUND,
        row.id(),
        ErrorText.code(failure),
        ErrorText.detail(failure));
    return OutboundStatus.LOI;
  }

  /** Bên nhận xác nhận sau (callback): {@code DA_XAC_NHAN} với mã xác nhận. */
  public void acknowledge(TenantId tenant, UUID id, String ackRef) {
    if (ackRef == null || ackRef.isBlank() || ackRef.length() > 128) {
      throw new IllegalArgumentException("Mã xác nhận phải có từ 1 đến 128 ký tự");
    }
    TenantScope.call(
        tenant,
        () -> {
          tx.executeWithoutResult(
              s -> {
                Row row = require(tenant, id);
                if (row.status() != OutboundStatus.DA_XAC_NHAN) {
                  messages.update(
                      tenant,
                      row.createdDay(),
                      id,
                      OutboundStatus.DA_XAC_NHAN,
                      row.attempts(),
                      null,
                      ackRef);
                }
              });
          return null;
        });
  }

  /** Đưa thông điệp lỗi về hàng chờ gửi lại từ đầu (phát lại thư chết chiều ra). */
  void reset(TenantId tenant, UUID id) {
    Row row = require(tenant, id);
    if (row.status() == OutboundStatus.DA_XAC_NHAN) {
      return;
    }
    messages.update(tenant, row.createdDay(), id, OutboundStatus.CHO, 0, clock.instant(), null);
  }

  private Row require(TenantId tenant, UUID id) {
    return messages
        .find(tenant, id)
        .orElseThrow(() -> new NoSuchElementException("Không có thông điệp ra " + id));
  }
}
