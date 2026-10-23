package vn.viettel.okrkpi.integ.infrastructure.web;

import java.net.URI;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import vn.viettel.okrkpi.integ.application.CellTransferService;
import vn.viettel.okrkpi.integ.application.CellTransferService.RejectedException;
import vn.viettel.okrkpi.integ.application.CellTransferService.View;
import vn.viettel.okrkpi.integ.application.SourceUnavailableException;
import vn.viettel.okrkpi.integ.application.port.CellChannel;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng gói chuyển hồ sơ cán bộ liên cell (DV-13-10). Hai đường cho org-service ở cell nguồn (token
 * dịch vụ): {@code POST /} đóng gói theo yêu cầu điều động đã duyệt ({@code packageId} chống
 * trùng), {@code GET /{id}} trạng thái. Hai đường cho gateway của cell đối tác qua kênh liên cell:
 * {@code POST /inbound} nhận gói, {@code POST /outcomes} nhận kết quả nhập; hai đường này xác thực
 * bằng chữ ký cell theo danh bạ khoá (chuỗi bảo mật để mở ở lớp ứng dụng, lưới dịch vụ chỉ cho cổng
 * ra liên cell gọi). Từ chối trả 422 kèm {@code reason}; nguồn nội bộ lỗi trả 502. Cổng nội bộ nên
 * chưa có trong {@code contracts/openapi}.
 */
@RestController
@RequestMapping(InternalCellTransferController.BASE)
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "okrkpi.integ.cell-transfer.enabled", havingValue = "true")
public class InternalCellTransferController {

  public static final String BASE = "/internal/v1/integ/cell-transfers";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final long MAX_BODY_BYTES = 72L * 1024 * 1024;

  private final CellTransferService transfers;

  public InternalCellTransferController(CellTransferService transfers) {
    this.transfers = transfers;
  }

  /** Yêu cầu điều động đã duyệt. */
  public record PackRequest(
      UUID packageId,
      UUID personId,
      String sourceTenantCode,
      String targetCell,
      UUID targetTenantId,
      String targetTenantCode) {}

  /** Gói từ cell nguồn, các trường base64. */
  public record EnvelopeDto(String header, String signature, String wrappedKey, String content) {}

  /** Kết quả nhập từ cell đích, các trường base64. */
  public record ReportDto(String payload, String signature) {}

  static final class TooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  @PostMapping
  public ResponseEntity<View> pack(
      @RequestHeader(TENANT_HEADER) UUID tenantId, @RequestBody PackRequest body) {
    if (body == null) {
      throw new IllegalArgumentException("Thiếu thân yêu cầu");
    }
    View v =
        transfers.pack(
            TenantId.of(tenantId),
            new CellTransferService.Request(
                body.packageId(),
                body.personId(),
                body.sourceTenantCode(),
                body.targetCell(),
                body.targetTenantId(),
                body.targetTenantCode()));
    return ResponseEntity.accepted().location(URI.create(BASE + "/" + v.packageId())).body(v);
  }

  @GetMapping("/{packageId}")
  public View status(@RequestHeader(TENANT_HEADER) UUID tenantId, @PathVariable UUID packageId) {
    return transfers
        .status(TenantId.of(tenantId), packageId)
        .orElseThrow(() -> new NoSuchElementException("Không có gói chuyển này"));
  }

  @PostMapping("/inbound")
  public ResponseEntity<View> inbound(
      @RequestHeader(name = "Content-Length", required = false) Long length,
      @RequestBody EnvelopeDto body) {
    if (length != null && length > MAX_BODY_BYTES) {
      throw new TooLargeException();
    }
    if (body == null) {
      throw new IllegalArgumentException("Thiếu thân yêu cầu");
    }
    View v =
        transfers.receive(
            new CellChannel.Envelope(
                decode(body.header()),
                decode(body.signature()),
                decode(body.wrappedKey()),
                decode(body.content())));
    return ResponseEntity.accepted().body(v);
  }

  @PostMapping("/outcomes")
  public View outcome(@RequestBody ReportDto body) {
    if (body == null) {
      throw new IllegalArgumentException("Thiếu thân yêu cầu");
    }
    return transfers.acknowledge(
        new CellChannel.Report(decode(body.payload()), decode(body.signature())));
  }

  private static byte[] decode(String b64) {
    if (b64 == null) {
      return null;
    }
    try {
      return Base64.getDecoder().decode(b64);
    } catch (IllegalArgumentException e) {
      throw new RejectedException(
          CellTransferService.Reasons.MALFORMED, "Trường gói không đúng base64");
    }
  }

  @ExceptionHandler(TooLargeException.class)
  ProblemDetail tooLarge() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.PAYLOAD_TOO_LARGE, "Thân yêu cầu vượt trần cho phép");
  }

  @ExceptionHandler(RejectedException.class)
  ProblemDetail rejected(RejectedException e) {
    ProblemDetail p =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    p.setProperty("reason", e.reason());
    return p;
  }

  @ExceptionHandler(SourceUnavailableException.class)
  ProblemDetail unavailable(SourceUnavailableException e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_GATEWAY, "Nguồn hồ sơ hoặc kho đối tượng chưa sẵn sàng");
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
