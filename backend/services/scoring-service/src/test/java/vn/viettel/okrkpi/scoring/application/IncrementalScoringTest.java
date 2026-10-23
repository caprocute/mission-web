package vn.viettel.okrkpi.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.port.OpenPeriods;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.JobScope;
import vn.viettel.okrkpi.scoring.domain.OpenPeriod;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;

/** Xếp tác vụ tính dần theo EVT-06, EVT-08, EVT-09, EVT-22 (DV-07-04). */
class IncrementalScoringTest {

  static final UUID TENANT = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID TASK = UUID.randomUUID();
  static final UUID VERSION = UUID.randomUUID();

  final StoredScores stored = mock(StoredScores.class);
  final OpenPeriods periods = mock(OpenPeriods.class);
  final ScoreJobStore jobs = mock(ScoreJobStore.class);
  final PolicyPackCache policies = mock(PolicyPackCache.class);
  final IncrementalScoring scoring = new IncrementalScoring(stored, periods, jobs, policies);

  static PinnedPolicy pin() {
    return new PinnedPolicy(
        UUID.randomUUID(), "KHOI_DANG", VERSION, "2.1.0", "a".repeat(64), false);
  }

  ScoreJob opened() {
    ArgumentCaptor<ScoreJob> job = ArgumentCaptor.forClass(ScoreJob.class);
    verify(jobs).open(job.capture());
    return job.getValue();
  }

  @Test
  void suKienNhiemVuMoTacVuTamTinhChoHoSoBiAnhHuong() {
    UUID p1 = UUID.randomUUID();
    when(stored.withTask(TASK))
        .thenReturn(List.of(new StoredScores.Affected(PERIOD, 20271, p1, "NHA_NUOC")));
    when(jobs.pendingIncremental(TENANT, 20271, PERIOD, IncrementalScoring.REASON_EVENT))
        .thenReturn(Optional.empty());
    when(policies.pin(PolicyBindingKey.of(TENANT, PERIOD, "NHA_NUOC"))).thenReturn(pin());
    UUID id = UUID.randomUUID();
    when(jobs.open(any())).thenReturn(id);

    assertThat(scoring.onTaskChanged(TENANT, TASK)).containsExactly(id);
    ScoreJob job = opened();
    assertThat(job.kind()).isEqualTo(ScoreJobKind.TAM_TINH);
    assertThat(job.triggerEventId()).isNull();
    assertThat(job.policyPackVersionId()).isEqualTo(VERSION);
    assertThat(JobScope.parse(job.scope().json()).profiles()).containsExactly(p1);
  }

  @Test
  void suKienDonDapGopVaoTacVuDangCho() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    UUID pending = UUID.randomUUID();
    when(stored.withTask(TASK))
        .thenReturn(List.of(new StoredScores.Affected(PERIOD, 20271, p2, "DANG")));
    CanonicalDoc old =
        JobScope.of(IncrementalScoring.REASON_EVENT).withProfiles(Set.of(p1)).toDoc();
    when(jobs.pendingIncremental(TENANT, 20271, PERIOD, IncrementalScoring.REASON_EVENT))
        .thenReturn(Optional.of(new ScoreJobStore.Pending(pending, old.json())));

    assertThat(scoring.onTaskChanged(TENANT, TASK)).containsExactly(pending);
    ArgumentCaptor<CanonicalDoc> scope = ArgumentCaptor.forClass(CanonicalDoc.class);
    verify(jobs)
        .updateScope(
            eq(TENANT),
            eq(20271),
            eq(pending),
            scope.capture(),
            eq(IncrementalScoring.SYSTEM_ACTOR));
    assertThat(JobScope.parse(scope.getValue().json()).profiles())
        .containsExactlyInAnyOrder(p1, p2);
    verify(jobs, never()).open(any());

