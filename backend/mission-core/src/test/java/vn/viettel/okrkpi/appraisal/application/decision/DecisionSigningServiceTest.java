package vn.viettel.okrkpi.appraisal.application.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRating;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRow;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.LockCandidate;
import vn.viettel.okrkpi.appraisal.application.decision.port.DecisionNotices;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService.Transitioned;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nhận EVT-18 của lô và khoá hồ sơ hết hạn khiếu nại với cổng giả (CORE-04-08). */
class DecisionSigningServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2027-01-12T03:00:00Z");
  private static final int BUCKET = 20264;

  private final UUID decider = UUID.randomUUID();
  private final UUID sign = UUID.randomUUID();
  private final UUID p1 = UUID.randomUUID();
  private final UUID p2 = UUID.randomUUID();
  private final UUID pack = UUID.randomUUID();
  private final AppraisalProfileRepository profiles = mock(AppraisalProfileRepository.class);
  private final BatchDecisionRepository batches = mock(BatchDecisionRepository.class);
  private final AppraisalProfileService steps = mock(AppraisalProfileService.class);
  private final DecisionNotices notices = mock(DecisionNotices.class);
  private final PolicyQueryApi policies = mock(PolicyQueryApi.class);
  private final WorkingCalendarApi calendar = mock(WorkingCalendarApi.class);
  private DecisionSigningService service;
  private BatchRow batch;
  private BatchRating r1;
  private BatchRating r2;

  @BeforeEach
  void setUp() {
    service =
        new DecisionSigningService(
            profiles,
            batches,
            steps,
            notices,
            policies,
            calendar,
            Clock.fixed(NOW, ZoneOffset.UTC));
    batch = batch(BatchDecisionService.CHO_KY, sign);
    r1 = new BatchRating(UUID.randomUUID(), p1, "HTTNV", null);
    r2 = new BatchRating(UUID.randomUUID(), p2, "HTNV", null);
    when(batches.find(eq(TENANT), eq(batch.id()), any(Boolean.class)))
        .thenReturn(Optional.of(batch));
    when(batches.ratings(TENANT, BUCKET, batch.id())).thenReturn(List.of(r1, r2));
    when(profiles.lock(TENANT, p1))
        .thenReturn(Optional.of(row(p1, AppraisalStatus.CHO_QUYET_DINH)));
    when(profiles.lock(TENANT, p2))
        .thenReturn(Optional.of(row(p2, AppraisalStatus.CHO_QUYET_DINH)));
    when(steps.transition(any(), any(), any(), any(), any()))
        .thenReturn(new Transitioned(null, 7L));
    when(calendar.addWorkingDays(eq(TENANT), any(), anyInt()))
        .thenAnswer(a -> ((LocalDate) a.getArgument(1)).plusDays(14));
  }

  @Test
  void kyDuLoThiChuyenDaKySoVaDongLo() {
    int n = service.onSigned(TENANT, batch.id(), sign, Set.of(hash(r1), hash(r2)), NOW);
    assertThat(n).isEqualTo(2);
    verify(batches, times(2)).markSigned(eq(TENANT), eq(BUCKET), any(), eq(sign), eq(NOW), any());
    verify(steps, times(4)).transition(eq(TENANT), any(), eq(null), eq(decider), any());
    verify(notices)
        .decided(
            eq(TENANT),
            any(),
            eq(RatingLevel.HTTNV),
            eq(decider),
            eq(sign),
            eq(LocalDate.parse("2027-01-26")),
            eq(7L));
    verify(batches)
        .setStatus(eq(TENANT), eq(BUCKET), eq(batch.id()), eq("DA_KY"), eq(decider), any());
    verify(notices)
        .batchCompleted(eq(TENANT), eq(batch.id()), eq(decider), eq("215-QĐ"), eq(2), eq(2L));
    verify(batches, never()).attachSignRequest(any(), anyInt(), any(), any(), any(), any());
  }

  @Test
  void chuKyKhongKhopHoacHoSoDaRoiBuocThiLoiMotPhan() {
    when(profiles.lock(TENANT, p2)).thenReturn(Optional.of(row(p2, AppraisalStatus.DA_THAM_DINH)));
    BatchRow open = batch(BatchDecisionService.NHAP, null);
    when(batches.find(eq(TENANT), eq(open.id()), any(Boolean.class))).thenReturn(Optional.of(open));
    when(batches.ratings(TENANT, BUCKET, open.id()))
        .thenReturn(List.of(r1, r2, new BatchRating(UUID.randomUUID(), p1, "HTNV", NOW)));
    BatchRow sameIds = open;
    int n =
        service.onSigned(
            TENANT, sameIds.id(), sign, Set.of(hash(sameIds, r1), hash(sameIds, r2)), NOW);
    assertThat(n).isEqualTo(1);
    verify(batches)
        .attachSignRequest(eq(TENANT), eq(BUCKET), eq(open.id()), eq(sign), eq(decider), any());
    verify(batches)
        .setStatus(eq(TENANT), eq(BUCKET), eq(open.id()), eq("LOI_MOT_PHAN"), eq(decider), any());
    verify(notices, never()).batchCompleted(any(), any(), any(), any(), anyInt(), any(Long.class));
  }

  @Test
  void suKienTungPhieuDenRoiRacThiLoTiepTucNhanToiKhiDuPhieu() {
    // DV-10-06: SVC-10 phát EVT-18 cho từng phiếu của lô, không theo thứ tự.
    assertThat(service.onSigned(TENANT, batch.id(), sign, Set.of(hash(r2)), NOW)).isEqualTo(1);
    verify(batches)
        .setStatus(eq(TENANT), eq(BUCKET), eq(batch.id()), eq("LOI_MOT_PHAN"), eq(decider), any());
    verify(notices, never()).batchCompleted(any(), any(), any(), any(), anyInt(), any(Long.class));

    BatchRow partial =
        new BatchRow(
            batch.id(),
            batch.periodId(),
            batch.periodBucket(),
            batch.flow(),
            batch.decisionNo(),
            batch.decisionDate(),
            batch.deciderId(),
            batch.deciderName(),
            batch.profileCount(),
            batch.profilesHash(),
            sign,
            BatchDecisionService.LOI_MOT_PHAN,
            3,
            NOW);
    when(batches.find(eq(TENANT), eq(batch.id()), any(Boolean.class)))
        .thenReturn(Optional.of(partial));
    when(batches.ratings(TENANT, BUCKET, batch.id()))
        .thenReturn(List.of(r1, new BatchRating(r2.proposalId(), p2, r2.ratingCode(), NOW)));

    // Sự kiện của yêu cầu ký khác khi lô đang LOI_MOT_PHAN bị bỏ qua.
    assertThat(service.onSigned(TENANT, batch.id(), UUID.randomUUID(), Set.of(hash(r1)), NOW))
        .isZero();
    assertThat(service.onSigned(TENANT, batch.id(), sign, Set.of(hash(r1)), NOW)).isEqualTo(1);
    verify(batches, times(2)).markSigned(eq(TENANT), eq(BUCKET), any(), eq(sign), eq(NOW), any());
    verify(batches)
        .setStatus(eq(TENANT), eq(BUCKET), eq(batch.id()), eq("DA_KY"), eq(decider), any());
    verify(notices)
        .batchCompleted(eq(TENANT), eq(batch.id()), eq(decider), eq("215-QĐ"), eq(2), eq(3L));
  }

  @Test
  void suKienLapHoacKhongThuocLoBiBoQua() {
    assertThat(service.onSigned(TENANT, UUID.randomUUID(), sign, Set.of(), NOW)).isZero();
    assertThat(service.onSigned(TENANT, batch.id(), UUID.randomUUID(), Set.of(), NOW)).isZero();
    BatchRow done = batch(BatchDecisionService.DA_KY, sign);
    when(batches.find(eq(TENANT), eq(done.id()), any(Boolean.class))).thenReturn(Optional.of(done));
    assertThat(service.onSigned(TENANT, done.id(), sign, Set.of(), NOW)).isZero();
    verify(steps, never()).transition(any(), any(), any(), any(), any());
  }

  @Test
  void khoaKhiQuaHanKhieuNai() {
    when(policies.parameter(TENANT, pack, "khieu_nai.thoi_han")).thenReturn(Optional.of(5));
    when(calendar.addWorkingDays(eq(TENANT), any(), eq(5)))
        .thenAnswer(a -> ((LocalDate) a.getArgument(1)).plusDays(7));
    LockCandidate c = new LockCandidate(p1, NOW, pack);
    Map<UUID, Integer> cache = new HashMap<>();
    when(profiles.lock(TENANT, p1)).thenReturn(Optional.of(row(p1, AppraisalStatus.DA_KY_SO)));
    assertThat(service.lockIfDue(TENANT, c, LocalDate.parse("2027-01-19"), cache)).isFalse();
    assertThat(service.lockIfDue(TENANT, c, LocalDate.parse("2027-01-20"), cache)).isTrue();
    assertThat(cache).containsEntry(pack, 5);
    when(profiles.lock(TENANT, p1)).thenReturn(Optional.of(row(p1, AppraisalStatus.KHIEU_NAI)));
    assertThat(service.lockIfDue(TENANT, c, LocalDate.parse("2027-02-20"), cache)).isFalse();
    verify(steps, times(1))
        .transition(eq(TENANT), eq(p1), eq(null), eq(DecisionSigningService.SYSTEM_ACTOR), any());
    when(batches.signedProfiles(TENANT, NOW, null, 10)).thenReturn(List.of(c));
    assertThat(service.candidates(TENANT, null, 10)).containsExactly(c);
  }

  @Test
  void hanMacDinhKhiKhongCoGoiHoacLich() {
    DecisionSigningService bare =
        new DecisionSigningService(
            profiles, batches, steps, notices, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(bare.deadline(TENANT, pack, NOW, new HashMap<>()))
        .isEqualTo(LocalDate.parse("2027-01-22"));
    assertThat(bare.deadline(TENANT, null, NOW, new HashMap<>()))
        .isEqualTo(LocalDate.parse("2027-01-22"));
    when(policies.parameter(TENANT, pack, "khieu_nai.thoi_han")).thenReturn(Optional.of(0));
    assertThat(service.deadline(TENANT, pack, NOW, new HashMap<>()))
        .isEqualTo(LocalDate.parse("2027-01-26"));
  }

  // ---------------------------------------------------------------- hỗ trợ

  private String hash(BatchRating r) {
    return hash(batch, r);
  }

  private static String hash(BatchRow b, BatchRating r) {
    return HexFormat.of().formatHex(BatchDecisionService.contentHash(b, r));
  }

  private BatchRow batch(String status, UUID signRequest) {
    return new BatchRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        BUCKET,
        "DANG",
        "215-QĐ",
        LocalDate.parse("2027-01-12"),
        decider,
        "Người quyết định",
        2,
        new byte[32],
        signRequest,
        status,
        2,
        NOW);
  }

  private ProfileRow row(UUID id, AppraisalStatus status) {
    return new ProfileRow(
        id,
        UUID.randomUUID(),
        BUCKET,
        UUID.randomUUID(),
        "DANG",
        status,
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        RatingLevel.HTTNV,
        null,
        0,
        pack,
        3,
        List.of());
  }
}
