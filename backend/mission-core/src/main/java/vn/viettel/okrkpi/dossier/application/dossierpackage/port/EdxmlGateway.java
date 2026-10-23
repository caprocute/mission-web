package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng gửi gói con EdXML qua integration-gateway (SVC-13, DV-13-05): {@code POST
 * /internal/v1/integ/edxml-transfers} và {@code GET .../{transferId}}. SVC-13 ký số, mã hoá theo
 * chứng thư bên nhận, thử lại theo lịch và đưa thư chết vào {@code integ.dead_letter}; MOD-06 chỉ
 * gửi một lần cho mỗi {@code transferId} (gửi lại cùng mã trả trạng thái hiện tại) rồi hỏi kết quả
 * định kỳ (wiki trang 14).
 */
public interface EdxmlGateway {

  /** Gói con tối đa 20 MB trước base64 (thoả thuận với DV-13-05). */
  long MAX_PAYLOAD_BYTES = 20L * 1024 * 1024;

  /** Một tệp đính kèm gói con. */
  record Attachment(String name, String contentType, byte[] content) {}

  /** Gói con cần gửi. */
  record Outgoing(
      UUID transferId,
      UUID packageId,
      String destination,
      String subject,
      String documentNumber,
      List<Attachment> attachments) {}

  /** Trạng thái ở SVC-13: {@code CHO}, {@code DA_GUI}, {@code DA_XAC_NHAN}, {@code LOI}. */
  record RemoteState(
      UUID transferId,
      String status,
      int attempts,
      Instant nextAttemptAt,
      String ackRef,
      Instant acknowledgedAt,
      String lastError) {}

  /** SVC-13 chưa cấu hình kết nối tới nơi nhận ({@code integ.connector}). */
  final class DestinationNotConfiguredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DestinationNotConfiguredException(String destination) {
      super("Nơi nhận " + destination + " chưa được cấu hình kết nối.");
    }
  }

  /** SVC-13 từ chối gói con vượt giới hạn kích thước. */
  final class PayloadTooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PayloadTooLargeException() {
      super("Gói con vượt quá 20 MB.");
    }
  }

  /** SVC-13 chưa cấu hình hoặc tạm không gọi được: lần sau gửi lại. */
  final class UnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Gửi gói con; trả trạng thái SVC-13 vừa ghi nhận. */
  RemoteState send(TenantId tenant, Outgoing outgoing);

  /** Trạng thái hiện tại; rỗng khi SVC-13 chưa có lần gửi này hoặc tạm không gọi được. */
  Optional<RemoteState> state(TenantId tenant, UUID transferId);
}
