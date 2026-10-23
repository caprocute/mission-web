package vn.viettel.okrkpi.integ.infrastructure.web;

import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSearch;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource.Result;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng nội bộ cho MOD-03 phục vụ {@code GET /api/v1/evidences/external-docs} (QT-07, SCR-0701,
 * BR-07-13): không thuộc API công khai, không qua APISIX, chỉ token dịch vụ có vai trò {@link
 * #SERVICE_ROLE}. Tenant và đơn vị nội bộ do mission-core truyền vì token dịch vụ không mang tenant
 * (suy luận, hợp đồng nội bộ chưa có trong {@code contracts/openapi}, cùng cách của org-service).
 * Thân trả lời giữ đúng tên trường của {@code EvidenceExternalDocList}.
 */
@RestController
@RequestMapping("/internal/v1/integ")
@ConditionalOnWebApplication
public class InternalIssuedDocumentController {

  public static final String SERVICE_ROLE = "svc_mission_core";

  private final IssuedDocumentSearch search;

  public InternalIssuedDocumentController(IssuedDocumentSearch search) {
    this.search = search;
  }

  /** Một văn bản đã ban hành. */
  public record DocDto(
      String ref,
      String docNumber,
      LocalDate docDate,
      String summary,
      String signerName,
      boolean signatureValid) {}

  /** Một trang kết quả. */
  public record DocList(List<DocDto> items, String nextCursor) {}

  @GetMapping("/issued-docs")
  public DocList issuedDocs(
      @RequestParam UUID tenantId,
      @RequestParam UUID orgUnitId,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "VOFFICE") String connector) {
    Result r = search.search(TenantId.of(tenantId), connector, orgUnitId, q, limit, cursor);
    return new DocList(
        r.items().stream()
            .map(
                d ->
                    new DocDto(
                        d.ref(),
                        d.docNumber(),
                        d.docDate(),
                        d.summary(),
                        d.signerName(),
                        d.signatureValid()))
            .toList(),
        r.nextCursor());
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
        HttpStatus.BAD_GATEWAY, "Hệ văn bản ngoài không phản hồi, vui lòng thử lại sau");
  }
}
