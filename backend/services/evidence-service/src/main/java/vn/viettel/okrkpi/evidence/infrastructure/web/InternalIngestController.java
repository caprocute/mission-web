package vn.viettel.okrkpi.evidence.infrastructure.web;

import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.evidence.application.IngestService;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/**
 * Cổng nội bộ cho integration-gateway chuyển tệp gốc văn bản đã ban hành vào kho minh chứng
 * (CORE-03-04, BR-07-13). Không qua APISIX; như cổng OCR, xác thực dịch vụ dựa vào mTLS của lưới
 * (AuthorizationPolicy chỉ cho danh tính integration-gateway). Thân là nội dung tệp, kiểu tệp ở
 * {@code Content-Type}, tên tệp (C2, mã hoá khi lưu) ở tham số {@code fileName}.
 */
@RestController
@RequestMapping("/internal/v1/ingested-objects")
@ConditionalOnWebApplication
public class InternalIngestController {

  private final IngestService ingest;

  public InternalIngestController(IngestService ingest) {
    this.ingest = ingest;
  }

  /** Đối tượng đã lưu; {@code sha256} là chuỗi thập lục phân. */
  public record Ingested(
      UUID objectId, int objectVersion, String sha256, long sizeBytes, String mediaType) {}

  @PostMapping
  public Ingested ingest(
      @RequestParam UUID tenantId,
      @RequestParam UUID ownerRefId,
      @RequestParam UUID requestedBy,
      @RequestParam(required = false) String fileName,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
      @RequestBody byte[] content) {
    StoredFile f =
        ingest.ingest(
            new IngestService.Ingest(
                tenantId,
                idempotencyKey,
                ownerRefId,
                requestedBy,
                fileName,
                contentType.split(";")[0].strip(),
                content));
    return new Ingested(
        f.objectId(),
        f.versionNo(),
        HexFormat.of().formatHex(f.sha256()),
        f.sizeBytes(),
        f.mediaType());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
