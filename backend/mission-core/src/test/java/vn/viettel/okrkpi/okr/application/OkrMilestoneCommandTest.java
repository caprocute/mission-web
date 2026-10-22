package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegatePort;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository.MilestoneDraft;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository.MilestoneRow;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị lệnh khai báo, ghi nhận mốc của KR kiểu mốc (CORE-01-14). */
class OkrMilestoneCommandTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID OWNER = UUID.randomUUID();
  static final UUID DELEGATE = UUID.randomUUID();
  static final UUID KR = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  static final Clock CLOCK = Clock.fixed(Instant.parse("2027-02-10T01:00:00Z"), VN);

  private final OkrCheckinRepository keyResults = mock(OkrCheckinRepository.class);
  private final OkrMilestoneRepository repo = mock(OkrMilestoneRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OkrCheckinDelegatePort delegates = mock(OkrCheckinDelegatePort.class);
  private final OkrCheckinService checkins = mock(OkrCheckinService.class);
  private final OkrMilestoneService service =
      new OkrMilestoneService(
          keyResults, repo, periods, () -> null, () -> delegates, checkins, CLOCK);

  static CheckinTarget kr(String metric, String objectiveStatus) {
    return new CheckinTarget(
        KR,
        PERIOD,
        20271,
        "KR 1.1",
        metric,
        "TANG",
        BigDecimal.ZERO,
        new BigDecimal("3"),
        null,
        BigDecimal.ZERO,
        null,
        "BINH_THUONG",
        1,
        UUID.randomUUID(),
        "O1",
        objectiveStatus,
        BigDecimal.ZERO,
        UUID.randomUUID(),
        OWNER,
        UUID.randomUUID());
  }

  static PeriodSnapshot period(PeriodStatus status) {
    return new PeriodSnapshot(
        PeriodId.of(PERIOD),
        TENANT,
        "Q1",
        "Quý I",
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2027, 3, 31),
        status);
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  static final UUID M1 = UUID.randomUUID();
  static final UUID M2 = UUID.randomUUID();

  static MilestoneDraft draft(UUID id, int seq, LocalDate due) {
    return new MilestoneDraft(id, seq, due, null);
  }

  @BeforeEach
  void setUp() {
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.MO)));
    when(repo.keyResultDueDate(TENANT, 20271, KR))
        .thenReturn(Optional.of(LocalDate.of(2027, 3, 31)));
    when(repo.touchKeyResult(TENANT, 20271, KR, 1, OWNER)).thenReturn(2L);
  }

  @Test
  void khaiBaoMocKhiMucTieuConNhap() {
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("MOC", "NHAP")));
    when(repo.milestones(TENANT, KR))
        .thenReturn(
            List.of(new MilestoneRow(M1, 1, LocalDate.of(2027, 2, 5), null, null, null, 0)));
    List<MilestoneDraft> drafts =
        List.of(
            new MilestoneDraft(null, 2, LocalDate.of(2027, 3, 20), "Ban hành"),
            new MilestoneDraft(M1, 1, LocalDate.of(2027, 2, 5), "Có biên bản"));
    var result = service.replaceMilestones(TENANT, OWNER, KR, 1, drafts);
    assertThat(result.keyResultVersion()).isEqualTo(2);
    assertThat(result.items())
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.lateWorkingDays()).isEqualTo(3);
              assertThat(v.achievedOn()).isNull();
            });
    verify(repo).replaceMilestones(TENANT, KR, List.of(drafts.get(1), drafts.get(0)), OWNER);
    assertThat(service.list(TENANT, KR).keyResultVersion()).isEqualTo(1);

    assertThatThrownBy(() -> service.replaceMilestones(TENANT, DELEGATE, KR, 1, drafts))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, 7, drafts))
        .isInstanceOf(VersionConflictException.class);
    for (List<MilestoneDraft> bad :
        List.of(
            List.<MilestoneDraft>of(),
            List.of(
                draft(null, 1, LocalDate.of(2027, 2, 5)), draft(null, 1, LocalDate.of(2027, 2, 6))),
            List.of(draft(null, 0, LocalDate.of(2027, 2, 5))),
            List.of(draft(null, 1, null)),
            List.of(new MilestoneDraft(null, 1, LocalDate.of(2027, 2, 5), "c".repeat(1001))),
            List.of(draft(M2, 1, LocalDate.of(2027, 2, 5))),
            List.of(
                draft(null, 1, LocalDate.of(2027, 3, 5)), draft(null, 2, LocalDate.of(2027, 2, 5))),
            List.of(draft(null, 1, LocalDate.of(2027, 4, 5))))) {
      assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, 1, bad))
          .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    }
    when(repo.touchKeyResult(TENANT, 20271, KR, 1, OWNER)).thenReturn(0L);
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, 1, drafts))
        .isInstanceOf(VersionConflictException.class);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, 1, drafts))
        .isInstanceOf(PeriodLockedException.class);
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("MOC", "CHO_DUYET")));
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, 1, drafts))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-INVALID-STATE"));
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("SO_LUONG", "NHAP")));
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, 1, drafts))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-NOT-MILESTONE-KR"));
    assertThat(service.list(TENANT, KR).items()).isEmpty();
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, 1, drafts))
        .isInstanceOf(OkrCheckinService.NotFound.class);
  }

  @Test
  void ghiNhanDatMocChuSoHuuHoacNguoiThay() {
    CheckinTarget approved = kr("MOC", "DA_DUYET");
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(approved));
    MilestoneRow done =
        new MilestoneRow(
            M1,
            1,
            LocalDate.of(2027, 2, 5),
            null,
            Instant.parse("2027-02-08T17:00:00Z"),
            "MC-1",
            1);
    when(repo.markAchieved(eq(TENANT), eq(KR), eq(M1), any(), any(), any()))
        .thenReturn(Optional.of(done));
    when(repo.milestones(TENANT, KR))
        .thenReturn(
            List.of(done, new MilestoneRow(M2, 2, LocalDate.of(2027, 3, 5), null, null, null, 0)));
    CheckinTarget after = kr("MOC", "DA_DUYET");
    when(checkins.record(eq(TENANT), any(), eq(KR), any(), any()))
        .thenReturn(new OkrCheckinService.CheckinOutcome(null, withVersion(after, 3), false));
    LocalDate feb9 = LocalDate.of(2027, 2, 9);
    var achieved = service.recordAchievement(TENANT, OWNER, KR, 1, M1, feb9, " MC-1 ");
    assertThat(achieved.keyResultVersion()).isEqualTo(3);
    assertThat(achieved.milestone().achievedOn()).isEqualTo(feb9);
    assertThat(achieved.milestone().lateWorkingDays()).isEqualTo(2);
    verify(repo).markAchieved(TENANT, KR, M1, Instant.parse("2027-02-08T17:00:00Z"), "MC-1", OWNER);
    ArgumentCaptor<OkrCheckinService.CheckinCommand> cmd =
        ArgumentCaptor.forClass(OkrCheckinService.CheckinCommand.class);
    verify(checkins).record(eq(TENANT), eq(OWNER), eq(KR), any(), cmd.capture());
    assertThat(cmd.getValue().value()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(cmd.getValue().confidence()).isEqualTo("CAO");
    assertThat(cmd.getValue().status()).isEqualTo("DA_GUI");

    when(delegates.isDelegate(TENANT, KR, DELEGATE)).thenReturn(true);
    service.recordAchievement(TENANT, DELEGATE, KR, 1, M1, feb9, null);

    assertThatThrownBy(
            () -> service.recordAchievement(TENANT, UUID.randomUUID(), KR, 1, M1, feb9, null))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 5, M1, feb9, null))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () -> service.recordAchievement(TENANT, OWNER, KR, 1, M1, feb9, "e".repeat(201)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.recordAchievement(
                    TENANT, OWNER, KR, 1, M1, LocalDate.of(2027, 2, 11), null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, M1, null, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    when(repo.markAchieved(eq(TENANT), eq(KR), eq(M2), any(), any(), any()))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, M2, feb9, null))
        .isInstanceOf(OkrCheckinService.NotFound.class);
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.DA_KHOA)));
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, M1, feb9, null))
        .isInstanceOf(PeriodLockedException.class);
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, M1, feb9, null))
        .isInstanceOf(IllegalStateException.class);
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("MOC", "NHAP")));
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, M1, feb9, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-OBJECTIVE-NOT-APPROVED"));
    OkrMilestoneService noPort =
        new OkrMilestoneService(keyResults, repo, periods, () -> null, () -> null, checkins, CLOCK);
    assertThatThrownBy(() -> noPort.recordAchievement(TENANT, DELEGATE, KR, 1, M1, feb9, null))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
  }

  @Test
  void giaTriTuTuSoVaMauSo() {
    assertThat(OkrCheckinService.ratioValue(BigDecimal.TEN, null, BigDecimal.ONE))
        .isEqualTo(BigDecimal.TEN);
    assertThat(OkrCheckinService.ratioValue(null, BigDecimal.ONE, null)).isNull();
    assertThat(OkrCheckinService.ratioValue(null, new BigDecimal("1"), new BigDecimal("3")))
        .isEqualByComparingTo("33.3333");
    assertThatThrownBy(() -> OkrCheckinService.ratioValue(null, BigDecimal.ONE, BigDecimal.ZERO))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(
            () -> OkrCheckinService.ratioValue(null, new BigDecimal("-1"), BigDecimal.ONE))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
  }

  static CheckinTarget withVersion(CheckinTarget t, long version) {
    return new CheckinTarget(
        t.keyResultId(),
        t.periodId(),
        t.periodBucket(),
        t.code(),
        t.metricType(),
        t.direction(),
        t.baselineValue(),
        t.targetValue(),
        t.currentValue(),
        t.progress(),
        t.confidence(),
        t.riskFlag(),
        version,
        t.objectiveId(),
        t.objectiveCode(),
        t.objectiveStatus(),
        t.objectiveProgress(),
        t.ownerUnitId(),
        t.ownerPersonId(),
        t.objectiveCreatedBy());
  }
}
