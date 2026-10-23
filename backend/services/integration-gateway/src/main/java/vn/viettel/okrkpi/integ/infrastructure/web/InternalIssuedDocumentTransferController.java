package vn.viettel.okrkpi.integ.infrastructure.web;

import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.integ.application.IssuedDocumentTransfer;
import vn.viettel.okrkpi.integ.application.IssuedDocumentTransfer.Transfer;
import vn.viettel.okrkpi.integ.infrastructure.web.InternalIssuedDocumentController.DocDto;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng nội bộ chuyển tệp gốc văn bản đã ban hành sang kho minh chứng cho MOD-03 (CORE-03-04,
 * BR-07-13), cùng bảo vệ với tra cứu: token dịch vụ có vai trò {@link
 * InternalIssuedDocumentController#SERVICE_ROLE}. 404 khi văn bản không có hoặc không thuộc đơn vị;
 * chữ ký không hợp lệ trả 200 với {@code signatureValid=false} và không có đối tượng; hệ văn bản
 * hay kho minh chứng lỗi trả 502. Đường dẫn và thân theo đề xuất ghi ở wiki trang 14.
 */
@RestController
@RequestMapping("/internal/v1/integ")
@ConditionalOnWebApplication
public class InternalIssuedDocumentTransferController {

  private final IssuedDocumentTransfer transfers;

  public InternalIssuedDocumentTransferController(IssuedDocumentTransfer transfers) {
    this.transfers = transfers;
  }

  /** Lệnh chuyển. */
  public record TransferRequest(
      UUID tenantId,
      UUID orgUnitId,
      String ref,
      UUID ownerRefId,
      UUID requestedBy,
      String connector) {}

  /** Kết quả; các trường đối tượng rỗng khi chữ ký không hợp lệ. */
  public record TransferDto(
      UUID objectId,
      Integer objectVersion,
      String sha256,
      Long sizeBytes,
      String mediaType,
      boolean signatureValid,
      DocDto doc) {}

  @PostMapping("/issued-docs/transfer")
  public TransferDto transfer(
      @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody TransferRequest body) {
    if (body.tenantId() == null || body.orgUnitId() == null) {
      throw new IllegalArgumentException("Thiếu tenant hoặc đơn vị");
    }
    if (body.ownerRefId() == null || body.requestedBy() == null) {
      throw new IllegalArgumentException("Thiếu minh chứng hoặc người yêu cầu");
    }
    Transfer t =
        transfers
            .transfer(
                TenantId.of(body.tenantId()),
                body.connector() == null ? "VOFFICE" : body.connector(),
                body.orgUnitId(),
                body.ref(),
                body.ownerRefId(),
                body.requestedBy(),
                idempotencyKey)
            .orElseThrow(() -> new NoSuchElementException("Không có văn bản trong đơn vị"));
    DocDto doc =
        new DocDto(
            t.document().ref(),
            t.document().docNumber(),
            t.document().docDate(),
            t.document().summary(),
            t.document().signerName(),
            t.signatureValid());
    if (t.stored() == null) {
      return new TransferDto(null, null, null, null, null, false, doc);
    }
    return new TransferDto(
        t.stored().objectId(),
        t.stored().objectVersion(),
        HexFormat.of().formatHex(t.stored().sha256()),
        t.stored().sizeBytes(),
        t.stored().mediaType(),
        true,
        doc);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  ProblemDetail notFound(NoSuchElementException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(UncheckedIOException.class)
  ProblemDetail upstream(UncheckedIOException e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_GATEWAY, "Không chuyển được tệp gốc, vui lòng thử lại sau");
  }
}
