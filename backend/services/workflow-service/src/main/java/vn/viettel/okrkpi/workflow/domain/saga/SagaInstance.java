package vn.viettel.okrkpi.workflow.domain.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Saga điều phối tầng 3 (ADR-04, TL-01 "Thiết kế ba tầng điều phối", TL-03 {@code
 * flowable.saga_instance}): máy trạng thái thuần, không biết engine hay CSDL.
 *
 * <p>Chiều tiến chạy bước {@code currentStepNo} rồi tăng dần. Khi bù trừ, {@code currentStepNo} là
 * bước đang được hoàn tác và giảm dần về 0; bước có hành động bù trừ chạy bù trừ, bước không có
 * được bỏ qua có ghi vết. Sau điểm không quay lại chỉ được thử lại, không bù trừ (TL-03 "Saga điều
 * phối chốt kỳ").
 *
 * <p>Tạm dừng không có trạng thái riêng trong TL-03: saga đang tạm dừng mang trạng thái {@code
 * CHO_SU_KIEN} (chờ lệnh tiếp tục) cùng cờ {@link #paused()} (suy luận, ghi ở wiki trang 14).
 */
public final class SagaInstance {

  private final UUID id;
  private final UUID tenantId;
  private final SagaType type;
  private final UUID correlationId;
  private final String exclusiveKey;
  private final UUID initiator;
  private final Map<String, Object> parameters;
  private final List<SagaStep> steps;
  private final Instant startedAt;
  private final long rowVersion;
  private SagaStatus status;
  private int currentStepNo;
  private boolean pointOfNoReturnPassed;
  private boolean pauseRequested;
  private boolean paused;
  private boolean compensating;
  private Instant finishedAt;

  /** Ghi chú của bộ xử lý bước (ví dụ lần chạy lại hồ sơ lỗi, chấp nhận để lại lỗi), lưu bền. */
  private final Map<String, Object> notes = new LinkedHashMap<>();

  public SagaInstance(
      UUID id,
      UUID tenantId,
      SagaType type,
      UUID correlationId,
      String exclusiveKey,
      UUID initiator,
      Map<String, Object> parameters,
      List<SagaStep> steps,
      SagaStatus status,
      int currentStepNo,
      boolean pointOfNoReturnPassed,
      boolean pauseRequested,
      boolean paused,
      boolean compensating,
      Instant startedAt,
      Instant finishedAt,
      long rowVersion) {
    this.id = Objects.requireNonNull(id, "id");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.type = Objects.requireNonNull(type, "type");
    this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    this.exclusiveKey = exclusiveKey;
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    this.steps = new ArrayList<>(steps);
    this.status = Objects.requireNonNull(status, "status");
    this.currentStepNo = currentStepNo;
    this.pointOfNoReturnPassed = pointOfNoReturnPassed;
    this.pauseRequested = pauseRequested;
    this.paused = paused;
    this.compensating = compensating;
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.finishedAt = finishedAt;
    this.rowVersion = rowVersion;
  }

  /** Saga mới: mọi bước ở {@code CHO}, bắt đầu từ bước 1. */
  public static SagaInstance start(
      UUID id,
      UUID tenantId,
      SagaDefinition definition,
      UUID correlationId,
      String exclusiveKey,
      UUID initiator,
      Map<String, Object> parameters,
      Supplier<UUID> stepIds,
      Instant now) {
    List<SagaStep> steps = new ArrayList<>();
    for (int no = 1; no <= definition.size(); no++) {
      steps.add(SagaStep.pending(stepIds.get(), no, definition.step(no)));
    }
    return new SagaInstance(
        id,
        tenantId,
        definition.type(),
        correlationId,
        exclusiveKey,
        initiator,
        parameters,
        steps,
        SagaStatus.DANG_CHAY,
        1,
        false,
        false,
        false,
        false,
        now,
        null,
        0);
  }

  /**
   * Quyết định việc kế tiếp. Lệnh tạm dừng có hiệu lực ở đây, tức là sau khi bước hiện tại đã xong.
   */
  public NextWork decideNext(Instant now) {
    if (status.isTerminal()) {
      return new NextWork.Finish();
    }
    if (status == SagaStatus.LOI_CAN_XU_LY || status == SagaStatus.CHO_SU_KIEN) {
      return new NextWork.Wait();
    }
    if (pauseRequested) {
      pauseRequested = false;
      paused = true;
      status = SagaStatus.CHO_SU_KIEN;
      return new NextWork.Wait();
    }
    if (compensating) {
      if (currentStepNo < 1) {
        finish(SagaStatus.DA_BU_TRU, now);
        return new NextWork.Finish();
      }
      SagaStep step = step(currentStepNo);
      if (step.status() == SagaStepStatus.XONG) {
        return step.compensationCode() != null
            ? new NextWork.Compensate(currentStepNo)
            : new NextWork.SkipCompensation(currentStepNo);
      }
      return new NextWork.SkipCompensation(currentStepNo);
    }
    if (currentStepNo > steps.size()) {
      finish(SagaStatus.HOAN_TAT, now);
      return new NextWork.Finish();
    }
    return new NextWork.Execute(currentStepNo);
  }

  /** Bắt đầu một lần thử của bước hiện tại (chiều tiến hoặc bù trừ). */
  public SagaStep beginAttempt(int stepNo, Instant now) {
    requireCurrent(stepNo);
    SagaStep step = step(stepNo);
    if (compensating) {
      // Bước bù trừ dùng lại bộ đếm lần thử của chính bước đó; trạng thái XONG giữ tới khi hoàn
      // tác.
      step.countCompensationAttempt(now);
    } else {
      step.begin(now);
    }
    return step;
  }

  /** Bước đã phát lệnh và chờ sự kiện trả lời. */
  public void awaitReply(int stepNo, UUID commandEventId) {
    requireCurrent(stepNo);
    step(stepNo).commandSent(commandEventId);
    status = SagaStatus.CHO_SU_KIEN;
  }

  /**
   * Lệnh của bước điểm không quay lại đã phát: từ đây không bù trừ được nữa dù bước chưa xong (ví
   * dụ kỳ đã sang {@code DA_KHOA} nhưng niêm phong chưa trả lời), chỉ thử lại hoặc chờ người vận
   * hành.
   */
  public void passPointOfNoReturn() {
    pointOfNoReturnPassed = true;
  }

  /** Bước xong; trả {@code true} khi đây là bước cuối và saga hoàn tất. */
  public boolean completeStep(
      int stepNo, UUID replyEventId, SagaDefinition definition, Instant now) {
    requireCurrent(stepNo);
    step(stepNo).complete(replyEventId, now);
    if (definition.step(stepNo).pointOfNoReturn()) {
      pointOfNoReturnPassed = true;
    }
    currentStepNo++;
    if (currentStepNo > steps.size()) {
      finish(SagaStatus.HOAN_TAT, now);
      return true;
    }
    status = SagaStatus.DANG_CHAY;
    return false;
  }

  /**
   * Ghi một lần thử thất bại của bước hiện tại và quyết định thử lại, bù trừ hay chờ người vận
   * hành.
   */
  public FailureDecision recordFailure(
      int stepNo, String error, SagaDefinition definition, Instant now) {
    requireCurrent(stepNo);
    SagaStep step = step(stepNo);
    RetryPolicy retry = definition.step(stepNo).retry();
    if (compensating) {
      step.compensationFailed(error);
      if (retry.allowsAnotherAttempt(step.attempts())) {
        status = SagaStatus.DANG_BU_TRU;
        return new FailureDecision.Retry(retry.backoffAfter(step.attempts()));
      }
      status = SagaStatus.LOI_CAN_XU_LY;
      return new FailureDecision.NeedsOperator();
    }
    step.fail(error, now);
    if (retry.allowsAnotherAttempt(step.attempts())) {
      status = SagaStatus.DANG_CHAY;
      return new FailureDecision.Retry(retry.backoffAfter(step.attempts()));
    }
    if (!pointOfNoReturnPassed && definition.compensateOnExhaustion()) {
      beginCompensation(stepNo - 1);
      return new FailureDecision.StartCompensation();
    }
    status = SagaStatus.LOI_CAN_XU_LY;
    return new FailureDecision.NeedsOperator();
  }

  /**
   * Bước hiện tại bị từ chối theo nghiệp vụ: không thử lại. Trước điểm không quay lại thì bù trừ
   * các bước đã xong (dù saga không bật bù trừ khi hết lần thử), sau đó thì chờ người vận hành.
   */
  public FailureDecision rejectStep(int stepNo, String error, Instant now) {
    requireCurrent(stepNo);
    step(stepNo).fail(error, now);
    if (!pointOfNoReturnPassed) {
      beginCompensation(stepNo - 1);
      return new FailureDecision.StartCompensation();
    }
    status = SagaStatus.LOI_CAN_XU_LY;
    return new FailureDecision.NeedsOperator();
  }

  /**
   * Bước đang chờ cần người vận hành quyết định (ví dụ còn hồ sơ tính lỗi, kiểm tất định lệch):
   * bước giữ lệnh đang chờ, saga chuyển {@code LOI_CAN_XU_LY}; {@link #resumeAwaiting} đưa về chờ.
   */
  public void holdForOperator(int stepNo, String reason) {
    requireCurrent(stepNo);
    step(stepNo).compensationFailed(reason);
    status = SagaStatus.LOI_CAN_XU_LY;
  }

  /** Bước đang giữ lệnh quay lại chờ trả lời sau khi người vận hành đã xử lý. */
  public void resumeAwaiting(int stepNo, Instant now) {
    requireActive();
    requireCurrent(stepNo);
    if (compensating
        || step(stepNo).status() != SagaStepStatus.DANG_CHAY
        || step(stepNo).commandEventId() == null) {
      throw new SagaRuleViolation(
          SagaRuleViolation.Reason.KHONG_O_TRANG_THAI_LOI,
          "Bước " + stepNo + " của saga " + id + " không đang chờ kết quả");
    }
    // Thời hạn chờ trả lời tính lại từ lúc người vận hành xử lý.
    step(stepNo).restartWaiting(now);
    status = SagaStatus.CHO_SU_KIEN;
  }

  public Map<String, Object> notes() {
    return Collections.unmodifiableMap(notes);
  }

  public void putNote(String key, Object value) {
    notes.put(Objects.requireNonNull(key, "key"), value);
  }

  /** Nạp lại ghi chú đã lưu (kho bền). */
  public void restoreNotes(Map<String, Object> stored) {
    notes.clear();
    if (stored != null) {
      notes.putAll(stored);
    }
  }

  /** Bù trừ của bước hiện tại đã xong; lùi về bước trước. */
  public boolean compensated(int stepNo, Instant now) {
    requireCurrent(stepNo);
    step(stepNo).compensated(now);
    return stepBack(now);
  }

  /** Bước hiện tại không có bù trừ hoặc chưa từng chạy xong: bỏ qua và lùi về bước trước. */
  public boolean skipCompensation(int stepNo, Instant now) {
    requireCurrent(stepNo);
    return stepBack(now);
  }

  /** Sự kiện trả lời của bước đang chờ có tới hạn chưa nhận được không. */
  public boolean isReplyOverdue(SagaDefinition definition, Instant now) {
    if (status != SagaStatus.CHO_SU_KIEN || paused || currentStepNo < 1) {
      return false;
    }
    if (currentStepNo > steps.size()) {
      return false;
    }
    SagaStep step = step(currentStepNo);
    if (step.startedAt() == null || step.status() != SagaStepStatus.DANG_CHAY) {
      return false;
    }
    return step.startedAt().plus(definition.step(currentStepNo).replyTimeout()).isBefore(now);
  }

  // --- Lệnh điều khiển ---------------------------------------------------------------------

  /** Tạm dừng sau bước hiện tại. */
  public void requestPause() {
    requireActive();
    if (!paused) {
      pauseRequested = true;
    }
  }

  /** Tiếp tục saga đã tạm dừng (hoặc huỷ yêu cầu tạm dừng chưa có hiệu lực). */
  public void resume() {
    requireActive();
    if (pauseRequested) {
      pauseRequested = false;
      return;
    }
    if (!paused) {
      throw new SagaRuleViolation(
          SagaRuleViolation.Reason.KHONG_TAM_DUNG, "Saga " + id + " không ở trạng thái tạm dừng");
    }
    paused = false;
    status = compensating ? SagaStatus.DANG_BU_TRU : SagaStatus.DANG_CHAY;
  }

  /** Thử lại bước lỗi khi saga đang chờ người vận hành; bộ đếm lần thử của bước về 0. */
  public void retryFailedStep() {
    requireActive();
    if (status != SagaStatus.LOI_CAN_XU_LY) {
      throw new SagaRuleViolation(
          SagaRuleViolation.Reason.KHONG_O_TRANG_THAI_LOI,
          "Saga " + id + " không ở trạng thái lỗi cần xử lý");
    }
    SagaStep step = step(currentStepNo);
    if (compensating) {
      step.resetCompensationAttempts();
      status = SagaStatus.DANG_BU_TRU;
    } else {
      step.resetForRetry();
      status = SagaStatus.DANG_CHAY;
    }
  }

  /** Yêu cầu bù trừ toàn bộ; chỉ được khi chưa qua điểm không quay lại. */
  public void requestCompensation() {
    requireActive();
    if (pointOfNoReturnPassed) {
      throw new SagaRuleViolation(
          SagaRuleViolation.Reason.DA_QUA_DIEM_KHONG_QUAY_LAI,
          "Saga " + id + " đã qua điểm không quay lại, chỉ được thử lại");
    }
    if (compensating) {
      return;
    }
    int lastDone = Math.min(currentStepNo, steps.size());
    if (lastDone >= 1 && step(lastDone).status() != SagaStepStatus.XONG) {
      if (isInFlight(step(lastDone))) {
        // Lệnh của bước đã phát mà chưa xong (ví dụ yêu cầu ký đang chờ): hoàn tác cả bước này.
        step(lastDone).markInFlightForCompensation();
      } else {
        step(lastDone).resetPending();
        lastDone--;
      }
    }
    beginCompensation(lastDone);
  }

  /**
   * Sự kiện trả lời của bước đang chờ. Trả lời trùng, trả lời cho bước khác hoặc tới khi saga đã
   * chuyển sang bù trừ đều bị bỏ qua (không đổi trạng thái).
   */
  public boolean acceptsReplyFor(int stepNo) {
    return !compensating
        && !status.isTerminal()
        && stepNo == currentStepNo
        && stepNo <= steps.size()
        && step(stepNo).status() == SagaStepStatus.DANG_CHAY
        && step(stepNo).commandEventId() != null;
  }

  private static boolean isInFlight(SagaStep step) {
    return step.commandEventId() != null
        && step.compensationCode() != null
        && (step.status() == SagaStepStatus.DANG_CHAY || step.status() == SagaStepStatus.LOI);
  }

  private void beginCompensation(int fromStepNo) {
    compensating = true;
    paused = false;
    pauseRequested = false;
    currentStepNo = fromStepNo;
    status = SagaStatus.DANG_BU_TRU;
    prepareCurrentForCompensation();
  }

  private void prepareCurrentForCompensation() {
    if (currentStepNo >= 1 && step(currentStepNo).status() == SagaStepStatus.XONG) {
      step(currentStepNo).prepareCompensation();
    }
  }

  private boolean stepBack(Instant now) {
    currentStepNo--;
    if (currentStepNo < 1) {
      finish(SagaStatus.DA_BU_TRU, now);
      return true;
    }
    status = SagaStatus.DANG_BU_TRU;
    prepareCurrentForCompensation();
    return false;
  }

  private void finish(SagaStatus terminal, Instant now) {
    status = terminal;
    finishedAt = now;
    paused = false;
    pauseRequested = false;
  }

  private void requireActive() {
    if (status.isTerminal()) {
      throw new SagaRuleViolation(
          SagaRuleViolation.Reason.DA_KET_THUC, "Saga " + id + " đã kết thúc (" + status + ")");
    }
  }

  private void requireCurrent(int stepNo) {
    if (stepNo != currentStepNo) {
      throw new IllegalStateException(
          "Bước " + stepNo + " không phải bước hiện tại " + currentStepNo + " của saga " + id);
    }
  }

  public SagaStep step(int stepNo) {
    if (stepNo < 1 || stepNo > steps.size()) {
      throw new IllegalArgumentException("Không có bước " + stepNo);
    }
    return steps.get(stepNo - 1);
  }

  /** Số bước đã xong theo chiều tiến, để tính phần trăm tiến độ. */
  public int completedSteps() {
    return (int) steps.stream().filter(s -> s.status() == SagaStepStatus.XONG).count();
  }

  public UUID id() {
    return id;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public SagaType type() {
    return type;
  }

  public UUID correlationId() {
    return correlationId;
  }

  public String exclusiveKey() {
    return exclusiveKey;
  }

  public UUID initiator() {
    return initiator;
  }

  public Map<String, Object> parameters() {
    return parameters;
  }

  public List<SagaStep> steps() {
    return Collections.unmodifiableList(steps);
  }

  public SagaStatus status() {
    return status;
  }

  public int currentStepNo() {
    return currentStepNo;
  }

  public boolean pointOfNoReturnPassed() {
    return pointOfNoReturnPassed;
  }

  public boolean pauseRequested() {
    return pauseRequested;
  }

  public boolean paused() {
    return paused;
  }

  public boolean compensating() {
    return compensating;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant finishedAt() {
    return finishedAt;
  }

  public long rowVersion() {
    return rowVersion;
  }
}
