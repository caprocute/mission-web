package vn.viettel.okrkpi.workflow.application.periodclose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandler;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandler.StepReply;
import vn.viettel.okrkpi.workflow.application.saga.StepInvocation;
import vn.viettel.okrkpi.workflow.application.saga.StepResult;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/** DV-06-06: bộ xử lý từng bước, mã tác vụ tất định và ngân sách. */
class PeriodCloseStepsTest {

  private static final Instant NOW = Instant.parse("2026-12-31T10:00:00Z");
  private final UUID saga = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private final List<String> alarms = new ArrayList<>();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void sauBuocTheoThuTuTl02aVaBuocCuoiLaDiemKhongQuayLai() {
    PeriodCloseSaga blueprint = new PeriodCloseSaga();
    assertThat(blueprint.definition().steps())
        .extracting(s -> s.stepCode())
        .containsExactly(
            "KIEM_TIEN_DIEU_KIEN",
            "DAT_DANG_KHOA",
            "TINH_DIEM_CHOT_KY",
            "SINH_BIEU_MAU",
            "KY_SO_LO",
            "HOAN_TAT");
    assertThat(blueprint.definition().steps()).filteredOn(s -> s.pointOfNoReturn()).hasSize(1);
    assertThat(blueprint.definition().compensateOnExhaustion()).isFalse();
    assertThat(blueprint.exclusiveKey(Map.of("periodId", period.toString())))
        .isEqualTo(period.toString());
    assertThat(blueprint.exclusiveKey(Map.of())).isNull();
    // Ký số: 13 lần thử, tổng thời gian lùi khoảng 4 giờ.
    var signing = blueprint.definition().steps().get(4).retry();
    Duration total = Duration.ZERO;
    for (int i = 1; i < signing.maxAttempts(); i++) {
      total = total.plus(signing.backoffAfter(i));
    }
    assertThat(total).isBetween(Duration.ofHours(4), Duration.ofHours(4).plusMinutes(10));
    assertThatThrownBy(() -> PeriodCloseSteps.handler("KHAC", null, null, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tienDieuKienKhongDatLaTuChoiNghiepVu() {
    FakeGateway gateway = new FakeGateway();
    SagaStepHandler step = handler(PeriodCloseSaga.KIEM_TIEN_DIEU_KIEN, gateway);
    assertThat(step.sagaType()).isEqualTo(SagaType.CHOT_KY);
    assertThat(step.execute(invocation(1, 1))).isInstanceOf(StepResult.Completed.class);

    gateway.violations = List.of("còn đề nghị gia hạn chờ duyệt");
    StepResult result = step.execute(invocation(1, 1));
    assertThat(result)
        .isEqualTo(
            new StepResult.Rejected(
                "PERIOD-422-PRECONDITION",
                "Kỳ chưa đủ điều kiện để khoá: còn đề nghị gia hạn chờ duyệt."));
    assertThat(((StepResult.Rejected) result).error()).startsWith("PERIOD-422-PRECONDITION: ");
  }

  @Test
  void buocTuXaGiaoTacVuMaTatDinhThamDoVaBuTru() {
    FakeGateway gateway = new FakeGateway();
    SagaStepHandler step = handler(PeriodCloseSaga.TINH_DIEM_CHOT_KY, gateway);
    StepResult first = step.execute(invocation(3, 1));
    StepResult again = step.execute(invocation(3, 1));
    StepResult retry = step.execute(invocation(3, 2));
    UUID job = ((StepResult.AwaitingReply) first).commandEventId();
    assertThat(((StepResult.AwaitingReply) again).commandEventId()).isEqualTo(job);
    assertThat(((StepResult.AwaitingReply) retry).commandEventId()).isNotEqualTo(job);

    gateway.state = PeriodCloseGateway.JobState.running(30);
    assertThat(step.poll(invocation(3, 1), job, NOW.minusSeconds(60))).isEmpty();
    gateway.state = PeriodCloseGateway.JobState.failed(null);
    assertThat(step.poll(invocation(3, 1), job, NOW))
        .contains(StepReply.failed("Bên tham gia báo tác vụ thất bại"));
    gateway.state = PeriodCloseGateway.JobState.done();
    assertThat(step.poll(invocation(3, 1), job, NOW)).contains(StepReply.done());
    gateway.state = PeriodCloseGateway.JobState.missing();
    int before = gateway.submits.size();
    assertThat(step.poll(invocation(3, 1), job, NOW)).isEmpty();
    assertThat(gateway.submits).hasSize(before + 1);

    // Quá ngân sách 2 giờ thì cảnh báo, không dừng bước.
    gateway.state = PeriodCloseGateway.JobState.running(90);
    assertThat(step.poll(invocation(3, 1), job, NOW.minus(Duration.ofHours(3)))).isEmpty();
    assertThat(alarms).containsExactly("TINH_DIEM_CHOT_KY");

    step.compensate(invocation(3, 1));
    assertThat(gateway.undone).containsExactly("TINH_DIEM");
  }

  @Test
  void hoanTatDatKhoaKyRoiMoiNiemPhongVaKhongCoBuTru() {
    FakeGateway gateway = new FakeGateway();
    SagaStepHandler step = handler(PeriodCloseSaga.HOAN_TAT, gateway);
    UUID job = ((StepResult.AwaitingReply) step.execute(invocation(6, 1))).commandEventId();
    assertThat(gateway.submits).containsExactly("HOAN_TAT_KY");

    gateway.state = PeriodCloseGateway.JobState.running(10);
    assertThat(step.poll(invocation(6, 1), job, NOW)).isEmpty();
    gateway.state = PeriodCloseGateway.JobState.done();
    gateway.sealState = PeriodCloseGateway.JobState.missing();
    assertThat(step.poll(invocation(6, 1), job, NOW)).isEmpty();
    assertThat(gateway.submits).containsExactly("HOAN_TAT_KY", "NIEM_PHONG");
    gateway.sealState = PeriodCloseGateway.JobState.done();
    assertThat(step.poll(invocation(6, 1), job, NOW)).contains(StepReply.done());
    assertThatThrownBy(() -> step.compensate(invocation(6, 1)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void tinhDiemGiuChoNguoiVanHanhKhiConHoSoLoiChayLaiChapNhanVaKiemTatDinh() {
    FakeGateway gateway = new FakeGateway();
    List<Integer> mismatches = new ArrayList<>();
    SagaStepHandler step =
        PeriodCloseSteps.handler(
            PeriodCloseSaga.TINH_DIEM_CHOT_KY,
            gateway,
            new PeriodCloseAlarms() {
              @Override
              public void overBudget(StepInvocation inv, Duration budget, Duration elapsed) {
                alarms.add(inv.stepCode());
              }

              @Override
              public void determinismMismatch(StepInvocation inv, int profiles) {
                mismatches.add(profiles);
              }
            },
            clock);
    UUID job = UUID.randomUUID();
    UUID rerun = UUID.randomUUID();

    // Lô chính chưa xong, rồi lỗi.
    gateway.byCommand.put(
        PeriodCloseGateway.Command.TINH_DIEM, PeriodCloseGateway.JobState.missing());
    assertThat(step.poll(invocation(3, 1), job, NOW)).isEmpty();
    assertThat(gateway.submits).containsExactly("TINH_DIEM");
    gateway.byCommand.put(
        PeriodCloseGateway.Command.TINH_DIEM, PeriodCloseGateway.JobState.failed("hỏng"));
    assertThat(step.poll(invocation(3, 1), job, NOW)).contains(StepReply.failed("hỏng"));

    // Lô chính xong nhưng còn 100 hồ sơ lỗi: giữ chờ người vận hành.
    gateway.byCommand.put(
        PeriodCloseGateway.Command.TINH_DIEM, PeriodCloseGateway.JobState.done(100));
    assertThat(step.poll(invocation(3, 1), job, NOW))
        .hasValueSatisfying(r -> assertThat(r.hold()).isTrue());

    // Chạy lại: lần đầu giao lô mới, đang chạy, lỗi thì giữ, xong còn 2 lỗi thì vẫn giữ.
    Map<String, Object> retry = Map.of(PeriodCloseSteps.Scoring.RETRY_NOTE, rerun.toString());
    gateway.byCommand.put(
        PeriodCloseGateway.Command.TINH_LAI_HO_SO_LOI, PeriodCloseGateway.JobState.missing());
    assertThat(step.poll(invocation(3, 1, retry), job, NOW)).isEmpty();
    assertThat(gateway.submits).containsExactly("TINH_DIEM", "TINH_LAI_HO_SO_LOI");
    gateway.byCommand.put(
        PeriodCloseGateway.Command.TINH_LAI_HO_SO_LOI, PeriodCloseGateway.JobState.running(50));
    assertThat(step.poll(invocation(3, 1, retry), job, NOW)).isEmpty();
    gateway.byCommand.put(
        PeriodCloseGateway.Command.TINH_LAI_HO_SO_LOI, PeriodCloseGateway.JobState.failed("x"));
    assertThat(step.poll(invocation(3, 1, retry), job, NOW))
        .hasValueSatisfying(
            r -> {
              assertThat(r.hold()).isTrue();
              assertThat(r.error()).contains("chạy lại");
            });
    gateway.byCommand.put(
        PeriodCloseGateway.Command.TINH_LAI_HO_SO_LOI, PeriodCloseGateway.JobState.done(2));
    assertThat(step.poll(invocation(3, 1, retry), job, NOW))
        .hasValueSatisfying(r -> assertThat(r.error()).contains("Còn 2 hồ sơ"));

    // Chấp nhận để lại lỗi: sang kiểm tất định; khớp thì xong, lệch thì cảnh báo và giữ.
    Map<String, Object> accepted =
        Map.of(PeriodCloseSteps.Scoring.ACCEPT_NOTE, Map.of("lyDo", "đã xác minh"));
    gateway.byCommand.put(
        PeriodCloseGateway.Command.KIEM_TAT_DINH, PeriodCloseGateway.JobState.running(1));
    assertThat(step.poll(invocation(3, 1, accepted), job, NOW)).isEmpty();
    gateway.byCommand.put(
        PeriodCloseGateway.Command.KIEM_TAT_DINH, PeriodCloseGateway.JobState.done());
    assertThat(step.poll(invocation(3, 1, accepted), job, NOW)).contains(StepReply.done());
    gateway.byCommand.put(
        PeriodCloseGateway.Command.KIEM_TAT_DINH, PeriodCloseGateway.JobState.done(3));
    assertThat(step.poll(invocation(3, 1, accepted), job, NOW))
        .hasValueSatisfying(r -> assertThat(r.hold()).isTrue());
    assertThat(mismatches).containsExactly(3);
    assertThatThrownBy(
            () ->
                new PeriodCloseGateway.JobState(
                    PeriodCloseGateway.JobState.Status.HOAN_TAT, 100, null, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private SagaStepHandler handler(String code, PeriodCloseGateway gateway) {
    return PeriodCloseSteps.handler(
        code, gateway, (inv, budget, elapsed) -> alarms.add(inv.stepCode()), clock);
  }

  private StepInvocation invocation(int stepNo, int attempt) {
    return invocation(stepNo, attempt, Map.of());
  }

  private StepInvocation invocation(int stepNo, int attempt, Map<String, Object> notes) {
    String code = new PeriodCloseSaga().definition().steps().get(stepNo - 1).stepCode();
    return new StepInvocation(
        saga,
        UUID.randomUUID(),
        SagaType.CHOT_KY,
        UUID.randomUUID(),
        stepNo,
        code,
        null,
        attempt,
        Map.of(
            "periodId", period.toString(),
            "lockOrderId", UUID.randomUUID().toString(),
            "unitIds", List.of(UUID.randomUUID().toString()),
            "requestedBy", UUID.randomUUID().toString()),
        notes);
  }

  static final class FakeGateway implements PeriodCloseGateway {
    List<String> violations = List.of();
    JobState state = JobState.done();
    JobState sealState = JobState.done();
    final Map<Command, JobState> byCommand = new java.util.EnumMap<>(Command.class);
    final List<String> submits = new ArrayList<>();
    final List<String> undone = new ArrayList<>();

    @Override
    public PreconditionReport checkPreconditions(PeriodCloseRequest request) {
      return new PreconditionReport(violations, List.of("cảnh báo"));
    }

    @Override
    public void submit(Command command, PeriodCloseRequest request, UUID jobId) {
      submits.add(command.name());
    }

    @Override
    public JobState state(Command command, PeriodCloseRequest request, UUID jobId) {
      if (byCommand.containsKey(command)) {
        return byCommand.get(command);
      }
      return command == Command.NIEM_PHONG ? sealState : state;
    }

    @Override
    public void undo(Command command, PeriodCloseRequest request, String undoKey) {
      undone.add(command.name());
    }
  }
}
