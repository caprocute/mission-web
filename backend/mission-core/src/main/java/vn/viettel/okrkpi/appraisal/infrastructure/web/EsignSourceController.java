package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-10 hỏi tài liệu cần ký của phiếu tự đánh giá ({@code GET
 * /internal/v1/esign-sources/appraisal/{id}?purpose=TU_DANH_GIA}, suy luận ở wiki trang 14). Tài
 * liệu là băm {@code content_hash} của phiếu đã chốt, ký dạng {@code JWS}; người ký duy nhất là chủ
 * hồ sơ. Không nằm trong hợp đồng công khai; bảo vệ bằng xác thực dịch vụ của mesh.
 */
@RestController
@RequestMapping("/internal/v1/esign-sources/appraisal")
public class EsignSourceController {

  static final String PURPOSE = "TU_DANH_GIA";
  static final String KIND = "PHIEU_TU_DANH_GIA";
  static final String FORMAT = "JWS";

  private final SelfAssessmentService service;

  public EsignSourceController(SelfAssessmentService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /** Thân phản hồi theo {@code HttpSourceDocumentClient} của SVC-10. */
  public record Source(boolean ready, Set<UUID> signers, List<Doc> documents) {}

  /** Một tài liệu, băm hex chữ thường. */
  public record Doc(String kind, String ref, String sha256, String format) {}

  @GetMapping("/{id}")
  public ResponseEntity<Source> source(
      @PathVariable("id") UUID id, @RequestParam("purpose") String purpose) {
    if (!PURPOSE.equals(purpose)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    SelfAssessmentService.SignSource s =
        service
            .signSource(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    List<Doc> documents =
        s.ready()
            ? List.of(
                new Doc(
                    KIND,
                    s.selfAssessmentId().toString(),
                    HexFormat.of().formatHex(s.contentHash()),
                    FORMAT))
            : List.of();
    return ResponseEntity.ok(new Source(s.ready(), Set.of(s.signer()), documents));
  }
}
