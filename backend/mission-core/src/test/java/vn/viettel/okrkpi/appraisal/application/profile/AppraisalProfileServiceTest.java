package vn.viettel.okrkpi.appraisal.application.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalProfile;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalRuleViolation;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingRecorded;
import vn.viettel.okrkpi.appraisal.domain.profile.StepCompleted;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dịch vụ máy trạng thái hồ sơ (CORE-04-03) với kho giả trong bộ nhớ, không cần CSDL. */
class AppraisalProfileServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2027-04-01T00:00:00Z"), ZoneOffset.UTC);

  private final UUID person = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final FakeRepository repo = new FakeRepository();
  private final List<StepCompleted> published = new ArrayList<>();

  private AppraisalProfileService service(PolicyQueryApi policies) {
    return new AppraisalProfileService(
        repo, (t, id, step, v) -> published.add(step), policies, CLOCK);
  }

  @Test
  void boQuaDeXuatThoiHienHanhMucDeXuat() {
    UUID proposal = UUID.randomUUID();
    repo.row =
        row(
            AppraisalStatus.DA_THAM_DINH,
            UUID.randomUUID(),
            List.of(
                new CurrentRating(
                    proposal,
                    LevelKind.DE_XUAT,
                    RatingLevel.HTNV,
                    UUID.randomUUID(),
                    false,
                    false,
                    false)));
    AppraisalProfileService.Transitioned t =
        service(null)
            .transition(TENANT, repo.row.id(), 3L, appraiser, (p, r) -> p.skipProposal(appraiser));
    assertThat(t.step().to()).isEqualTo(AppraisalStatus.CHO_QUYET_DINH);
    assertThat(t.rowVersion()).isEqualTo(4L);
    assertThat(repo.superseded).containsExactly(proposal);
    assertThat(published).hasSize(1);
  }

  @Test
  void lechPhienBanHoacGhiThatBaiTraXungDot() {
    repo.row = row(AppraisalStatus.CHUA_LAP, UUID.randomUUID(), List.of());
    AppraisalProfileService s = service(null);
    assertThatThrownBy(
            () ->
                s.transition(TENANT, repo.row.id(), 9L, person, (p, r) -> p.markSubmitted(person)))
        .isInstanceOf(VersionConflictException.class);
    repo.saveFails = true;
    assertThatThrownBy(
            () ->
                s.transition(
                    TENANT,
                    repo.row.id(),
                    null,
                    person,
                    (p, r) -> p.openSelfAssessment(r.currentSnapshotId(), r.autoRating(), person)))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> s.transition(TENANT, UUID.randomUUID(), null, person, (p, r) -> null))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void soLanTraLaiLayTuPolicyPack() {
    PolicyQueryApi one = policy(Optional.of(1));
    repo.row = row(AppraisalStatus.CHO_THAM_DINH, UUID.randomUUID(), List.of(), 1);
    assertThatThrownBy(
            () -> service(one).returnForRevision(TENANT, repo.row.id(), null, appraiser, "Bổ sung"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("APPRAISAL-409-RETURN-LIMIT"));
    assertThat(service(policy(Optional.of("hai"))).maxReturns(TENANT, repo.row)).isEqualTo(2);
    assertThat(service(policy(Optional.empty())).maxReturns(TENANT, repo.row)).isEqualTo(2);
    assertThat(service(null).maxReturns(TENANT, repo.row)).isEqualTo(2);
    AppraisalProfileService.Transitioned t =
        service(policy(Optional.of(3)))
            .returnForRevision(TENANT, repo.row.id(), null, appraiser, "Bổ sung");
    assertThat(t.step().to()).isEqualTo(AppraisalStatus.TU_DANH_GIA);
  }

  @Test
  void ghiMucVaGiaiTrinh() {
    repo.row = row(AppraisalStatus.TU_DANH_GIA, UUID.randomUUID(), List.of());
    AppraisalProfileService s = service(null);
    CurrentRating first =
        s.recordRating(
            TENANT,
            repo.row.id(),
            null,
            LevelKind.TU_DE_XUAT,
            RatingLevel.HTTNV,
            person,
            "staff",
            null,
            null);
    assertThat(first.explanationRequired()).isFalse();
    assertThat(repo.inserted).hasSize(1);
    repo.row = withRatings(repo.row, List.of(first));
    s.recordRating(
        TENANT,
        repo.row.id(),
        null,
        LevelKind.TU_DE_XUAT,
        RatingLevel.HTNV,
        person,
        "staff",
        new AppraisalProfileRepository.SealedText(new byte[] {1}, UUID.randomUUID()),
        null);
    assertThat(repo.superseded).containsExactly(first.proposalId());
    UUID explanation =
        s.recordExplanation(
            TENANT, repo.row.id(), LevelKind.TU_DE_XUAT, "HA_MUC", null, null, null, person);
    assertThat(explanation).isNotNull();
    assertThat(repo.explanations).containsExactly(first.proposalId());
    assertThatThrownBy(
            () ->
                s.recordExplanation(
                    TENANT,
                    repo.row.id(),
                    LevelKind.QUYET_DINH,
                    "HA_MUC",
                    null,
                    null,
                    null,
                    person))
        .isInstanceOf(BusinessException.class);
    assertThat(s.find(TENANT, repo.row.id())).isPresent();

    repo.row = row(AppraisalStatus.TU_DANH_GIA, null, List.of());
    assertThatThrownBy(
            () ->
                s.recordRating(
                    TENANT,
                    repo.row.id(),
                    null,
                    LevelKind.TU_DE_XUAT,
                    RatingLevel.HTNV,
                    person,
                    "staff",
                    null,
                    null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("APPRAISAL-409-NOT-COMPUTED"));
  }

  @ParameterizedTest
  @EnumSource(AppraisalRuleViolation.Code.class)
  void moiMaTuChoiCoMaLoiHopDong(AppraisalRuleViolation.Code code) {
    BusinessException e = AppraisalProfileService.toProblem(new AppraisalRuleViolation(code, "x"));
    assertThat(e.getMessage()).matches("(APPRAISAL|COMMON)-\\d{3}-[A-Z-]+");
    assertThat(EnumSet.allOf(AppraisalRuleViolation.Code.class)).contains(code);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private AppraisalProfileRepository.ProfileRow row(
      AppraisalStatus status, UUID snapshot, List<CurrentRating> ratings) {
    return row(status, snapshot, ratings, 0);
  }

  private AppraisalProfileRepository.ProfileRow row(
      AppraisalStatus status, UUID snapshot, List<CurrentRating> ratings, int returns) {
    return new AppraisalProfileRepository.ProfileRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        20271,
        person,
        "DANG",
        status,
        appraiser,
        null,
        snapshot,
        RatingLevel.HTTNV,
        null,
        returns,
        UUID.randomUUID(),
        3L,
        ratings);
  }

  private static AppraisalProfileRepository.ProfileRow withRatings(
      AppraisalProfileRepository.ProfileRow r, List<CurrentRating> ratings) {
    return new AppraisalProfileRepository.ProfileRow(
        r.id(),
        r.periodId(),
        r.periodBucket(),
        r.personId(),
        r.flow(),
        r.status(),
        r.appraiserId(),
        r.deciderId(),
        r.currentSnapshotId(),
        r.autoRating(),
        r.finalRating(),
        r.returnCount(),
        r.policyPackVersionId(),
        r.rowVersion(),
        ratings);
  }

  private static PolicyQueryApi policy(Optional<Object> value) {
    return new PolicyQueryApi() {
      @Override
      public Optional<PolicyQueryApi.PolicyFormulaView> findActivePolicy(
          TenantId t, PeriodId p, String code) {
        return Optional.empty();
      }

      @Override
      public Optional<Object> parameter(TenantId t, UUID versionId, String key) {
        return value;
      }
    };
  }

  /** Kho giả: một hồ sơ, ghi nhận các lệnh ghi. */
  private static final class FakeRepository implements AppraisalProfileRepository {
    ProfileRow row;
    boolean saveFails;
    final List<UUID> superseded = new ArrayList<>();
    final List<RatingRecorded> inserted = new ArrayList<>();
    final List<UUID> explanations = new ArrayList<>();

    @Override
    public Optional<ProfileRow> lock(TenantId tenant, UUID profileId) {
      return row != null && row.id().equals(profileId) ? Optional.of(row) : Optional.empty();
    }

    @Override
    public Optional<Long> save(
        TenantId tenant, ProfileRow r, AppraisalProfile p, UUID actorId, Instant at) {
      return saveFails ? Optional.empty() : Optional.of(r.rowVersion() + 1);
    }

    @Override
    public void supersedeRating(
        TenantId tenant, int periodBucket, UUID proposalId, UUID actorId, Instant at) {
      superseded.add(proposalId);
    }

    @Override
    public void insertRating(
        TenantId tenant,
        ProfileRow r,
        UUID snapshotId,
        RatingRecorded recorded,
        SealedText reason,
        SealedText comment,
        Instant at) {
      inserted.add(recorded);
    }

    @Override
    public void insertExplanation(
        TenantId tenant,
        ProfileRow r,
        UUID explanationId,
        UUID proposalId,
        String explanationType,
        String autoText,
        String legalRef,
        SealedText explanation,
        UUID actorId,
        Instant at) {
      explanations.add(proposalId);
    }
  }
}
