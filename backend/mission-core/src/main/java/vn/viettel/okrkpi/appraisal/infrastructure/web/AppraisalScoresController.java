package vn.viettel.okrkpi.appraisal.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.review.ReviewParticipants;
import vn.viettel.okrkpi.appraisal.application.score.ScoreViewService;
import vn.viettel.okrkpi.appraisal.application.score.ScoreViewService.ScoreView;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader.Snapshot;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalDowngradeReason;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalRatingExplanation;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalRuleCheck;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoreGroup;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoreSnapshot;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoreView;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.contract.appraisals.model.GeneralCriteriaLevel;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevel;
import vn.viettel.okrkpi.contract.appraisals.model.ScoreSnapshotKind;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code getAppraisalScores} của {@code appraisals.yaml} (CORE-04-09, SCR-0901, SCR-0903): ảnh chụp
 * điểm hiện hành theo luồng và giải thích của hệ thống. ETag là băm ảnh chụp ({@code
 * snapshot_hash}): ảnh chụp bất biến nên trình duyệt và lớp đệm HTTP dùng lại được bằng {@code
 * If-None-Match} trong ngày công bố kết quả, không cần bộ đệm phía máy chủ (dữ liệu chỉ C1,
 * ADR-18). Vết tính ({@code getAppraisalScoreTrace}) do SVC-07 phục vụ trực tiếp (DV-07-03),
 * mission-core không đọc bảng {@code scoring}.
 */
@RestController
@RequestMapping("/api/v1/appraisals/profiles")
public class AppraisalScoresController {

  private final ScoreViewService service;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<AppraisalReviewGuard> guards;
  private final ObjectMapper json;

  public AppraisalScoresController(
      ScoreViewService service,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<AppraisalReviewGuard> guards,
      ObjectProvider<ObjectMapper> json) {
    this.service = Objects.requireNonNull(service, "service");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.guards = Objects.requireNonNull(guards, "guards");
    ObjectMapper mapper = json.getIfAvailable();
    this.json =
        (mapper == null ? new ObjectMapper() : mapper.copy())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @GetMapping("/{id}/scores")
  @RequiresPermission(
      operationId = "getAppraisalScores",
      module = "appraisals",
      resourceType = "appraisal_profile")
  public ResponseEntity<AppraisalScoreView> getAppraisalScores(
      @PathVariable("id") UUID id,
      @RequestParam(value = "flow", required = false) String flow,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    if (flow != null && !"DANG".equals(flow) && !"NHA_NUOC".equals(flow)) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Luồng đánh giá không hợp lệ.",
          List.of(new ProblemFieldError("flow", "Luồng đánh giá không hợp lệ.", null)));
    }
    TenantId tenant = TenantContext.require();
    ScoreView view =
        service
            .view(
                tenant,
                id,
                flow,
                actor(),
                p -> AppraisalReviewGuard.canView(guards.getIfAvailable(), participants(tenant, p)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    String etag = "\"" + HexFormat.of().formatHex(view.snapshot().snapshotHash()) + "\"";
    CacheControl cache = CacheControl.noCache().cachePrivate();
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
    }
    return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(body(view));
  }

  AppraisalScoreView body(ScoreView view) {
    Snapshot s = view.snapshot();
    HexFormat hex = HexFormat.of();
    List<AppraisalRuleCheck> checks = list(s.ruleChecks(), new TypeReference<>() {});
    List<AppraisalDowngradeReason> reasons = list(s.downgradeReasons(), new TypeReference<>() {});
    AppraisalScoreSnapshot snapshot =
        new AppraisalScoreSnapshot(
            s.id(),
            s.profileId(),
            Flow.fromValue(s.flow()),
            ScoreSnapshotKind.fromValue(s.kind()),
            s.policyVersion(),
            hex.formatHex(s.inputHash()),
            hex.formatHex(s.snapshotHash()),
            s.totalScore(),
            RatingLevel.fromValue(s.autoRatingCode()),
            checks,
            reasons,
            groups(s.groupScores()),
            s.computedAt().atOffset(ZoneOffset.UTC));
    snapshot.setScoreResultId(s.scoreResultId());
    snapshot.setPolicyPackVersionId(s.policyPackVersionId());
    if (s.policyContentHash() != null) {
      snapshot.setPolicyContentHash(hex.formatHex(s.policyContentHash()));
    }
    snapshot.setRegularScore(s.regularScore());
    snapshot.setKeyTaskScore(s.keyTaskScore());
    snapshot.setGeneralCriteriaScore(s.generalCriteriaScore());
    snapshot.setKpiScore(s.kpiScore());
    snapshot.setExceedRatio(s.exceedRatio());
    snapshot.setObjectiveRatio(s.objectiveRatio());
    if (s.generalCriteriaLevel() != null) {
      snapshot.setGeneralCriteriaLevel(GeneralCriteriaLevel.fromValue(s.generalCriteriaLevel()));
    }
    snapshot.setSupersededById(s.supersededById());
    AppraisalScoreView out =
        new AppraisalScoreView(
            view.profile().id(), Flow.fromValue(view.profile().flow()), snapshot);
    AppraisalRatingExplanation explanation =
        new AppraisalRatingExplanation(RatingLevel.fromValue(s.autoRatingCode()), checks);
    String text = detailText(s.detail());
    if (text == null && !reasons.isEmpty()) {
      text = reasons.get(0).getText();
    }
    if (text != null) {
      explanation.setText(text);
    }
    if (!reasons.isEmpty() && reasons.get(0).getLegalRef() != null) {
      explanation.setLegalRef(reasons.get(0).getLegalRef());
    }
    out.setExplanation(explanation);
    return out;
  }

  /** Câu giải thích của SVC-07 trong {@code detail_json.giai_thich} (DV-07-05), nếu có. */
  private String detailText(String detail) {
    if (detail == null) {
      return null;
    }
    try {
      JsonNode node = json.readTree(detail).get("giai_thich");
      return node == null || !node.isTextual() ? null : node.asText();
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private List<AppraisalScoreGroup> groups(String value) {
    if (value == null) {
      return List.of();
    }
    try {
      JsonNode node = json.readTree(value);
      if (node.isArray()) {
        return json.convertValue(node, new TypeReference<>() {});
      }
      if (node.get("groups") != null && node.get("groups").isArray()) {
        return json.convertValue(node.get("groups"), new TypeReference<>() {});
      }
      return List.of();
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return List.of();
    }
  }

  private <T> List<T> list(String value, TypeReference<List<T>> type) {
    if (value == null) {
      return List.of();
    }
    try {
      List<T> out = json.readValue(value, type);
      return out == null ? List.of() : out;
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private static ReviewParticipants participants(TenantId tenant, ProfileSummary p) {
    return new ReviewParticipants(
        tenant.value(),
        p.id(),
        p.orgUnitId(),
        p.flow(),
        p.personId(),
        Set.of(),
        p.appraiserId(),
        p.deciderId());
  }

  private UUID actor() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    try {
      return UUID.fromString(subject.userId());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }
}
