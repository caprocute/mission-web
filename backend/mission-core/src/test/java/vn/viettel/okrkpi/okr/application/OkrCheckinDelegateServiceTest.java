package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository.Delegation;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository.KeyResultCode;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị uỷ quyền check-in thay và chuyển giá trị sang phiên bản mới (CORE-01-12). */
class OkrCheckinDelegateServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID OWNER = UUID.randomUUID();
  static final UUID PERSON = UUID.randomUUID();
  static final UUID KR = UUID.randomUUID();
  static final UUID OBJ = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  static final Clock CLOCK = Clock.fixed(Instant.parse("2027-02-10T01:00:00Z"), VN);

  private final OkrCheckinRepository checkins = mock(OkrCheckinRepository.class);
  private final OkrCheckinDelegateRepository delegates = mock(OkrCheckinDelegateRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OkrCheckinDelegateService service =
      new OkrCheckinDelegateService(checkins, delegates, periods, CLOCK);

  static CheckinTarget target(UUID id, String objectiveStatus, BigDecimal current, long version) {
    return new CheckinTarget(
        id,
        PERIOD,
        20271,
        "KR 1.1",
        "SO_LUONG",
        "TANG",
        BigDecimal.ZERO,
        BigDecimal.TEN,
        current,
        BigDecimal.ZERO,
        "CAO",
        "THEO_DOI",
        version,
        OBJ,
        "O1",
        objectiveStatus,
        BigDecimal.ZERO,
        UUID.randomUUID(),
        OWNER,
        UUID.randomUUID());
  }

  static Delegation delegation(LocalDate until) {
    return new Delegation(UUID.randomUUID(), KR, PERSON, until, null, OWNER, Instant.now());
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @BeforeEach
  void setUp() {
    CheckinTarget t = target(KR, "DA_DUYET", null, 2);
    when(checkins.target(TENANT, KR)).thenReturn(Optional.of(t));
    when(checkins.lockTarget(TENANT, 20271, OBJ, KR)).thenReturn(Optional.of(t));
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    TENANT,
                    "Q1",
                    "Quý I",
                    LocalDate.of(2027, 1, 1),
                    LocalDate.of(2027, 3, 31),
                    PeriodStatus.MO)));
    when(delegates.touchKeyResult(eq(TENANT), eq(20271), eq(KR), eq(2L), any())).thenReturn(3L);
  }

  @Test
  void giaoQuyenThayNguoiCu() {
    Delegation old = delegation(null);
    when(delegates.open(TENANT, KR)).thenReturn(Optional.of(old));
    assertThat(
            service.setDelegate(
                TENANT, OWNER, KR, 2, PERSON, LocalDate.of(2027, 2, 20), "  Đi công tác  "))
        .isEqualTo(3L);
    verify(delegates).end(TENANT, old.id(), OWNER);
    verify(delegates).appoint(TENANT, KR, PERSON, LocalDate.of(2027, 2, 20), "Đi công tác", OWNER);
    when(delegates.open(TENANT, KR)).thenReturn(Optional.empty());
    service.setDelegate(TENANT, OWNER, KR, 2, PERSON, null, " ");
    verify(delegates).appoint(TENANT, KR, PERSON, null, null, OWNER);
  }

  @Test
  void giaoQuyenKiemDuLieu() {
    assertThatThrownBy(() -> service.setDelegate(TENANT, OWNER, KR, 2, null, null, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(() -> service.setDelegate(TENANT, OWNER, KR, 2, OWNER, null, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.setDelegate(TENANT, OWNER, KR, 2, PERSON, LocalDate.of(2027, 2, 9), null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.setDelegate(TENANT, OWNER, KR, 2, PERSON, null, "x".repeat(501)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
  }

  @Test
  void chiChuSoHuuMucTieuChuaDongKyMo() {
    assertThatThrownBy(() -> service.setDelegate(TENANT, PERSON, KR, 2, PERSON, null, null))
        .isInstanceOf(OkrCheckinService.Forbidden.class);
    assertThatThrownBy(() -> service.revokeDelegate(TENANT, OWNER, KR, 1))
        .isInstanceOf(VersionConflictException.class);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(() -> service.revokeDelegate(TENANT, OWNER, KR, 2))
        .isInstanceOf(PeriodLockedException.class);
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.revokeDelegate(TENANT, OWNER, KR, 2))
        .isInstanceOf(IllegalStateException.class);
    CheckinTarget closed = target(KR, "DONG", null, 2);
    when(checkins.lockTarget(TENANT, 20271, OBJ, KR)).thenReturn(Optional.of(closed));
    assertThatThrownBy(() -> service.revokeDelegate(TENANT, OWNER, KR, 2))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-INVALID-STATE"));
    when(checkins.lockTarget(TENANT, 20271, OBJ, KR)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.revokeDelegate(TENANT, OWNER, KR, 2))
        .isInstanceOf(OkrCheckinService.NotFound.class);
    UUID missing = UUID.randomUUID();
    when(checkins.target(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.revokeDelegate(TENANT, OWNER, missing, 2))
        .isInstanceOf(OkrCheckinService.NotFound.class);
  }

  @Test
  void thuHoiVaTraCuu() {
    Delegation open = delegation(LocalDate.of(2027, 2, 9));
    when(delegates.open(TENANT, KR)).thenReturn(Optional.of(open));
    assertThat(service.delegateOf(TENANT, KR)).isEmpty();
    assertThat(service.revokeDelegate(TENANT, OWNER, KR, 2)).isEqualTo(3L);
    verify(delegates).end(TENANT, open.id(), OWNER);
    when(delegates.open(TENANT, KR)).thenReturn(Optional.of(delegation(LocalDate.of(2027, 2, 10))));
    assertThat(service.delegateOf(TENANT, KR)).isPresent();
    when(delegates.open(TENANT, KR)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.revokeDelegate(TENANT, OWNER, KR, 2))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-INVALID-STATE"));
  }

  @Test
  void chuyenGiaTriSangPhienBanMoi() {
    UUID prev = UUID.randomUUID();
    UUID rev = UUID.randomUUID();
    UUID oldKr = UUID.randomUUID();
    UUID oldEmpty = UUID.randomUUID();
    UUID newKr = UUID.randomUUID();
    UUID newEmpty = UUID.randomUUID();
    UUID added = UUID.randomUUID();
    when(delegates.keyResultCodes(TENANT, 20271, prev))
        .thenReturn(
            List.of(new KeyResultCode(oldKr, "KR 1.1"), new KeyResultCode(oldEmpty, "KR 1.2")));
    when(delegates.keyResultCodes(TENANT, 20271, rev))
        .thenReturn(
            List.of(
                new KeyResultCode(newKr, "KR 1.1"),
                new KeyResultCode(newEmpty, "KR 1.2"),
                new KeyResultCode(added, "KR 1.3")));
    when(checkins.target(TENANT, oldKr))
        .thenReturn(Optional.of(target(oldKr, "DONG", new BigDecimal("4"), 7)));
    when(checkins.target(TENANT, newKr))
        .thenReturn(Optional.of(target(newKr, "DA_DUYET", null, 0)));
    when(checkins.target(TENANT, oldEmpty))
        .thenReturn(Optional.of(target(oldEmpty, "DONG", null, 1)));
    when(checkins.target(TENANT, newEmpty)).thenReturn(Optional.empty());
    when(checkins.writeKeyResultProgress(
            any(), anyInt(), eq(newKr), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    when(checkins.objectiveWeights(TENANT, 20271, rev)).thenReturn(List.of());
    OkrRevisionCarryOver carry = new OkrRevisionCarryOver(checkins, delegates);
    carry.on(new OkrObjectiveRevisionApproved(TENANT, prev, rev, 20271, OWNER));
    verify(checkins)
        .writeKeyResultProgress(
            eq(TENANT),
            eq(20271),
            eq(newKr),
            eq(0L),
            eq(new BigDecimal("4")),
            eq("CAO"),
            any(),
            eq("THEO_DOI"),
            eq(OWNER));
    verify(checkins).writeObjectiveProgress(eq(TENANT), eq(20271), eq(rev), any(), eq(OWNER));

    // Không KR nào đổi thì không tính lại mục tiêu.
    when(delegates.keyResultCodes(TENANT, 20271, rev))
        .thenReturn(List.of(new KeyResultCode(added, "KR 1.3")));
    carry.on(new OkrObjectiveRevisionApproved(TENANT, prev, rev, 20271, OWNER));
    verify(checkins).writeObjectiveProgress(eq(TENANT), eq(20271), eq(rev), any(), eq(OWNER));
  }
}
