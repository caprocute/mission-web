package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.CheckinCommand;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.CheckinOutcome;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegatePort;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinEvents;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinExtension;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinRow;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị check-in KR (CORE-01-08, CORE-01-09) với kho giả lập. */
class OkrCheckinServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID OWNER = UUID.randomUUID();
  static final UUID KR = UUID.randomUUID();
  static final UUID OBJ = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();

  private final OkrCheckinRepository repo = mock(OkrCheckinRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OkrCheckService checks = mock(OkrCheckService.class);
  private final OkrCheckinDelegatePort delegates = mock(OkrCheckinDelegatePort.class);
  private final OkrCheckinExtension hook = mock(OkrCheckinExtension.class);
  private final OkrCheckinEvents events = mock(OkrCheckinEvents.class);
  private final OkrCheckinService service =
      new OkrCheckinService(repo, periods, checks, () -> delegates, () -> List.of(hook), events);

  static CheckinTarget target(String objectiveStatus, String risk, String confidence) {
    return new CheckinTarget(
        KR,
        PERIOD,
        20271,
        "KR 1.1",
        "SO_LUONG",
        "TANG",
        BigDecimal.ZERO,
        BigDecimal.TEN,
        BigDecimal.ONE,
        new BigDecimal("0.1"),
        confidence,
        risk,
        3,
        OBJ,
        "O1",
        objectiveStatus,
        BigDecimal.ZERO,
        UNIT,
        null,
        OWNER);
  }

  static CheckinRow row(UUID id, String status, String value, String confidence) {
    return new CheckinRow(
        id,
        KR,
        PERIOD,
        Instant.now(),
        new BigDecimal(value),
        confidence,
        null,
        null,
        "WEB",
        status,
        null,
        null,
        null,
        null,
        null,
        OWNER,
        "k");
  }

  static CheckinCommand cmd(String status, String value, String confidence) {
    return new CheckinCommand(
        status,
        value == null ? null : new BigDecimal(value),
        confidence,
        null,
        null,
        null,
        " Nổi bật ",
        null,
        null,
        false);
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

  static List<String> fields(Throwable t) {
    return ((BusinessException) t).errors().stream().map(ProblemFieldError::field).toList();
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @BeforeEach
  void setUp() {
    when(repo.target(TENANT, KR)).thenReturn(Optional.of(target("DA_DUYET", "BINH_THUONG", "CAO")));
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.MO)));
  }

  @Test
  void luuNhapChiGhiDongNhap() {
    when(repo.byId(eq(TENANT), eq(20271), any()))
        .thenAnswer(inv -> Optional.of(row(inv.getArgument(2), "NHAP", "4", "CAO")));
    CheckinOutcome out = service.record(TENANT, OWNER, KR, "k1", cmd("NHAP", "4", "CAO"));
    assertThat(out.checkin().status()).isEqualTo("NHAP");
    assertThat(out.onBehalf()).isFalse();
    verify(repo, never())
        .writeKeyResultProgress(
            any(), anyInt(), any(), anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void guiTinhTienDoPhatSuKienVaGoiMoRong() {
    when(repo.lockTarget(TENANT, 20271, OBJ, KR))
        .thenReturn(Optional.of(target("DA_DUYET", "THEO_DOI", "THAP")));
    when(repo.lastSubmitted(TENANT, 20271, KR))
        .thenReturn(Optional.of(row(UUID.randomUUID(), "DA_GUI", "3", "THAP")));
    when(repo.writeKeyResultProgress(
            any(), anyInt(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    when(repo.objectiveWeights(TENANT, 20271, OBJ)).thenReturn(List.of());
    when(repo.byId(eq(TENANT), eq(20271), any()))
        .thenAnswer(inv -> Optional.of(row(inv.getArgument(2), "DA_GUI", "4", "THAP")));
    when(delegates.isDelegate(TENANT, KR, UUID.fromString("00000000-0000-0000-0000-000000000001")))
        .thenReturn(true);
    CheckinCommand c =
        new CheckinCommand(
            "DA_GUI",
            new BigDecimal("4"),
            "THAP",
            new BigDecimal("5"),
            Instant.now(),
            "Số liệu nguồn chưa cập nhật",
            null,
            "Thiếu kinh phí",
            "MOBILE",
            false);
    CheckinOutcome out =
        service.record(
            TENANT, UUID.fromString("00000000-0000-0000-0000-000000000001"), KR, "k2", c);
    assertThat(out.onBehalf()).isTrue();
    verify(events).checkedIn(eq(TENANT), any(), any(), eq(4L));
    verify(events).atRisk(eq(TENANT), any(), any(), eq(4L));
    verify(hook).onSubmitted(any());
    verify(repo).writeObjectiveProgress(eq(TENANT), eq(20271), eq(OBJ), any(), any());
  }

  @Test
  void guiLaiCungKhoaTraKetQuaCuHoacBaoLech() {
    CheckinRow old = row(UUID.randomUUID(), "DA_GUI", "4", "CAO");
    when(repo.byIdempotencyKey(TENANT, 20271, "k3")).thenReturn(Optional.of(old));
    assertThat(service.record(TENANT, OWNER, KR, "k3", cmd("DA_GUI", "4.0", "CAO")).checkin())
        .isEqualTo(old);
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k3", cmd("DA_GUI", "5", "CAO")))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void thayDoiLonPhaiXacNhanVaXungDotPhienBan() {
    when(repo.lockTarget(TENANT, 20271, OBJ, KR))
        .thenReturn(Optional.of(target("DA_DUYET", "BINH_THUONG", "CAO")));
    when(repo.lastSubmitted(TENANT, 20271, KR))
        .thenReturn(Optional.of(row(UUID.randomUUID(), "DA_GUI", "1", "CAO")));
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k4", cmd("DA_GUI", "8.25", "CAO")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-LARGE-CHANGE-UNCONFIRMED"))
        .satisfies(t -> assertThat(((BusinessException) t).detail()).contains("72,5%"));
    CheckinCommand confirmed =
        new CheckinCommand(
            "DA_GUI", new BigDecimal("8"), "CAO", null, null, null, null, null, null, true);
    when(repo.writeKeyResultProgress(
            any(), anyInt(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(false);
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k5", confirmed))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-VERSION-CONFLICT"));
    when(repo.lockTarget(TENANT, 20271, OBJ, KR)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k6", confirmed))
        .isInstanceOf(OkrCheckinService.NotFound.class);
  }

  @Test
  void kiemQuyenKyVaTrangThai() {
    assertThatThrownBy(
            () -> service.record(TENANT, UUID.randomUUID(), KR, "k", cmd("NHAP", "1", "CAO")))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
    UUID missing = UUID.randomUUID();
    when(repo.target(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.record(TENANT, OWNER, missing, "k", cmd("NHAP", "1", "CAO")))
        .isInstanceOf(OkrCheckinService.NotFound.class);

    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.DA_KHOA)));
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k", cmd("NHAP", "1", "CAO")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-PERIOD-LOCKED"));
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.MO)));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k", cmd("NHAP", "1", "CAO")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-PERIOD-LOCKED"));

    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k", cmd("NHAP", "1", "CAO")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void mucTieuChuaDuyet() {
    when(repo.target(TENANT, KR)).thenReturn(Optional.of(target("NHAP", "BINH_THUONG", null)));
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k", cmd("NHAP", "1", "CAO")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-OBJECTIVE-NOT-APPROVED"));
  }

  @Test
  void kiemTruongDuLieu() {
    CheckinCommand bad =
        new CheckinCommand(
            null,
            new BigDecimal("1.12345"),
            null,
            null,
            null,
            "x".repeat(501),
            "h".repeat(1001),
            "b".repeat(1001),
            null,
            false);
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k", bad))
        .satisfies(
            t ->
                assertThat(fields(t))
                    .containsExactly(
                        "status",
                        "value",
                        "confidence",
                        "highlights",
                        "blockerNote",
                        "overrideReason"));
    assertThatThrownBy(
            () -> service.record(TENANT, OWNER, KR, "k", cmd("NHAP", "999999999999999999", "CAO")))
        .satisfies(t -> assertThat(fields(t)).containsExactly("value"));
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k", cmd("NHAP", null, "CAO")))
        .satisfies(t -> assertThat(fields(t)).containsExactly("value"));
    CheckinCommand override =
        new CheckinCommand(
            "DA_GUI", BigDecimal.ONE, "CAO", BigDecimal.TEN, null, " ", null, null, null, false);
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k", override))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-OVERRIDE-REASON-REQUIRED"));
    when(checks.flag(TENANT, PERIOD, UNIT, OkrCheckinService.P_BLOCKER_REQUIRED_ON_LOW))
        .thenReturn(true);
    assertThatThrownBy(() -> service.record(TENANT, OWNER, KR, "k", cmd("DA_GUI", "2", "THAP")))
        .satisfies(t -> assertThat(fields(t)).containsExactly("blockerNote"));
  }

  @Test
  void lichSuTraBanNhapONgayTrangDau() {
    UUID draft = UUID.randomUUID();
    when(repo.draft(TENANT, 20271, KR, OWNER))
        .thenReturn(Optional.of(row(draft, "NHAP", "1", "CAO")));
    when(repo.history(TENANT, 20271, KR, draft, null, null, 20)).thenReturn(List.of());
    assertThat(service.history(TENANT, OWNER, KR, null, null, 20).owner()).isEqualTo(OWNER);
    Instant at = Instant.now();
    service.history(TENANT, OWNER, KR, at, UUID.randomUUID(), 20);
    service.history(TENANT, null, KR, null, null, 20);
    verify(repo).history(TENANT, 20271, KR, draft, null, null, 20);
    verify(repo).history(eq(TENANT), eq(20271), eq(KR), eq(null), eq(at), any(), eq(20));
  }

  @Test
  void khongCoNguoiThayThiCam() {
    OkrCheckinService noDelegates =
        new OkrCheckinService(repo, periods, checks, () -> null, () -> null, events);
    assertThatThrownBy(
            () -> noDelegates.record(TENANT, UUID.randomUUID(), KR, "k", cmd("NHAP", "1", "CAO")))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
  }
}
