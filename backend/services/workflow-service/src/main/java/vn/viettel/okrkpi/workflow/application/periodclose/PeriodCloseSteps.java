package vn.viettel.okrkpi.workflow.application.periodclose;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway.Command;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway.JobState;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway.PreconditionReport;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandler;
import vn.viettel.okrkpi.workflow.application.saga.StepInvocation;
import vn.viettel.okrkpi.workflow.application.saga.StepResult;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/** Bộ xử lý sáu bước của saga {@code CHOT_KY} (DV-06-06). */
public final class PeriodCloseSteps {

  static final String PRECONDITION_FAILED = "PERIOD-422-PRECONDITION";

  private PeriodCloseSteps() {}

  /** Đủ sáu bộ xử lý cho {@link PeriodCloseSaga}, theo thứ tự bước. */
  public static List<SagaStepHandler> handlers(
      PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
    return List.of(
        handler(PeriodCloseSaga.KIEM_TIEN_DIEU_KIEN, gateway, alarms, clock),
        handler(PeriodCloseSaga.DAT_DANG_KHOA, gateway, alarms, clock),
        handler(PeriodCloseSaga.TINH_DIEM_CHOT_KY, gateway, alarms, clock),
        handler(PeriodCloseSaga.SINH_BIEU_MAU, gateway, alarms, clock),
        handler(PeriodCloseSaga.KY_SO_LO, gateway, alarms, clock),
        handler(PeriodCloseSaga.HOAN_TAT, gateway, alarms, clock));
  }

  /** Bộ xử lý của một bước. */
  public static SagaStepHandler handler(
      String stepCode, PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
    return switch (stepCode) {
      case PeriodCloseSaga.KIEM_TIEN_DIEU_KIEN -> new Precondition(gateway);
      case PeriodCloseSaga.DAT_DANG_KHOA ->
          new RemoteStep(stepCode, Command.KHOA_KY, gateway, alarms, clock);
      case PeriodCloseSaga.TINH_DIEM_CHOT_KY -> new Scoring(gateway, alarms, clock);
      case PeriodCloseSaga.SINH_BIEU_MAU ->
          new RemoteStep(stepCode, Command.SINH_BIEU_MAU, gateway, alarms, clock);
      case PeriodCloseSaga.KY_SO_LO ->
          new RemoteStep(stepCode, Command.KY_SO_LO, gateway, alarms, clock);
      case PeriodCloseSaga.HOAN_TAT -> new Completion(gateway, alarms, clock);
      default -> throw new IllegalArgumentException("Không có bước chốt kỳ " + stepCode);
    };
  }

  /**
   * Mã tác vụ saga cấp cho bên nhận: tất định theo saga, bước, lần thử và lệnh, nên chạy lại lượt
   * sau khi pod chết giao lại đúng tác vụ cũ, còn lần thử sau thất bại tạo tác vụ mới.
   */
  static UUID jobId(StepInvocation invocation, Command command) {
    String key =
        invocation.sagaId()
            + ":"
            + invocation.stepNo()
            + ":"
            + invocation.attempt()
            + ":"
            + command;
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
  }

  static UUID derived(UUID jobId, Command command) {
    return UUID.nameUUIDFromBytes((jobId + ":" + command).getBytes(StandardCharsets.UTF_8));
  }

  /** Bước 1 (TL-02a bước 0): tiền điều kiện; không đạt là từ chối nghiệp vụ, không thử lại. */
  static final class Precondition implements SagaStepHandler {

    private static final Logger LOG = LoggerFactory.getLogger(Precondition.class);

    private final PeriodCloseGateway gateway;

    Precondition(PeriodCloseGateway gateway) {
      this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public SagaType sagaType() {
      return SagaType.CHOT_KY;
    }

    @Override
    public String stepCode() {
      return PeriodCloseSaga.KIEM_TIEN_DIEU_KIEN;
    }

    @Override
    public StepResult execute(StepInvocation invocation) {
      PreconditionReport report = gateway.checkPreconditions(PeriodCloseRequest.of(invocation));
      if (!report.warnings().isEmpty()) {
        LOG.info(
            "Saga chốt kỳ {}: {} cảnh báo tiền điều kiện",
            invocation.sagaId(),
            report.warnings().size());
      }
      if (!report.passed()) {
        return StepResult.rejected(
            PRECONDITION_FAILED,
            "Kỳ chưa đủ điều kiện để khoá: " + String.join("; ", report.violations()) + ".");
      }
      return StepResult.completed();
    }
  }

