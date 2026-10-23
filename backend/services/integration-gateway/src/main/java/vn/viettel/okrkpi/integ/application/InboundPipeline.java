package vn.viettel.okrkpi.integ.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.CanonicalEventSink;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.integ.application.port.InboxMessages;
import vn.viettel.okrkpi.integ.application.port.InboxMessages.Raw;
import vn.viettel.okrkpi.integ.application.port.InboxMessages.Stored;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.domain.InboxStatus;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Luồng vào của lớp chống ô nhiễm (TL-01 "Lớp chống ô nhiễm"): lưu nguyên văn đã mã hoá vào {@code
 * inbox_message} ở giao dịch riêng trước khi xử lý, rồi Translator dịch sang mô hình chuẩn và
 * Publisher ghi outbox cùng giao dịch với trạng thái {@code DA_PHAT}. Thông điệp đã xử lý (cùng mã
 * ngoài trong cửa sổ chống trùng) bị bỏ qua; lỗi chuyển đổi để dòng ở {@code LOI} và ném {@link
 * InboundProcessingException} cho route thử lại rồi chuyển thư chết. Translator từ chối ({@link
 * TranslationRejectedException}) thì không thử lại: dòng ở {@code LOI} và ghi thẳng một thư chết
 * mang mã lý do cho quản trị tenant xử lý (DV-13-03).
 */
public class InboundPipeline {

  public static final String RAW_COLUMN = "integ.inbox_message.raw_enc";

  public enum Result {
    /** Đã phát ít nhất một sự kiện. */
    PUBLISHED,
    /** Đã chuyển đổi, không có sự kiện cần phát. */
    TRANSLATED,
    /** Thông điệp đã xử lý trước đó. */
    DUPLICATE,
    /** Translator từ chối, đã ghi thư chết có mã lý do. */
    REJECTED
  }

  public record Receipt(Result result, UUID inboxId, List<UUID> eventIds) {
    public Receipt {
      eventIds = List.copyOf(eventIds);
    }
  }

  private final Connectors connectors;
  private final InboxMessages inbox;
  private final DeadLetters deadLetters;
  private final PayloadCipher cipher;
  private final CanonicalEventSink sink;
  private final MappingXrefService mappings;
  private final Translators translators;
  private final TransactionOperations tx;
  private final Clock clock;
  private final int dedupeWindowDays;

  public InboundPipeline(
      Connectors connectors,
      InboxMessages inbox,
      DeadLetters deadLetters,
      PayloadCipher cipher,
      CanonicalEventSink sink,
      MappingXrefService mappings,
      Translators translators,
      TransactionOperations tx,
      Clock clock,
      int dedupeWindowDays) {
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.mappings = Objects.requireNonNull(mappings, "mappings");
    this.translators = Objects.requireNonNull(translators, "translators");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (dedupeWindowDays < 1) {
      throw new IllegalArgumentException("Cửa sổ chống trùng phải từ 1 ngày");
    }
    this.dedupeWindowDays = dedupeWindowDays;
  }

  /** Nhận một thông điệp của kết nối {@code connectorCode}. */
  public Receipt receive(TenantId tenant, String connectorCode, InboundMessage message) {
    Objects.requireNonNull(message, "message");
    return TenantScope.call(
        tenant,
        () -> {
          Connector connector =
              Objects.requireNonNull(tx.execute(s -> inboundConnector(tenant, connectorCode)));
          Translator translator = translators.require(connector.code());
          LocalDate today = today();
          LocalDate since = today.minusDays(dedupeWindowDays);
          if (Boolean.TRUE.equals(
              tx.execute(
                  s -> inbox.processed(tenant, connector.id(), message.externalMsgId(), since)))) {
            return new Receipt(Result.DUPLICATE, null, List.of());
          }
          UUID id = Ids.newUuidV7();
          Stored stored =
              tx.execute(
                  s ->
                      inbox.store(
                          tenant,
                          id,
                          today,
                          since,
                          connector.id(),
                          message.externalMsgId(),
                          message.messageType(),
                          cipher.seal(tenant, RAW_COLUMN, id, message.raw())));
          if (done(stored.status())) {
            return new Receipt(Result.DUPLICATE, stored.id(), List.of());
          }
          return process(
              tenant, connector, translator, stored.id(), stored.receivedDay(), message, true);
        });
  }

