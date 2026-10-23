package vn.viettel.okrkpi.evidence.infrastructure.web;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.viettel.okrkpi.evidence.application.OcrContentService;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/**
 * Cổng nội bộ cho ai-gateway đọc nội dung tệp cần OCR (DV-08-05): ai-gateway ghép {@code
 * OBJECT_BASE_URL} với khoá {@code <tenant>/<đối tượng>/<phiên bản>} đã nhận trong {@code
 * AiOcrRequest}. Không qua APISIX; xác thực dịch vụ bằng mTLS của lưới (AuthorizationPolicy chỉ cho
 * danh tính ai-gateway), vì ai-gateway đọc kho không kèm token (DV-14-05). Chỉ tệp đã quét sạch,
 * ảnh hoặc PDF và không phải C3.
 */
@RestController
@RequestMapping("/internal/v1/ocr-objects")
@ConditionalOnWebApplication
public class InternalOcrObjectController {

  private final OcrContentService content;

  public InternalOcrObjectController(OcrContentService content) {
    this.content = content;
  }

  @GetMapping("/{tenantId}/{objectId}/{versionNo}")
  public ResponseEntity<StreamingResponseBody> read(
      @PathVariable UUID tenantId, @PathVariable UUID objectId, @PathVariable int versionNo) {
    StoredFile f = content.authorize(tenantId, objectId, versionNo);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(f.mediaType()))
        .contentLength(f.sizeBytes())
        .header("Cache-Control", "no-store")
        .body(out -> content.copy(f, out));
  }

  @ExceptionHandler(OcrContentService.DeniedException.class)
  ResponseEntity<ProblemDetail> denied(OcrContentService.DeniedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage()));
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<ProblemDetail> notFound(NoSuchElementException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Không có tệp"));
  }
}