  /**
   * Bước giao một tác vụ dài cho bên tham gia rồi thăm dò tới khi xong; bù trừ là hoàn tác của bên
   * đó (bước 2 đến 5).
   */
  static class RemoteStep implements SagaStepHandler {

    private final String stepCode;
    final Command command;
    final PeriodCloseGateway gateway;
    final PeriodCloseAlarms alarms;
    private final Clock clock;
    private final Duration budget;

    RemoteStep(
        String stepCode,
        Command command,
        PeriodCloseGateway gateway,
        PeriodCloseAlarms alarms,
        Clock clock) {
      this.stepCode = stepCode;
      this.command = command;
      this.gateway = Objects.requireNonNull(gateway, "gateway");
      this.alarms = Objects.requireNonNull(alarms, "alarms");
      this.clock = Objects.requireNonNull(clock, "clock");
      this.budget = PeriodCloseSaga.BUDGETS.get(stepCode);
    }

    @Override
    public SagaType sagaType() {
      return SagaType.CHOT_KY;
    }

    @Override
    public String stepCode() {
      return stepCode;
    }

    @Override
    public StepResult execute(StepInvocation invocation) {
      UUID jobId = jobId(invocation, command);
      gateway.submit(command, PeriodCloseRequest.of(invocation), jobId);
      return StepResult.awaiting(jobId);
    }

    @Override
    public Optional<StepReply> poll(
        StepInvocation invocation, UUID commandEventId, Instant stepStartedAt) {
      watchBudget(invocation, stepStartedAt);
      return await(command, PeriodCloseRequest.of(invocation), commandEventId);
    }

    @Override
    public void compensate(StepInvocation invocation) {
      gateway.undo(command, PeriodCloseRequest.of(invocation), invocation.idempotencyKey());
    }

    final void watchBudget(StepInvocation invocation, Instant stepStartedAt) {
      if (budget == null || stepStartedAt == null) {
        return;
      }
      Duration elapsed = Duration.between(stepStartedAt, clock.instant());
      if (elapsed.compareTo(budget) > 0) {
        alarms.overBudget(invocation, budget, elapsed);
      }
    }

    /** Trạng thái tác vụ; bên nhận chưa có tác vụ thì giao lại (idempotent theo mã tác vụ). */
    final Optional<StepReply> await(Command c, PeriodCloseRequest request, UUID jobId) {
      JobState state = gateway.state(c, request, jobId);
      if (state.status() == JobState.Status.CHUA_CO) {
        gateway.submit(c, request, jobId);
        return Optional.empty();
      }
      return reply(state);
    }

    static Optional<StepReply> reply(JobState state) {
      return switch (state.status()) {
        case CHUA_CO, DANG_CHAY -> Optional.empty();
        case HOAN_TAT -> Optional.of(StepReply.done());
        case THAT_BAI ->
            Optional.of(
                StepReply.failed(
                    state.error() == null ? "Bên tham gia báo tác vụ thất bại" : state.error()));
      };
    }
  }

  /**
   * Bước 3 (TL-02a bước 2): tính điểm chốt kỳ. Tác vụ xong mà còn hồ sơ lỗi thì bước chờ người vận
   * hành chạy lại lô hồ sơ lỗi ({@link #RETRY_NOTE}) hoặc chấp nhận để lại kèm lý do ({@link
   * #ACCEPT_NOTE}); sau đó kiểm tất định 0,1% hồ sơ (BR-08-11), lệch thì chờ người vận hành.
   */
  static final class Scoring extends RemoteStep {

    static final String RETRY_NOTE = "chayLaiHoSoLoi";
    static final String ACCEPT_NOTE = "chapNhanHoSoLoi";
    static final String RETRY_PROFILES_NOTE = "hoSoChayLai";

