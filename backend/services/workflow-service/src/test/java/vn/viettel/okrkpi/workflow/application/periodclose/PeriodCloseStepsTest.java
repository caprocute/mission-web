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

  private SagaStepHandler handler(String code, PeriodCloseGateway gateway) {
    return PeriodCloseSteps.handler(
        code, gateway, (inv, budget, elapsed) -> alarms.add(inv.stepCode()), clock);
  }

  private StepInvocation invocation(int stepNo, int attempt) {
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
            "requestedBy", UUID.randomUUID().toString()));
  }

  static final class FakeGateway implements PeriodCloseGateway {
    List<String> violations = List.of();
    JobState state = JobState.done();
    JobState sealState = JobState.done();
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
      return command == Command.NIEM_PHONG ? sealState : state;
    }

    @Override
    public void undo(Command command, PeriodCloseRequest request, String undoKey) {
      undone.add(command.name());
    }
  }
}
