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
  private final OkrMilestoneService service =
      new OkrMilestoneService(keyResults, repo, periods, () -> null, () -> delegates, CLOCK);

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

  @BeforeEach
  void setUp() {
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.MO)));
  }

  @Test
  void khaiBaoMocKhiMucTieuConNhap() {
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("MOC", "NHAP")));
    when(repo.milestones(TENANT, KR))
        .thenReturn(
            List.of(
                new MilestoneRow(
                    UUID.randomUUID(), 1, LocalDate.of(2027, 2, 5), null, null, null, 0)));
    List<MilestoneDraft> drafts =
        List.of(new MilestoneDraft(1, LocalDate.of(2027, 2, 5), "Có biên bản"));
    assertThat(service.replaceMilestones(TENANT, OWNER, KR, drafts))
        .singleElement()
        .satisfies(v -> assertThat(v.lateWorkingDays()).isEqualTo(3));
    verify(repo).replaceMilestones(TENANT, KR, drafts, OWNER);

    assertThatThrownBy(() -> service.replaceMilestones(TENANT, DELEGATE, KR, drafts))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
    assertThatThrownBy(
            () ->
                service.replaceMilestones(
                    TENANT,
                    OWNER,
                    KR,
                    List.of(
                        new MilestoneDraft(1, LocalDate.of(2027, 2, 5), null),
                        new MilestoneDraft(1, LocalDate.of(2027, 2, 6), null))))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.replaceMilestones(
                    TENANT,
                    OWNER,
                    KR,
                    List.of(new MilestoneDraft(0, LocalDate.of(2027, 2, 5), null))))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.replaceMilestones(
                    TENANT, OWNER, KR, List.of(new MilestoneDraft(1, null, null))))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.replaceMilestones(
                    TENANT,
                    OWNER,
                    KR,
                    List.of(new MilestoneDraft(1, LocalDate.of(2027, 2, 5), "c".repeat(1001)))))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, drafts))
        .isInstanceOf(PeriodLockedException.class);
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("MOC", "CHO_DUYET")));
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, drafts))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-INVALID-STATE"));
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("SO_LUONG", "NHAP")));
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, drafts))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.replaceMilestones(TENANT, OWNER, KR, drafts))
        .isInstanceOf(OkrCheckinService.NotFound.class);
  }

  @Test
  void ghiNhanDatMocChuSoHuuHoacNguoiThay() {
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("MOC", "DA_DUYET")));
    MilestoneRow done =
        new MilestoneRow(
            UUID.randomUUID(),
            1,
            LocalDate.of(2027, 2, 5),
            null,
            Instant.parse("2027-02-09T03:00:00Z"),
            "MC-1",
            1);
    when(repo.markAchieved(eq(TENANT), eq(KR), eq(1), any(), eq("MC-1"), any()))
        .thenReturn(Optional.of(done));
    assertThat(service.recordAchievement(TENANT, OWNER, KR, 1, null, "MC-1").lateWorkingDays())
        .isEqualTo(2);
    verify(repo).markAchieved(TENANT, KR, 1, CLOCK.instant(), "MC-1", OWNER);
    when(delegates.isDelegate(TENANT, KR, DELEGATE)).thenReturn(true);
    service.recordAchievement(
        TENANT, DELEGATE, KR, 1, Instant.parse("2027-02-09T03:00:00Z"), "MC-1");

    assertThatThrownBy(
            () -> service.recordAchievement(TENANT, UUID.randomUUID(), KR, 1, null, null))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, null, "e".repeat(501)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    when(repo.markAchieved(eq(TENANT), eq(KR), eq(9), any(), any(), any()))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 9, null, null))
        .isInstanceOf(OkrCheckinService.NotFound.class);
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.DA_KHOA)));
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, null, null))
        .isInstanceOf(PeriodLockedException.class);
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, null, null))
        .isInstanceOf(IllegalStateException.class);
    when(keyResults.target(TENANT, KR)).thenReturn(Optional.of(kr("MOC", "NHAP")));
    assertThatThrownBy(() -> service.recordAchievement(TENANT, OWNER, KR, 1, null, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-OBJECTIVE-NOT-APPROVED"));
    OkrMilestoneService noPort =
        new OkrMilestoneService(keyResults, repo, periods, () -> null, () -> null, CLOCK);
    assertThatThrownBy(() -> noPort.recordAchievement(TENANT, DELEGATE, KR, 1, null, null))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
  }
}