    Scoring(PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
      super(PeriodCloseSaga.TINH_DIEM_CHOT_KY, Command.TINH_DIEM, gateway, alarms, clock);
    }

    @Override
    public Optional<StepReply> poll(
        StepInvocation invocation, UUID commandEventId, Instant stepStartedAt) {
      watchBudget(invocation, stepStartedAt);
      PeriodCloseRequest request = PeriodCloseRequest.of(invocation);
      Outcome scoring = outcome(command, request, commandEventId);
      if (scoring.reply != null) {
        return scoring.reply;
      }
      int failed = scoring.state.failedItems();
      Object retry = invocation.notes().get(RETRY_NOTE);
      if (retry != null) {
        Outcome rerun =
            outcome(Command.TINH_LAI_HO_SO_LOI, request, UUID.fromString(retry.toString()));
        if (rerun.reply != null) {
          return rerun.reply.map(
              r ->
                  r.success()
                      ? r
                      : StepReply.hold("Lần chạy lại hồ sơ lỗi không thành công: " + r.error()));
        }
        failed = rerun.state.failedItems();
      }
      if (failed > 0 && !invocation.notes().containsKey(ACCEPT_NOTE)) {
        return Optional.of(
            StepReply.hold(
                "Còn "
                    + failed
                    + " hồ sơ tính điểm lỗi: chạy lại sau khi sửa nguyên nhân hoặc chấp nhận để lại"
                    + " kèm lý do."));
      }
      Outcome check =
          outcome(Command.KIEM_TAT_DINH, request, derived(commandEventId, Command.KIEM_TAT_DINH));
      if (check.reply != null) {
        return check.reply;
      }
      if (check.state.failedItems() > 0) {
        alarms.determinismMismatch(invocation, check.state.failedItems());
        return Optional.of(
            StepReply.hold(
                "Kiểm tra tính lại phát hiện "
                    + check.state.failedItems()
                    + " hồ sơ cho kết quả khác kết quả đã lưu."));
      }
      return Optional.of(StepReply.done());
    }

    /** Tác vụ xong thì {@code reply} rỗng và có {@code state}; chưa xong hoặc lỗi thì có reply. */
    private Outcome outcome(Command c, PeriodCloseRequest request, UUID jobId) {
      JobState state = gateway.state(c, request, jobId);
      return switch (state.status()) {
        case CHUA_CO -> {
          gateway.submit(c, request, jobId);
          yield new Outcome(null, Optional.empty());
        }
        case DANG_CHAY -> new Outcome(null, Optional.empty());
        case THAT_BAI -> new Outcome(null, reply(state));
        case HOAN_TAT -> new Outcome(state, null);
      };
    }

    private record Outcome(JobState state, Optional<StepReply> reply) {}
  }

  /**
   * Bước 6 (TL-02a bước 5): MOD-08 đặt {@code DA_KHOA}, đóng mục tiêu, tạo hồ sơ {@code CHUA_LAP}
   * và thông báo toàn tenant; sau đó SVC-09 niêm phong Merkle để bản niêm phong phủ cả sự kiện
   * khoá. Lệnh đầu đã phát là qua điểm không quay lại, nên bước chỉ thử lại.
   */
  static final class Completion extends RemoteStep {

    Completion(PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
      super(PeriodCloseSaga.HOAN_TAT, Command.HOAN_TAT_KY, gateway, alarms, clock);
    }

    @Override
    public Optional<StepReply> poll(
        StepInvocation invocation, UUID commandEventId, Instant stepStartedAt) {
      watchBudget(invocation, stepStartedAt);
      PeriodCloseRequest request = PeriodCloseRequest.of(invocation);
      Optional<StepReply> finalized = await(command, request, commandEventId);
      if (finalized.isEmpty() || !finalized.get().success()) {
        return finalized;
      }
      return await(Command.NIEM_PHONG, request, derived(commandEventId, Command.NIEM_PHONG));
    }

    @Override
    public void compensate(StepInvocation invocation) {
      throw new IllegalStateException("Bước hoàn tất chốt kỳ không có bù trừ");
    }
  }
}
