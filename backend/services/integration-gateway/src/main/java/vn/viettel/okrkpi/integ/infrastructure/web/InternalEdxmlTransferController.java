package vn.viettel.okrkpi.integ.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.integ.application.EdxmlTransferService;
import vn.viettel.okrkpi.integ.application.EdxmlTransferService.Status;
import vn.viettel.okrkpi.integ.application.TransferRejectedException;
import vn.viettel.okrkpi.integ.domain.OutboundStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng nội bộ gửi văn bản EdXML ra Trục liên thông cho MOD-06 (DV-13-05, CORE-06-08 gửi hồ sơ cán
 * bộ ra BTC_TW, VPTW): chỉ token dịch vụ (chuỗi bảo mật {@code /internal/**}). {@code transferId}
 * là khoá chống trùng: gửi lại cùng mã trả trạng thái đang có. Nơi nhận chưa cấu hình hoặc tệp quá
 * trần trả 422 kèm {@code reason}; thân yêu cầu quá {@value #MAX_BODY_BYTES} byte trả 413. Hợp đồng
 * đã thống nhất với CORE-06-08 (wiki trang 14), chưa có trong {@code contracts/openapi} vì là cổng
 * nội bộ.
 */
@RestController
@RequestMapping("/internal/v1/integ/edxml-transfers")
@ConditionalOnWebApplication
public class InternalEdxmlTransferController {

  static final String TENANT_HEADER = "X-Tenant-Id";
  static final long MAX_BODY_BYTES = 28L * 1024 * 1024;

  private final EdxmlTransferService transfers;

  public InternalEdxmlTransferController(EdxmlTransferService transfers) {
    this.transfers = transfers;
  }

  /** Tệp đính kèm, nội dung base64 (gói con MOD-06 đã ký, mã hoá). */
  public record AttachmentDto(String name, String contentType, String contentBase64) {}

  public record TransferRequest(
      UUID transferId,
      UUID packageId,
      String destination,
      String subject,
      String documentNumber,
      List<AttachmentDto> attachments) {}

  public record TransferDto(
      UUID transferId,
      String destination,
      String status,
      int attempts,
      Instant nextAttemptAt,
      String ackRef,
      Instant acknowledgedAt,
      String lastError) {

    static TransferDto of(Status s) {
      return new TransferDto(
          s.transferId(),
          s.destination(),
          s.status().name(),
          s.attempts(),
          s.status() == OutboundStatus.CHO || s.status() == OutboundStatus.DA_GUI
              ? s.nextAttemptAt()
              : null,
          s.ackRef(),
          s.status() == OutboundStatus.DA_XAC_NHAN ? s.updatedAt() : null,
          s.lastError());
    }
  }

  /** Thân quá trần: Spring đã đọc xong nên chặn theo độ dài khai báo trước khi giải base64. */
  static final class TooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  @PostMapping
  public ResponseEntity<TransferDto> submit(
      @RequestHeader(TENANT_HEADER) UUID tenantId,
      @RequestHeader(name = "Content-Length", required = false) Long length,
      @RequestBody TransferRequest body) {
    if (length != null && length > MAX_BODY_BYTES) {
      throw new TooLargeException();
    }
    if (body == null) {
      throw new IllegalArgumentException("Thiếu thân yêu cầu");
    }
    List<EdxmlTransferService.Attachment> files =
        body.attachments() == null
            ? List.of()
            : body.attachments().stream().map(InternalEdxmlTransferController::decode).toList();
    Status s =
        transfers.submit(
            TenantId.of(tenantId),
            new EdxmlTransferService.Request(
                body.transferId(),
                body.packageId(),
                body.destination(),
                body.subject(),
                body.documentNumber(),
                files));
    return ResponseEntity.accepted()
        .location(URI.create("/internal/v1/integ/edxml-transfers/" + s.transferId()))
        .body(TransferDto.of(s));
  }

  @GetMapping("/{transferId}")
  public TransferDto status(
      @RequestHeader(TENANT_HEADER) UUID tenantId, @PathVariable UUID transferId) {
    return transfers
        .status(TenantId.of(tenantId), transferId)
        .map(TransferDto::of)
        .orElseThrow(() -> new NoSuchElementException("Không có lần gửi này"));
  }

  private static EdxmlTransferService.Attachment decode(AttachmentDto a) {
    if (a == null || a.contentBase64() == null) {
      throw new IllegalArgumentException("Tệp đính kèm thiếu nội dung");
    }
    try {
      return new EdxmlTransferService.Attachment(
          a.name(), a.contentType(), Base64.getDecoder().decode(a.contentBase64()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Nội dung tệp đính kèm không đúng base64");
    }
  }

  @ExceptionHandler(TooLargeException.class)
  ProblemDetail tooLarge() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.PAYLOAD_TOO_LARGE, "Thân yêu cầu vượt trần cho phép");
  }

  @ExceptionHandler(TransferRejectedException.class)
  ProblemDetail rejected(TransferRejectedException e) {
    ProblemDetail p =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    p.setProperty("reason", e.reason().name());
    return p;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  ProblemDetail notFound(NoSuchElementException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }
}
