package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.PeriodCloseParticipantService.Command;
import vn.viettel.okrkpi.config.application.PeriodCloseParticipantService.JobStatus;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.domain.DefaultPeriodCalendar;
import vn.viettel.okrkpi.config.domain.IllegalPeriodTransitionException;
import vn.viettel.okrkpi.config.domain.LockOrderStatus;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** QA-06: MOD-08 là bên tham gia saga chốt kỳ và chiếu tiến độ saga sang lệnh khoá. */
class PeriodCloseParticipantServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-12-28T02:00:00Z");
  private static final UUID ADMIN = UUID.randomUUID();
  private static final UUID LEADER = UUID.randomUUID();

  private final PeriodId periodId = PeriodId.of(UUID.randomUUID());
  private final UUID saga = UUID.randomUUID();
  private final PeriodLockServiceTest.Orders orders = new PeriodLockServiceTest.Orders();
  private final PeriodRepository periods = mock(PeriodRepository.class);
  private final PeriodConfigService config = mock(PeriodConfigService.class);
  private final PeriodLockControlService control = mock(PeriodLockControlService.class);
  private final PeriodCloseParticipantService participant =
      new PeriodCloseParticipantService(orders, periods, config);
  private final PeriodLockProgressService progress = new PeriodLockProgressService(orders, control);

  private PeriodLockOrder running() {
    PeriodLockOrder o =
        PeriodLockOrder.request(
            UUID.randomUUID(), TENANT, periodId, "Thông báo", null, ADMIN, NOW, "CK-1");
    o.approve(LEADER, NOW);
    o.start(ADMIN, saga, NOW);
    orders.rows.put(o.id(), o);
    return o;
  }

  private void periodIn(PeriodState state) {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 12, 31);
    Period p =
        Period.builder()
            .id(periodId)
            .tenantId(TENANT)
            .code("2026-Q4")
            .name("Quý IV/2026")
            .kind(PeriodKind.QUY)
            .flow(PeriodFlow.CA_HAI)
            .startDate(start)
            .endDate(end)
            .schedule(DefaultPeriodCalendar.scheduleOf(start, end))
            .created(ADMIN, NOW)
            .build();
    if (state != PeriodState.MO) {
      p.moveTo(PeriodState.DANG_KHOA, UUID.randomUUID(), saga, ADMIN, NOW);
    }
    if (state == PeriodState.DA_KHOA) {
      p.moveTo(PeriodState.DA_KHOA, UUID.randomUUID(), saga, ADMIN, NOW);
    }
    when(periods.findById(TENANT, periodId)).thenReturn(Optional.of(p));
  }

  @Test
  void tienDieuKienKhoaVaHoanTatTheoTrangThaiKy() {
    PeriodLockOrder order = running();
    periodIn(PeriodState.MO);
    assertThat(participant.preconditions(TENANT, periodId, order.id(), saga).violations())
        .isEmpty();
    assertThat(
            participant.preconditions(TENANT, periodId, order.id(), UUID.randomUUID()).violations())
        .hasSize(1);
    assertThat(participant.preconditions(TENANT, periodId, null, saga).violations()).hasSize(1);
    assertThat(participant.state(TENANT, periodId, Command.KHOA_KY)).isEqualTo(JobStatus.CHUA_CO);

    participant.submit(TENANT, periodId, Command.KHOA_KY, saga, order.id());
    verify(config).transition(TENANT, periodId, PeriodState.DANG_KHOA, saga, ADMIN, null, null);
    assertThatThrownBy(
            () -> participant.submit(TENANT, periodId, Command.HOAN_TAT_KY, saga, order.id()))
        .isInstanceOf(IllegalPeriodTransitionException.class);
    participant.undo(TENANT, periodId, Command.KHOA_KY, saga, order.id(), "x");
    verify(config, never())
        .transition(any(), any(), eq(PeriodState.MO), any(), any(), any(), any());

    periodIn(PeriodState.DANG_KHOA);
    assertThat(participant.state(TENANT, periodId, Command.KHOA_KY)).isEqualTo(JobStatus.HOAN_TAT);
    assertThat(participant.state(TENANT, periodId, Command.HOAN_TAT_KY))
        .isEqualTo(JobStatus.CHUA_CO);
    participant.submit(TENANT, periodId, Command.KHOA_KY, saga, order.id());
    participant.submit(TENANT, periodId, Command.HOAN_TAT_KY, saga, order.id());
    verify(config).transition(TENANT, periodId, PeriodState.DA_KHOA, saga, ADMIN, null, null);
    participant.undo(TENANT, periodId, Command.HOAN_TAT_KY, saga, order.id(), "x");
    participant.undo(TENANT, periodId, Command.KHOA_KY, saga, order.id(), "Bù trừ");
    verify(config).transition(TENANT, periodId, PeriodState.MO, saga, ADMIN, order.id(), "Bù trừ");

    periodIn(PeriodState.DA_KHOA);
    assertThat(participant.state(TENANT, periodId, Command.HOAN_TAT_KY))
        .isEqualTo(JobStatus.HOAN_TAT);
    participant.submit(TENANT, periodId, Command.HOAN_TAT_KY, saga, order.id());
    assertThat(participant.preconditions(TENANT, periodId, order.id(), saga).violations())
        .containsExactly("Kỳ đánh giá không còn mở.");
  }

  @Test
  void banChieuBuocVaLenhTheoVetSaga() {
    PeriodLockOrder order = running();
    assertThat(progress.apply(TENANT, saga, "BAT_DAU_BUOC", 2, true, ADMIN, NOW)).isTrue();
    LockStep step = orders.steps.get(order.id()).get(0);
    assertThat(step.stepNo()).isEqualTo(1);
    assertThat(step.status()).isEqualTo("DANG_CHAY");
    assertThat(step.startedAt()).isEqualTo(NOW);
    Instant later = NOW.plusSeconds(5);
    progress.apply(TENANT, saga, "NHAN_TRA_LOI", 2, false, ADMIN, later);
    step = orders.steps.get(order.id()).get(0);
    assertThat(step.status()).isEqualTo("LOI");
    assertThat(step.startedAt()).isEqualTo(NOW);
    assertThat(step.finishedAt()).isEqualTo(later);
    progress.apply(TENANT, saga, "BU_TRU_XONG", 2, true, ADMIN, later);
    assertThat(orders.steps.get(order.id()).get(0).status()).isEqualTo("DA_BU_TRU");
    assertThat(orders.steps.get(order.id()).get(0).compensatedAt()).isEqualTo(later);

    // Hành động không đổi bản chiếu, bước ngoài 1..6, saga lạ: bỏ qua.
    assertThat(progress.apply(TENANT, saga, "TAM_DUNG", 2, true, ADMIN, NOW)).isFalse();
    assertThat(progress.apply(TENANT, saga, "BUOC_XONG", 7, true, ADMIN, NOW)).isFalse();
    assertThat(progress.apply(TENANT, UUID.randomUUID(), "HOAN_TAT", null, true, ADMIN, NOW))
        .isFalse();

    // Saga bù trừ xong mà không có đề nghị huỷ đã duyệt: không huỷ lệnh.
    assertThat(progress.apply(TENANT, saga, "DA_BU_TRU", null, true, ADMIN, NOW)).isFalse();
    verify(control, never()).completeCancel(any(), any(), any());
    assertThat(progress.apply(TENANT, saga, "HOAN_TAT", null, true, ADMIN, NOW)).isTrue();
    assertThat(order.status()).isEqualTo(LockOrderStatus.HOAN_TAT);
    assertThat(progress.apply(TENANT, saga, "HOAN_TAT", null, true, ADMIN, NOW)).isFalse();
  }

  @Test
  void sagaBuTruTheoDeNghiHuyDaDuyetThiHoanTatHuy() {
    PeriodLockOrder order = running();
    order.requestCancel(ADMIN, "Phát hiện sai sót dữ liệu cần huỷ khoá kỳ", NOW);
    order.approveCancel(LEADER, NOW);
    when(control.completeCancel(TENANT, order.id(), ADMIN)).thenReturn(true);
    assertThat(progress.apply(TENANT, saga, "DA_BU_TRU", null, true, ADMIN, NOW)).isTrue();
    verify(control).completeCancel(TENANT, order.id(), ADMIN);
    assertThat(PeriodLockProgressService.stepStatus("CHAY_LAI_LOI", true)).isEqualTo("DANG_CHAY");
    assertThat(PeriodLockProgressService.stepStatus("QUA_HAN_TRA_LOI", false)).isEqualTo("LOI");
  }
}