  /** Thông điệp đã xử lý xong trong cửa sổ chống trùng (bộ chống trùng của route). */
  public boolean processed(TenantId tenant, String connectorCode, String externalMsgId) {
    return TenantScope.call(
        tenant,
        () ->
            Boolean.TRUE.equals(
                tx.execute(
                    s ->
                        connectors
                            .findByCode(tenant, connectorCode)
                            .map(
                                c ->
                                    inbox.processed(
                                        tenant,
                                        c.id(),
                                        externalMsgId,
                                        today().minusDays(dedupeWindowDays)))
                            .orElse(false))));
  }

  /** Xử lý lại thông điệp đã lưu (phát lại thư chết), giải mã nguyên văn từ {@code raw_enc}. */
  public Receipt reprocess(TenantId tenant, UUID inboxId) {
    return TenantScope.call(
        tenant,
        () -> {
          Raw raw =
              Objects.requireNonNull(
                  tx.execute(
                      s ->
                          inbox
                              .find(tenant, inboxId)
                              .orElseThrow(
                                  () ->
                                      new NoSuchElementException(
                                          "Không có thông điệp " + inboxId))));
          if (done(raw.status())) {
            return new Receipt(Result.DUPLICATE, inboxId, List.of());
          }
          Connector connector =
              Objects.requireNonNull(
                  tx.execute(
                      s ->
                          connectors
                              .findById(tenant, raw.connectorId())
                              .orElseThrow(() -> new NoSuchElementException("Không có kết nối"))));
          InboundMessage message =
              new InboundMessage(
                  raw.externalMsgId(),
                  raw.messageType(),
                  cipher.open(tenant, RAW_COLUMN, inboxId, raw.rawEnc()));
          return process(
              tenant,
              connector,
              translators.require(connector.code()),
              inboxId,
              raw.receivedDay(),
              message,
              false);
        });
  }

  private Receipt process(
      TenantId tenant,
      Connector connector,
      Translator translator,
      UUID inboxId,
      LocalDate day,
      InboundMessage message,
      boolean recordRejection) {
    try {
      return tx.execute(
          s -> {
            TranslationContext ctx = new TranslationContext(tenant, connector, today(), mappings);
            List<CanonicalEvent> events = translator.translate(ctx, message);
            List<UUID> ids = new ArrayList<>();
            for (CanonicalEvent e : events == null ? List.<CanonicalEvent>of() : events) {
              ids.add(sink.publish(tenant, e));
            }
            InboxStatus status = ids.isEmpty() ? InboxStatus.DA_CHUYEN_DOI : InboxStatus.DA_PHAT;
            inbox.mark(tenant, day, inboxId, status, ids.isEmpty() ? null : ids.get(0));
            return new Receipt(ids.isEmpty() ? Result.TRANSLATED : Result.PUBLISHED, inboxId, ids);
          });
    } catch (TranslationRejectedException e) {
      // Phát lại thư chết mà vẫn bị từ chối thì để thư cũ mở, không ghi thư mới.
      if (!recordRejection) {
        throw e;
      }
      tx.executeWithoutResult(
          s -> {
            inbox.mark(tenant, day, inboxId, InboxStatus.LOI, null);
            if (!deadLetters.hasOpen(tenant, inboxId)) {
              deadLetters.insert(
                  tenant,
                  Ids.newUuidV7(),
                  connector.id(),
                  DeadLetterService.INBOUND,
                  inboxId,
                  e.reasonCode(),
                  e.getMessage());
            }
          });
      return new Receipt(Result.REJECTED, inboxId, List.of());
    } catch (RuntimeException e) {
      tx.executeWithoutResult(s -> inbox.mark(tenant, day, inboxId, InboxStatus.LOI, null));
      throw new InboundProcessingException(tenant, connector.id(), inboxId, e);
    }
  }

  private Connector inboundConnector(TenantId tenant, String code) {
    Connector c =
        connectors
            .findByCode(tenant, code)
            .orElseThrow(() -> new NoSuchElementException("Tenant chưa cấu hình kết nối " + code));
    if (!c.enabled() || !c.direction().inbound()) {
      throw new IllegalStateException("Kết nối " + code + " không nhận thông điệp vào");
    }
    return c;
  }

  private static boolean done(InboxStatus status) {
    return status == InboxStatus.DA_PHAT || status == InboxStatus.DA_CHUYEN_DOI;
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }
}
