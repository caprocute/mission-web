package vn.viettel.okrkpi.integ.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages.Tracked;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.OutboundStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Use case gửi văn bản ra qua Trục liên thông (DV-13-05, QT-11 BR-11-11): MOD-06 gửi gói con hồ sơ
 * diện Trung ương đã ký và mã hoá theo chứng thư bên nhận; gateway dựng EdXML và ghi {@code
 * outbound_message} với {@code source_event_id} là {@code transferId} do MOD-06 cấp, nên gửi lại
 * cùng mã không xếp hàng lần hai. Worker gửi ra của khung DV-13-01 thử lại theo lịch và chỉ coi
 * xong khi bên nhận có biên nhận; MOD-06 hỏi kết quả qua {@link #status}.
 */
public class EdxmlTransferService {

  public static final String MESSAGE_TYPE = "EDXML";

  /** Tệp đính kèm (gói con đã ký, đã mã hoá). */
  public record Attachment(String name, String contentType, byte[] content) {
    public Attachment {
      Objects.requireNonNull(content, "content");
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Attachment a
          && Objects.equals(name, a.name)
          && Objects.equals(contentType, a.contentType)
          && java.util.Arrays.equals(content, a.content);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, contentType, java.util.Arrays.hashCode(content));
    }

    @Override
    public String toString() {
      return "Attachment[" + name + ", " + content.length + " byte]";
    }
  }

  /** Yêu cầu gửi; {@code destination} là mã kết nối của nơi nhận (ví dụ BTC_TW, VPTW). */
  public record Request(
      UUID transferId,
      UUID packageId,
      String destination,
      String subject,
      String documentNumber,
      List<Attachment> attachments) {
    public Request {
      attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
  }

  /** Kết quả gửi cho MOD-06; {@code lastError} là mã lỗi của thư chết khi đã hết lượt. */
  public record Status(
      UUID transferId,
      String destination,
      OutboundStatus status,
      int attempts,
      Instant nextAttemptAt,
      String ackRef,
      Instant updatedAt,
      String lastError) {}

  /** Dựng nội dung gửi theo giao thức của nơi nhận (EdXML). */
  @FunctionalInterface
  public interface Composer {
    byte[] compose(Connector destination, Request request);
  }

  private final Connectors connectors;
  private final OutboundPipeline pipeline;
  private final OutboundMessages messages;
  private final DeadLetters deadLetters;
  private final Composer composer;
  private final Set<String> destinations;
  private final long maxBytes;
  private final TransactionOperations tx;

  public EdxmlTransferService(
      Connectors connectors,
      OutboundPipeline pipeline,
      OutboundMessages messages,
      DeadLetters deadLetters,
      Composer composer,
      Set<String> destinations,
      long maxBytes,
      TransactionOperations tx) {
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
    this.composer = Objects.requireNonNull(composer, "composer");
    this.destinations = Set.copyOf(destinations);
    if (maxBytes < 1) {
      throw new IllegalArgumentException("Trần dung lượng phải dương");
    }
    this.maxBytes = maxBytes;
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  public Status submit(TenantId tenant, Request request) {
    Objects.requireNonNull(request, "request");
    if (request.transferId() == null) {
      throw new IllegalArgumentException("Thiếu mã lần gửi");
    }
    if (request.subject() == null
        || request.subject().isBlank()
        || request.subject().length() > 500) {
      throw new IllegalArgumentException("Trích yếu phải có từ 1 đến 500 ký tự");
    }
    if (request.attachments().isEmpty()) {
      throw new IllegalArgumentException("Thiếu tệp đính kèm");
    }
    long total = 0;
    for (Attachment a : request.attachments()) {
      if (a.name() == null || a.name().isBlank() || a.name().length() > 255) {
        throw new IllegalArgumentException("Tên tệp đính kèm phải có từ 1 đến 255 ký tự");
      }
      total += a.content().length;
    }
    if (total > maxBytes) {
      throw new TransferRejectedException(
          TransferRejectedException.Reason.PAYLOAD_TOO_LARGE, "Tổng tệp đính kèm vượt trần");
    }
    Optional<Status> existing = status(tenant, request.transferId());
    if (existing.isPresent()) {
      return existing.get();
    }
    String code = request.destination();
    Connector destination =
        code == null || !destinations.contains(code)
            ? null
            : TenantScope.call(
                tenant,
                () ->
                    tx.execute(
                        s ->
                            connectors
                                .findByCode(tenant, code)
                                .filter(c -> c.enabled() && c.direction().outbound())
                                .orElse(null)));
    if (destination == null) {
      throw new TransferRejectedException(
          TransferRejectedException.Reason.DESTINATION_NOT_CONFIGURED,
          "Nơi nhận chưa được cấu hình kết nối");
    }
    byte[] payload = composer.compose(destination, request);
    pipeline.enqueue(tenant, code, request.transferId(), MESSAGE_TYPE, payload);
    return status(tenant, request.transferId())
        .orElseThrow(() -> new IllegalStateException("Không đọc lại được lần gửi vừa xếp hàng"));
  }

  public Optional<Status> status(TenantId tenant, UUID transferId) {
    Objects.requireNonNull(transferId, "transferId");
    return TenantScope.call(
        tenant,
        () ->
            tx.execute(
                s -> {
                  Optional<Tracked> t = messages.bySource(tenant, transferId);
                  if (t.isEmpty()) {
                    return Optional.<Status>empty();
                  }
                  Tracked m = t.get();
                  String error =
                      m.status() == OutboundStatus.LOI
                          ? deadLetters.openErrorCode(tenant, m.id()).orElse(null)
                          : null;
                  String code =
                      connectors
                          .findById(tenant, m.connectorId())
                          .map(Connector::code)
                          .orElse(null);
                  return Optional.of(
                      new Status(
                          transferId,
                          code,
                          m.status(),
                          m.attempts(),
                          m.nextAttemptAt(),
                          m.ackRef(),
                          m.updatedAt(),
                          error));
                }));
  }
}