    // Cùng hồ sơ lần nữa: phạm vi không đổi thì không ghi.
    when(jobs.pendingIncremental(TENANT, 20271, PERIOD, IncrementalScoring.REASON_EVENT))
        .thenReturn(Optional.of(new ScoreJobStore.Pending(pending, scope.getValue().json())));
    scoring.onTaskChanged(TENANT, TASK);
    verify(jobs).updateScope(any(), eq(20271), any(), any(), any());
  }

  @Test
  void nhiemVuChuaCoKetQuaThiTinhCaKyDangMo() {
    UUID pending = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    when(stored.withTask(TASK)).thenReturn(List.of());
    when(periods.of(TENANT))
        .thenReturn(
            List.of(
                new OpenPeriod(PERIOD, 20271, "DANG"),
                new OpenPeriod(PERIOD, 20271, "NHA_NUOC"),
                new OpenPeriod(other, 20272, "DANG")));
    CanonicalDoc old =
        JobScope.of(IncrementalScoring.REASON_EVENT)
            .withProfiles(Set.of(UUID.randomUUID()))
            .toDoc();
    when(jobs.pendingIncremental(TENANT, 20271, PERIOD, IncrementalScoring.REASON_EVENT))
        .thenReturn(Optional.of(new ScoreJobStore.Pending(pending, old.json())));
    when(jobs.pendingIncremental(TENANT, 20272, other, IncrementalScoring.REASON_EVENT))
        .thenReturn(Optional.empty());
    // Kỳ thứ hai không có liên kết gói nào: bỏ qua.
    when(policies.pin(any())).thenThrow(new IllegalStateException("chưa liên kết"));

    assertThat(scoring.onTaskChanged(TENANT, TASK)).containsExactly(pending);
    ArgumentCaptor<CanonicalDoc> scope = ArgumentCaptor.forClass(CanonicalDoc.class);
    verify(jobs).updateScope(eq(TENANT), eq(20271), eq(pending), scope.capture(), any());
    assertThat(JobScope.parse(scope.getValue().json()).profiles()).isEmpty();
    verify(jobs, never()).open(any());
  }

  @Test
  void khoaKyMoTacVuChotKy() {
    UUID event = UUID.randomUUID();
    scoring.onPeriodLocked(TENANT, event, PERIOD, 20271, List.of(VERSION));
    ScoreJob job = opened();
    assertThat(job.kind()).isEqualTo(ScoreJobKind.CHOT_KY);
    assertThat(job.triggerEventId()).isEqualTo(event);
    assertThat(job.policyPackVersionId()).isEqualTo(VERSION);

    UUID pinned = UUID.randomUUID();
    when(policies.pin(PolicyBindingKey.of(TENANT, PERIOD, "DANG")))
        .thenReturn(
            new PinnedPolicy(UUID.randomUUID(), "KHOI_DANG", pinned, "2", "b".repeat(64), true));
    scoring.onPeriodLocked(TENANT, UUID.randomUUID(), PERIOD, 20271, List.of());
    ArgumentCaptor<ScoreJob> all = ArgumentCaptor.forClass(ScoreJob.class);
    verify(jobs, org.mockito.Mockito.times(2)).open(all.capture());
    assertThat(all.getAllValues().get(1).policyPackVersionId()).isEqualTo(pinned);
  }

  StoredScores.Stored original(String flow) {
    return new StoredScores.Stored(
        UUID.randomUUID(),
        TENANT,
        PERIOD,
        UUID.randomUUID(),
        UUID.randomUUID(),
        flow,
        VERSION,
        "hash-" + flow,
        "2.1.0",
        "{}",
        20271,
        UUID.randomUUID());
  }

  @Test
  void moLaiHauKiemDungPhienBanGocVaPhamVi() {
    UUID event = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    when(stored.bucketOf(PERIOD)).thenReturn(Optional.of(20271));
    when(stored.originals(PERIOD, 20271)).thenReturn(List.of(original("DANG")));
    when(jobs.open(any())).thenReturn(UUID.randomUUID());

    assertThat(scoring.onPeriodReopened(TENANT, event, PERIOD, "DON_VI", List.of(unit)))
        .isPresent();
    ArgumentCaptor<ScoreJob> captured = ArgumentCaptor.forClass(ScoreJob.class);
    verify(jobs).open(captured.capture());
    ScoreJob job = captured.getValue();
    assertThat(job.kind()).isEqualTo(ScoreJobKind.TINH_LAI_HAU_KIEM);
    JobScope scope = JobScope.parse(job.scope().json());
    assertThat(scope.units()).containsExactly(unit);
    assertThat(scope.originalVersion("DANG")).isEqualTo(VERSION);
    assertThat(scope.originalHash("DANG")).isEqualTo("hash-DANG");

    assertThat(scoring.onPeriodReopened(TENANT, event, PERIOD, "HO_SO", List.of())).isEmpty();
    assertThat(scoring.onPeriodReopened(TENANT, event, PERIOD, "TOAN_KY", List.of())).isPresent();
    assertThat(scoring.onPeriodReopened(TENANT, event, PERIOD, "HO_SO", List.of(UUID.randomUUID())))
        .isPresent();
    assertThatThrownBy(() -> scoring.onPeriodReopened(TENANT, event, PERIOD, "KHAC", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void moLaiKyChuaCoKetQuaThiKhongXepTacVu() {
    UUID other = UUID.randomUUID();
    when(stored.bucketOf(PERIOD)).thenReturn(Optional.empty());
    when(stored.bucketOf(other)).thenReturn(Optional.of(20272));
    when(stored.originals(other, 20272)).thenReturn(List.of());
    assertThat(scoring.onPeriodReopened(TENANT, UUID.randomUUID(), PERIOD, "TOAN_KY", List.of()))
        .isEmpty();
    assertThat(scoring.onPeriodReopened(TENANT, UUID.randomUUID(), other, "TOAN_KY", List.of()))
        .isEmpty();
    verify(jobs, never()).open(any());
  }
}
