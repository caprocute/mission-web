package vn.viettel.okrkpi.appraisal.application.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.SpecialCase;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.Treatment;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.QuarterRef;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRecomputeRequests;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService.Outcome;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService.ScoreComputed;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository.NewSnapshot;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository.PeriodLock;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository.SnapshotHead;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository.UnitGroup;
import vn.viettel.okrkpi.appraisal.application.setup.port.SetupNotices;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.PolicyContent;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class ProfileSetupServiceTest {

  private static final Instant LOCKED = Instant.parse("2026-09-30T18:30:00Z");
  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID unitA = UUID.randomUUID();
  private final UUID unitB = UUID.randomUUID();
  private final UUID pack = UUID.randomUUID();
  private final AppraisalProfileService profiles = mock(AppraisalProfileService.class);
  private final ProfileSetupRepository repo = mock(ProfileSetupRepository.class);
  private final SetupNotices notices = mock(SetupNotices.class);
  private final PolicyQueryApi policies = mock(PolicyQueryApi.class);
  private final AppraisalSpecialCases special = mock(AppraisalSpecialCases.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final ProfileSetupService service =
      new ProfileSetupService(
          profiles, repo, notices, policies, special, periods, Clock.fixed(LOCKED));

  @Test
  void khoaKyQuyLapHoSoTheoLuongCoGoiVaBoTruongHopDacThu() {
    period(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
    UUID excluded = UUID.randomUUID();
    UUID ratedOnly = UUID.randomUUID();
    when(repo.unitGroups(tenant, LocalDate.of(2026, 10, 1)))
        .thenReturn(List.of(new UnitGroup(unitA, null), new UnitGroup(unitB, null)));
    when(policies.resolve(any(), any(), eq("NHA_NUOC"), any(), eq(unitA)))
        .thenReturn(Optional.of(pack(pack)));
    when(policies.resolve(any(), any(), eq("NHA_NUOC"), any(), eq(unitB)))
        .thenReturn(Optional.empty());
    when(policies.resolve(any(), any(), eq("DANG"), any(), any())).thenReturn(Optional.empty());
    when(special.forPeriod(tenant, period))
        .thenReturn(
            List.of(
                new SpecialCase(excluded, null, Treatment.KHONG_LAP_HO_SO),
                new SpecialCase(UUID.randomUUID(), "DANG", Treatment.KHONG_LAP_HO_SO),
                new SpecialCase(ratedOnly, null, Treatment.KHONG_XEP_LOAI)));
    List<Object[]> calls = new ArrayList<>();
    when(repo.createProfiles(any(), any(), anyString(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              calls.add(inv.getArguments());
              return 7;
            });
    assertThat(service.onPeriodLocked(tenant, period, 20263, LOCKED)).isEqualTo(7);
    assertThat(calls).hasSize(1);
    Object[] c = calls.get(0);
    assertThat(((PeriodLock) c[1]).validDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(c[2]).isEqualTo("NHA_NUOC");
    assertThat(c[3]).isEqualTo(Map.of(unitA, pack));
    assertThat(List.copyOf((Collection<?>) c[4])).isEqualTo(List.of(excluded));
    assertThat(c[5]).isEqualTo(ProfileSetupService.SYSTEM_ACTOR);
  }

  @Test
  void kyKhongPhaiQuyHoacKhongCoGoiThiKhongLap() {
    period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    assertThat(service.onPeriodLocked(tenant, period, 20260, LOCKED)).isZero();
    when(periods.findById(any(), any())).thenReturn(Optional.empty());
    assertThat(service.quarter(tenant, period, 20260)).isFalse();
    assertThat(service.quarter(tenant, period, 20264)).isTrue();
    ProfileSetupService bare =
        new ProfileSetupService(profiles, repo, notices, null, null, null, Clock.fixed(LOCKED));
    when(repo.unitGroups(any(), any())).thenReturn(List.of(new UnitGroup(unitA, null)));
    assertThat(bare.onPeriodLocked(tenant, period, 20261, LOCKED)).isZero();
    verify(repo, never()).createProfiles(any(), any(), any(), any(), any(), any());
  }

  @Test
  void ketQuaChinhThucMoPhieuTuDanhGia() {
    ProfileRow row = row(AppraisalStatus.CHUA_LAP);
    when(profiles.find(tenant, row.id())).thenReturn(Optional.of(row));
    when(policies.content(tenant, pack))
        .thenReturn(Optional.of(new PolicyContent(pack, "2026.3", "ab", List.of(), List.of())));
    when(profiles.transition(any(), any(), any(), any(), any()))
        .thenReturn(new AppraisalProfileService.Transitioned(null, 1));
    assertThat(service.onScoreComputed(tenant, score(row, "CHOT_KY", LOCKED)))
        .isEqualTo(Outcome.OPENED);
    var captor = org.mockito.ArgumentCaptor.forClass(NewSnapshot.class);
    verify(repo).insertSnapshot(eq(tenant), captor.capture(), eq(null), any());
    assertThat(captor.getValue().policyVersion()).isEqualTo("2026.3");
    assertThat(captor.getValue().snapshotHash()).hasSize(32);
    verify(notices).selfAssessmentOpened(tenant, row.id(), row.personId(), 1);
  }

  @Test
  void tamTinhChiLuuAnhChupVaThayTheHaiBuoc() {
    ProfileRow row = row(AppraisalStatus.CHUA_LAP);
    when(profiles.find(tenant, row.id())).thenReturn(Optional.of(row));
    UUID old = UUID.randomUUID();
    when(repo.currentSnapshot(tenant, 20263, row.id()))
        .thenReturn(Optional.of(new SnapshotHead(old, UUID.randomUUID(), LOCKED.minusSeconds(60))));
    assertThat(service.onScoreComputed(tenant, score(row, "TAM_TINH", LOCKED)))
        .isEqualTo(Outcome.STORED);
    verify(repo).insertSnapshot(eq(tenant), any(), eq(old), any());
    verify(repo).pointCurrent(eq(tenant), eq(20263), eq(row.id()), any(), any(), any());
    verify(profiles, never()).transition(any(), any(), any(), any(), any());

    ProfileRow open = row(AppraisalStatus.TU_DANH_GIA);
    when(profiles.find(tenant, open.id())).thenReturn(Optional.of(open));
    assertThat(service.onScoreComputed(tenant, score(open, "CHOT_KY", null)))
        .isEqualTo(Outcome.REPLACED);
  }

  @Test
  void suKienCuTrungHoacKhongCoHoSoBiBoQua() {
    ProfileRow state = row(AppraisalStatus.CHUA_LAP);
    ProfileRow nn =
        new ProfileRow(
            state.id(),
            period,
            20263,
            state.personId(),
            "NHA_NUOC",
            AppraisalStatus.CHUA_LAP,
            null,
            null,
            null,
            null,
            null,
            0,
            pack,
            0,
            List.of());
    when(profiles.find(tenant, nn.id())).thenReturn(Optional.of(nn));
    assertThat(service.onScoreComputed(tenant, score(nn, "CHOT_KY", LOCKED)))
        .isEqualTo(Outcome.INCOMPLETE);
    assertThat(service.onScoreComputed(tenant, score(nn, "KIEM_TRA_TAI_LAP", LOCKED)))
        .isEqualTo(Outcome.IGNORED);

    UUID missing = UUID.randomUUID();
    when(profiles.find(tenant, missing)).thenReturn(Optional.empty());
    assertThat(
            service.onScoreComputed(
                tenant,
                new ScoreComputed(
                    UUID.randomUUID(),
                    missing,
                    "TAM_TINH",
                    BigDecimal.TEN,
                    "HTNV",
                    null,
                    null,
                    LOCKED)))
        .isEqualTo(Outcome.NO_PROFILE);

    ProfileRow locked = row(AppraisalStatus.DA_KHOA);
    when(profiles.find(tenant, locked.id())).thenReturn(Optional.of(locked));
    assertThat(service.onScoreComputed(tenant, score(locked, "CHOT_KY", LOCKED)))
        .isEqualTo(Outcome.LOCKED);

    ProfileRow row = row(AppraisalStatus.TU_DANH_GIA);
    when(profiles.find(tenant, row.id())).thenReturn(Optional.of(row));
    ScoreComputed dup = score(row, "CHOT_KY", LOCKED);
    when(repo.hasResult(tenant, 20263, dup.scoreResultId())).thenReturn(true);
    assertThat(service.onScoreComputed(tenant, dup)).isEqualTo(Outcome.DUPLICATE);

    when(repo.currentSnapshot(tenant, 20263, row.id()))
        .thenReturn(Optional.of(new SnapshotHead(UUID.randomUUID(), UUID.randomUUID(), LOCKED)));
    assertThat(service.onScoreComputed(tenant, score(row, "CHOT_KY", LOCKED.minusSeconds(1))))
        .isEqualTo(Outcome.STALE);
    verify(repo, never()).insertSnapshot(any(), any(), any(), any());
  }

  private void period(LocalDate start, LocalDate end) {
    when(periods.findById(tenant, PeriodId.of(period)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(period), tenant, "Q", "Quý", start, end, PeriodStatus.DA_KHOA)));
  }

  private static ResolvedPack pack(UUID version) {
    return new ResolvedPack(
        UUID.randomUUID(), "GOI", version, "2026.3", "ab", null, UUID.randomUUID(), true);
  }

  /** CORE-04-10: ảnh chụp năm ghi các quý, ghi điểm năm; mức năm đổi thì về bước quyết định. */
  @Test
  void anhChupNamVaTinhLaiSauHauKiem() {
    AnnualRatingRepository annual = mock(AnnualRatingRepository.class);
    AnnualRecomputeRequests recompute = mock(AnnualRecomputeRequests.class);
    ProfileSetupService yearly =
        new ProfileSetupService(
            profiles, repo, notices, null, null, null, Clock.fixed(LOCKED), annual, recompute);
    UUID decider = UUID.randomUUID();
    ProfileRow open = annualRow(AppraisalStatus.CHUA_LAP, "NHA_NUOC", null, null);
    when(profiles.find(tenant, open.id())).thenReturn(Optional.of(open));
    when(annual.quarters(tenant, 2026, open.personId(), "NHA_NUOC"))
        .thenReturn(
            List.of(
                new QuarterRef(
                    1,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "DA_KY_SO",
                    "HTTNV",
                    UUID.randomUUID(),
                    new byte[] {1},
                    BigDecimal.TEN,
                    "1.0",
                    UUID.randomUUID()),
                new QuarterRef(
                    4,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "CHUA_LAP",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
    when(profiles.transition(any(), any(), any(), any(), any()))
        .thenReturn(new AppraisalProfileService.Transitioned(null, 2));
    assertThat(yearly.onScoreComputed(tenant, score(open, "CHOT_KY", LOCKED)))
        .isEqualTo(Outcome.OPENED);
    var captor = org.mockito.ArgumentCaptor.forClass(NewSnapshot.class);
    verify(repo).insertSnapshot(eq(tenant), captor.capture(), eq(null), any());
    assertThat(captor.getValue().detailJson()).contains("\"quy\":1").contains("\"quy\":4");
    verify(annual)
        .recordScore(eq(tenant), eq(2026), eq(open.id()), any(), eq("HTTNV"), any(), any());

    ProfileRow signed =
        annualRow(
            AppraisalStatus.DA_KY_SO,
            "DANG",
            vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel.HTXSNV,
            decider);
    when(profiles.find(tenant, signed.id())).thenReturn(Optional.of(signed));
    assertThat(yearly.onScoreComputed(tenant, score(signed, "TINH_LAI_HAU_KIEM", LOCKED)))
        .isEqualTo(Outcome.REOPENED);
    verify(notices)
        .annualRatingChanged(
            eq(tenant),
            eq(signed.id()),
            eq(List.of(signed.personId(), decider)),
            eq(vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel.HTXSNV),
            eq(vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel.HTTNV),
            eq(2L));
    ProfileRow same =
        annualRow(
            AppraisalStatus.DA_KY_SO,
            "DANG",
            vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel.HTTNV,
            null);
    when(profiles.find(tenant, same.id())).thenReturn(Optional.of(same));
    assertThat(yearly.onScoreComputed(tenant, score(same, "TINH_LAI_HAU_KIEM", LOCKED)))
        .isEqualTo(Outcome.REPLACED);

    ProfileRow quarter = row(AppraisalStatus.DA_KY_SO);
    UUID annualId = UUID.randomUUID();
    when(profiles.find(tenant, quarter.id())).thenReturn(Optional.of(quarter));
    when(annual.annualProfileOf(tenant, 2026, quarter.personId(), "DANG"))
        .thenReturn(Optional.of(annualId));
    assertThat(yearly.onScoreComputed(tenant, score(quarter, "TINH_LAI_HAU_KIEM", LOCKED)))
        .isEqualTo(Outcome.REPLACED);
    verify(recompute).request(tenant, annualId, quarter.id());
  }

  private ProfileRow annualRow(
      AppraisalStatus status,
      String flow,
      vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel decided,
      UUID decider) {
    return new ProfileRow(
        UUID.randomUUID(),
        period,
        20260,
        UUID.randomUUID(),
        flow,
        status,
        null,
        decider,
        null,
        null,
        decided,
        0,
        pack,
        0,
        List.of());
  }

  private ProfileRow row(AppraisalStatus status) {
    return new ProfileRow(
        UUID.randomUUID(),
        period,
        20263,
        UUID.randomUUID(),
        "DANG",
        status,
        null,
        null,
        null,
        null,
        null,
        0,
        pack,
        0,
        List.of());
  }

  private static ScoreComputed score(ProfileRow row, String kind, Instant at) {
    return new ScoreComputed(
        UUID.randomUUID(),
        row.id(),
        kind,
        new BigDecimal("82.500"),
        "HTTNV",
        new byte[32],
        new byte[32],
        at);
  }
}
