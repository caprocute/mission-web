package vn.viettel.okrkpi.appraisal.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService.View;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.CriteriaRow;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.StoredSelfAssessment;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.TrailRow;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalPeriodKind;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfile;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfileStatus;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalRatingProposal;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalSelfAssessment;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalTaskClaim;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.contract.appraisals.model.GeneralCriteriaItem;
import vn.viettel.okrkpi.contract.appraisals.model.GeneralCriteriaLevel;
import vn.viettel.okrkpi.contract.appraisals.model.GeneralCriteriaResult;
import vn.viettel.okrkpi.contract.appraisals.model.GeneralCriteriaStage;
import vn.viettel.okrkpi.contract.appraisals.model.PeriodRef;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevelKind;
import vn.viettel.okrkpi.contract.appraisals.model.Ref;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ánh xạ hồ sơ đánh giá sang mô hình của {@code appraisals.yaml}, dùng chung cho phiếu tự đánh giá
 * (CORE-04-04) và thẩm định, đề xuất (CORE-04-05). Tên người, đơn vị chưa có bản sao trong MOD-04
 * nên trả rỗng; loại kỳ mặc định {@code QUY} (suy luận).
 */
final class AppraisalProfileMapper {

  private final ObjectMapper json;
  private final ObjectProvider<PeriodApi> periods;

  AppraisalProfileMapper(ObjectMapper json, ObjectProvider<PeriodApi> periods) {
    this.json = json;
    this.periods = periods;
  }

  AppraisalProfile profile(TenantId tenant, View view) {
    ProfileSummary p = view.profile();
    List<AppraisalRatingProposal> trail = new ArrayList<>();
    for (TrailRow t : view.trail()) {
      trail.add(
          new AppraisalRatingProposal(
              t.id(),
              RatingLevelKind.fromValue(t.levelKind().name()),
              rating(t.rating()),
              new Ref(t.actorId(), ""),
              AppraisalRatingProposal.ActorRoleEnum.fromValue(t.actorRole()),
              t.current(),
              time(t.proposedAt())));
    }
    AppraisalProfile dto =
        new AppraisalProfile(
            p.id(),
            new Ref(p.personId(), ""),
            new Ref(p.orgUnitId(), ""),
            periodRef(tenant, p.periodId()),
            AppraisalPeriodKind.QUY,
            Flow.fromValue(p.flow()),
            List.of(Flow.fromValue(p.flow())),
            AppraisalProfileStatus.fromValue(p.status().name()),
            trail,
            (int) p.rowVersion());
    dto.setIsHead(p.head());
    dto.setIsLeadership(p.leadership());
    dto.setCurrentSnapshotId(p.currentSnapshotId());
    dto.setFinalRatingCode(p.finalRating() == null ? null : rating(p.finalRating()));
    dto.setReturnCount(p.returnCount());
    dto.setNoSelfAssessment(p.noSelfAssessment());
    dto.setSubmittedAt(time(p.submittedAt()));
    dto.setDecidedAt(time(p.decidedAt()));
    dto.setLockedAt(time(p.lockedAt()));
    if (p.appraiserId() != null) {
      dto.setAppraiser(new Ref(p.appraiserId(), ""));
    }
    if (p.deciderId() != null) {
      dto.setDecider(new Ref(p.deciderId(), ""));
    }
    dto.setGeneralCriteria(criteria(view.criteria()));
    if (view.stored() != null) {
      dto.setSelfAssessment(selfAssessment(view.stored(), view.form()));
    }
    return dto;
  }

  private static List<GeneralCriteriaResult> criteria(List<CriteriaRow> rows) {
    Map<String, GeneralCriteriaResult> byStage = new LinkedHashMap<>();
    for (CriteriaRow r : rows) {
      GeneralCriteriaResult stage =
          byStage.computeIfAbsent(
              r.stage(),
              s ->
                  new GeneralCriteriaResult(
                      GeneralCriteriaStage.fromValue(s),
                      new ArrayList<>(),
                      r.ratio(),
                      GeneralCriteriaLevel.fromValue(r.level().name())));
      stage.getItems().add(new GeneralCriteriaItem(r.code(), r.met()));
    }
    return new ArrayList<>(byStage.values());
  }

  AppraisalSelfAssessment selfAssessment(StoredSelfAssessment s, SelfAssessmentForm f) {
    AppraisalSelfAssessment dto = new AppraisalSelfAssessment(s.versionNo());
    dto.setFormCode(s.formCode());
    dto.setFormVersion(s.formVersion());
    dto.setSelfRatingCode(f.selfRating() == null ? null : rating(f.selfRating()));
    dto.setBreakthrough(f.breakthrough());
    dto.setBreakthroughEvidenceIds(new ArrayList<>(f.breakthroughEvidenceIds()));
    dto.setReason(f.reason());
    dto.setContent(f.content());
    dto.setStrengths(f.strengths());
    dto.setWeaknesses(f.weaknesses());
    dto.setRemedialPlan(f.remedialPlan());
    List<AppraisalTaskClaim> claims = new ArrayList<>();
    for (Map<String, Object> c : f.claims()) {
      claims.add(json.convertValue(c, AppraisalTaskClaim.class));
    }
    dto.setClaims(claims);
    if (s.contentHash() != null) {
      dto.setContentHash(HexFormat.of().formatHex(s.contentHash()));
    }
    dto.setSavedAt(time(s.updatedAt()));
    dto.setSubmittedAt(time(s.submittedAt()));
    dto.setSignedAt(time(s.signedAt()));
    return dto;
  }

  PeriodRef periodRef(TenantId tenant, UUID periodId) {
    PeriodApi api = periods.getIfAvailable();
    Optional<PeriodSnapshot> snapshot =
        api == null ? Optional.empty() : api.findById(tenant, PeriodId.of(periodId));
    return snapshot
        .map(s -> new PeriodRef(periodId, s.name(), s.startDate(), s.endDate()))
        .orElseGet(() -> new PeriodRef(periodId, "", null, null));
  }

  static vn.viettel.okrkpi.contract.appraisals.model.RatingLevel rating(RatingLevel r) {
    if (r == null) {
      return null;
    }
    return vn.viettel.okrkpi.contract.appraisals.model.RatingLevel.fromValue(r.name());
  }

  static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }
}
