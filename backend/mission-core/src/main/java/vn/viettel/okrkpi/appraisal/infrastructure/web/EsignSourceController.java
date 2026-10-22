package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-10 hỏi tài liệu cần ký của phiếu tự đánh giá và kết luận thẩm định ({@code GET
 * /internal/v1/esign-sources/appraisal/{id}?purpose=TU_DANH_GIA|THAM_DINH|QUYET_DINH}, suy luận ở
 * wiki trang 14). Tài liệu là băm {@code content_hash} của phiếu đã chốt, ký dạng {@code JWS};
 * người ký duy nhất là chủ hồ sơ. Không nằm trong hợp đồng công khai; bảo vệ bằng xác thực dịch vụ
 * của mesh.
 */
@RestController
@RequestMapping("/internal/v1/esign-sources/appraisal")
public class EsignSourceController {

  static final String PURPOSE = "TU_DANH_GIA";
  static final String KIND = "PHIEU_TU_DANH_GIA";
  static final String APPRAISAL_PURPOSE = "THAM_DINH";
  static final String APPRAISAL_KIND = "KET_QUA_THAM_DINH";
  static final String FORMAT = "JWS";
  static final String DECISION_PURPOSE = "QUYET_DINH";
  static final String DECISION_KIND = "QUYET_DINH_XEP_LOAI";
  static final String DECISION_FORMAT = "PADES_LTA";

  private final SelfAssessmentService service;
  private final ObjectProvider<ReviewService> reviews;
  private final ObjectProvider<BatchDecisionService> decisions;

  public EsignSourceController(
      SelfAssessmentService service, ObjectProvider<ReviewService> reviews) {
    this(service, reviews, null);
  }

  @Autowired
  public EsignSourceController(
      SelfAssessmentService service,
      ObjectProvider<ReviewService> reviews,
      ObjectProvider<BatchDecisionService> decisions) {
    this.service = Objects.requireNonNull(service, "service");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.decisions = decisions;
  }

  /** Thân phản hồi theo {@code HttpSourceDocumentClient} của SVC-10. */
  public record Source(boolean ready, Set<UUID> signers, List<Doc> documents) {}

  /** Một tài liệu, băm hex chữ thường. */
  public record Doc(String kind, String ref, String sha256, String format) {}

  @GetMapping("/{id}")
  public ResponseEntity<Source> source(
      @PathVariable("id") UUID id, @RequestParam("purpose") String purpose) {
    if (APPRAISAL_PURPOSE.equals(purpose) && reviews.getIfAvailable() != null) {
      return appraisal(id);
    }
    if (DECISION_PURPOSE.equals(purpose)
        && decisions != null
        && decisions.getIfAvailable() != null) {
      return decision(id);
    }
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

  /** Kết luận thẩm định hiện hành (CORE-04-05): người ký là người thẩm định đã ghi kết luận. */
  private ResponseEntity<Source> appraisal(UUID id) {
    ReviewService.SignSource s =
        reviews
            .getObject()
            .signSource(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (s.proposalId() == null) {
      return ResponseEntity.ok(new Source(false, Set.of(), List.of()));
    }
    List<Doc> documents =
        s.ready()
            ? List.of(
                new Doc(
                    APPRAISAL_KIND,
                    s.proposalId().toString(),
                    HexFormat.of().formatHex(s.contentHash()),
                    FORMAT))
            : List.of();
    return ResponseEntity.ok(new Source(s.ready(), Set.of(s.signer()), documents));
  }

  /**
   * Lô quyết định (CORE-04-07, ADR-31): mỗi hồ sơ chưa ký của lô là một tài liệu PAdES, mã tham
   * chiếu là mã hồ sơ; người ký duy nhất là người quyết định của lô.
   */
  private ResponseEntity<Source> decision(UUID id) {
    BatchDecisionService.SignSource s =
        decisions
            .getObject()
            .signSource(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    List<Doc> documents = new ArrayList<>();
    for (BatchDecisionService.SignDoc d : s.documents()) {
      documents.add(
          new Doc(
              DECISION_KIND,
              d.profileId().toString(),
              HexFormat.of().formatHex(d.contentHash()),
              DECISION_FORMAT));
    }
    return ResponseEntity.ok(new Source(s.ready(), Set.of(s.signer()), documents));
  }
}
