package vn.viettel.okrkpi.workflow.application.saga;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.workflow.application.saga.SagaJournal.Action;
import vn.viettel.okrkpi.workflow.domain.saga.FailureDecision;
import vn.viettel.okrkpi.workflow.domain.saga.NextWork;
import vn.viettel.okrkpi.workflow.domain.saga.SagaDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaRepository;
import vn.viettel.okrkpi.workflow.domain.saga.SagaRuleViolation;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStep;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Khung saga tầng 3 (DV-06-05, ADR-04, TL-01 "Thiết kế ba tầng điều phối"): khởi động idempotent,
 * chạy từng lượt do engine giao, nhận trả lời, điều khiển và quét quá hạn.
 *
 * <p>Mọi phương thức chạy trong một giao dịch đã có ngữ cảnh tenant: trạng thái {@code saga_step},
 * bản ghi sổ cái trong outbox và lệnh tới engine (hoàn thành lượt, đánh thức) cùng commit hoặc cùng
 * huỷ. Vì vậy saga chạy tiếp đúng bước sau khi pod chết: lượt chưa commit được engine giao lại khi
 * khoá hết hạn, lượt đã commit thì bước đã ghi xong.
 */
public class SagaService {

  private static final Logger LOG = LoggerFactory.getLogger(SagaService.class);

  static final String SAGA_EXISTS = "WORKFLOW-409-SAGA-EXISTS";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String VERSION_CONFLICT = "WORKFLOW-409-VERSION-CONFLICT";

  private final SagaRepository sagas;
  private final SagaCatalog catalog;
  private final SagaEngine engine;
  private final SagaJournal journal;
  private final Clock clock;

  public SagaService(
      SagaRepository sagas,
      SagaCatalog catalog,
      SagaEngine engine,
      SagaJournal journal,
      Clock clock) {
    this.sagas = Objects.requireNonNull(sagas, "sagas");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Lệnh khởi động saga. */
  public record StartCommand(
      UUID tenantId,
      SagaType type,
      UUID correlationId,
      Map<String, Object> parameters,
      UUID initiator) {}

  /** Kết quả khởi động: saga đã có hay vừa tạo. */
  public record StartResult(SagaInstance saga, boolean created) {}

  /**
   * Khởi động idempotent theo ({@code tenant_id}, {@code saga_type}, {@code correlation_id}): gọi
   * trùng trả lại saga cũ; saga khác cùng loại, cùng khoá độc quyền đang chạy hoặc đã hoàn tất trả
   * 409 {@code WORKFLOW-409-SAGA-EXISTS}. Chỉ ghi dòng và tạo tiến trình, không chạy bước nào, nên
   * phản hồi nhanh (NFR-03).
   */
  @Transactional
  public StartResult start(StartCommand command) {
    SagaBlueprint blueprint =
        catalog
            .blueprint(command.type())
            .orElseThrow(
                () ->
                    new BusinessException(
                        VALIDATION, "Loại quy trình nền này chưa được cấu hình trên hệ thống."));
    Optional<SagaInstance> existing =
        sagas.findByCorrelation(command.type(), command.correlationId());
    if (existing.isPresent()) {
      return new StartResult(existing.get(), false);
    }
    String exclusiveKey = blueprint.exclusiveKey(command.parameters());
    if (exclusiveKey != null) {
      sagas.lockExclusiveKey(command.type(), exclusiveKey);
      Optional<SagaInstance> raced =
          sagas.findByCorrelation(command.type(), command.correlationId());
      if (raced.isPresent()) {
        return new StartResult(raced.get(), false);
      }
      if (sagas.existsBlockingFor(command.type(), exclusiveKey)) {
        throw new BusinessException(
            SAGA_EXISTS, "Đối tượng này đã có quy trình đang chạy hoặc đã hoàn tất.");
      }
    }
    SagaInstance saga =
        SagaInstance.start(
            Ids.newUuidV7(),
            command.tenantId(),
            blueprint.definition(),
            command.correlationId(),
            exclusiveKey,
            command.initiator(),
            command.parameters(),
            Ids::newUuidV7,
            now());
    sagas.insert(saga);
    engine.launch(saga.id(), saga.tenantId(), businessKey(saga.id()));
    journal.record(saga, Action.KHOI_DONG, null, true);
    return new StartResult(saga, true);
  }

  public static String businessKey(UUID sagaId) {
    return "SAGA:" + sagaId;
  }

  @Transactional(readOnly = true)
  public Optional<SagaInstance> find(UUID sagaId) {
    return sagas.findById(sagaId);
  }

  /**
   * Chạy một lượt do engine giao: làm đúng một việc kế tiếp (bước, bù trừ hoặc bỏ qua bù trừ) rồi
   * hoàn thành lượt trong cùng giao dịch. Ngoại lệ từ bộ xử lý làm huỷ cả giao dịch; worker gọi
   * {@link #recordTurnFailure} trong giao dịch mới.
   */
  @Transactional
  public SagaEngine.Next runTurn(UUID sagaId, String turnId) {
    SagaInstance saga = require(sagaId);
    SagaDefinition definition = catalog.definition(saga.type());
    Instant now = now();
    NextWork work = saga.decideNext(now);
    switch (work) {
      case NextWork.Execute execute -> execute(saga, definition, execute.stepNo(), now);
      case NextWork.Compensate compensate -> compensate(saga, compensate.stepNo(), now);
      case NextWork.SkipCompensation skip -> {
        saga.skipCompensation(skip.stepNo(), now);
        journal.record(saga, Action.BO_QUA_BU_TRU, skip.stepNo(), true);
      }
      case NextWork.Wait w -> {
        if (saga.paused()) {
          journal.record(saga, Action.TAM_DUNG, saga.currentStepNo(), true);
        }
      }
      case NextWork.Finish f -> {
        // Saga đã kết thúc; lượt này chỉ đóng tiến trình.
      }
    }
    finishIfTerminal(saga);
    sagas.update(saga);
    SagaEngine.Next next = nextFor(saga);
    engine.completeTurn(turnId, next);
    return next;
  }

  /**
   * Ghi lần thử thất bại của lượt vừa huỷ và quyết định: thử lại sau thời gian lùi, bắt đầu bù trừ
   * hoặc chờ người vận hành.
   */
  @Transactional
  public FailureDecision recordTurnFailure(UUID sagaId, String turnId, String error) {
    SagaInstance saga = require(sagaId);
    SagaDefinition definition = catalog.definition(saga.type());
    Instant now = now();
    NextWork work = saga.decideNext(now);
    int stepNo =
        switch (work) {
          case NextWork.Execute e -> e.stepNo();
          case NextWork.Compensate c -> c.stepNo();
          default -> -1;
        };
    if (stepNo < 0) {
      // Lỗi không gắn với bước (ví dụ engine), giao lại lượt theo chính sách mặc định.
      sagas.update(saga);
      engine.failTurn(turnId, error, definition.step(1).retry().initialBackoff());
      return new FailureDecision.Retry(definition.step(1).retry().initialBackoff());
    }
    saga.beginAttempt(stepNo, now);
    FailureDecision decision = saga.recordFailure(stepNo, error, definition, now);
    journal.record(saga, Action.BUOC_LOI, stepNo, false);
    applyDecision(saga, decision, stepNo);
    sagas.update(saga);
    if (decision instanceof FailureDecision.Retry retry) {
      engine.failTurn(turnId, error, retry.backoff());
    } else {
      engine.completeTurn(turnId, nextFor(saga));
    }
    return decision;
  }

  /** Giao lại lượt sau {@code backoff} mà không tính lần thử (xung đột ghi đồng thời). */
  @Transactional
  public void releaseTurn(String turnId, java.time.Duration backoff) {
    engine.failTurn(turnId, "Saga vừa được cập nhật bởi lệnh khác", backoff);
  }

  /**
   * Sự kiện trả lời của một bước (consumer của từng saga gọi sau khi đã khử trùng qua inbox). Trả
   * lời trùng hoặc lạc bước bị bỏ qua.
   */
  @Transactional
  public boolean onReply(
      UUID sagaId, int stepNo, UUID replyEventId, boolean success, String error) {
    SagaInstance saga = require(sagaId);
    if (!saga.acceptsReplyFor(stepNo)) {
      LOG.info("Bỏ qua trả lời {} cho bước {} của saga {}", replyEventId, stepNo, sagaId);
      return false;
    }
    wakeUnlessBackingOff(saga, applyReply(saga, stepNo, replyEventId, success, error));
    return true;
  }

  /** Ghi trả lời; trả quyết định khi trả lời là lỗi, {@code null} khi bước xong. */
  private FailureDecision applyReply(
      SagaInstance saga, int stepNo, UUID replyEventId, boolean success, String error) {
    SagaDefinition definition = catalog.definition(saga.type());
    Instant now = now();
    FailureDecision decision = null;
    if (success) {
      saga.completeStep(stepNo, replyEventId, definition, now);
      journal.record(saga, Action.NHAN_TRA_LOI, stepNo, true);
    } else {
      decision = saga.recordFailure(stepNo, error, definition, now);
      journal.record(saga, Action.NHAN_TRA_LOI, stepNo, false);
      applyDecision(saga, decision, stepNo);
    }
    finishIfTerminal(saga);
    sagas.update(saga);
    return decision;
  }

  /**
   * Trả lời lỗi được thử lại sau thời gian lùi của bước: không đánh thức ngay mà để lượt đối chiếu
   * đánh thức khi tới hạn ({@link #retryDue}), nên chính sách lùi dần áp dụng cả cho lệnh chờ trả
   * lời (ký số lô lùi dần tới 4 giờ, DV-06-06).
   */
  private void wakeUnlessBackingOff(SagaInstance saga, FailureDecision decision) {
    if (decision instanceof FailureDecision.Retry retry && retry.backoff().isPositive()) {
      return;
    }
    engine.wake(saga.id());
  }

  private static boolean retryDue(SagaInstance saga, SagaDefinition definition, Instant now) {
    int stepNo = saga.currentStepNo();
    if (saga.status() != SagaStatus.DANG_CHAY
        || saga.compensating()
        || stepNo < 1
        || stepNo > saga.steps().size()) {
      return true;
    }
    SagaStep step = saga.step(stepNo);
    if (step.status() != SagaStepStatus.LOI || step.finishedAt() == null) {
      return true;
    }
    Instant due =
        step.finishedAt().plus(definition.step(stepNo).retry().backoffAfter(step.attempts()));
    return !now.isBefore(due);
  }

  /**
   * Đối chiếu một saga mà engine báo đang ở nút chờ: thăm dò kết quả lệnh của bước đang chờ khi bộ
   * xử lý hỗ trợ, đánh thức khi saga đã có việc (trả lời tới trước khi tiến trình vào nút chờ, lệnh
   * tiếp tục), hoặc tính quá hạn khi chờ trả lời quá thời hạn của bước.
   */
  @Transactional
  public void reconcileWaiting(UUID sagaId) {
    SagaInstance saga = require(sagaId);
    SagaDefinition definition = catalog.definition(saga.type());
    Instant now = now();
    Optional<SagaStepHandler.StepReply> polled = poll(saga);
    if (polled.isPresent() && polled.get().hold()) {
      int stepNo = saga.currentStepNo();
      saga.holdForOperator(stepNo, polled.get().error());
      journal.record(saga, Action.CHO_XU_LY, stepNo, false);
      sagas.update(saga);
      return;
    }
    if (polled.isPresent()) {
      SagaStep step = saga.step(saga.currentStepNo());
      wakeUnlessBackingOff(
          saga,
          applyReply(
              saga,
              step.stepNo(),
              step.commandEventId(),
              polled.get().success(),
              polled.get().error()));
      return;
    }
    if (saga.isReplyOverdue(definition, now)) {
      int stepNo = saga.currentStepNo();
      FailureDecision decision =
          saga.recordFailure(stepNo, "Quá thời hạn chờ trả lời", definition, now);
      journal.record(saga, Action.QUA_HAN_TRA_LOI, stepNo, false);
      applyDecision(saga, decision, stepNo);
      sagas.update(saga);
    }
    if ((nextFor(saga) != SagaEngine.Next.CHO || saga.status().isTerminal())
        && retryDue(saga, definition, now)) {
      engine.wake(sagaId);
    }
  }

  // --- Lệnh điều khiển ---------------------------------------------------------------------

  /** Điều khiển của người vận hành. */
  public enum Control {
    TAM_DUNG,
    TIEP_TUC,
    THU_LAI,
    BU_TRU
  }

  @Transactional
  public SagaInstance control(UUID sagaId, Control control) {
    SagaInstance saga = require(sagaId);
    try {
      switch (control) {
        case TAM_DUNG -> saga.requestPause();
        case TIEP_TUC -> saga.resume();
        case THU_LAI -> saga.retryFailedStep();
        case BU_TRU -> saga.requestCompensation();
      }
    } catch (SagaRuleViolation e) {
      throw violation(saga, e);
    }
    Action action =
        switch (control) {
          case TAM_DUNG -> Action.TAM_DUNG;
          case TIEP_TUC -> Action.TIEP_TUC;
          case THU_LAI -> Action.THU_LAI;
          case BU_TRU -> Action.YEU_CAU_BU_TRU;
        };
    journal.record(saga, action, saga.currentStepNo(), true);
    sagas.update(saga);
    if (control != Control.TAM_DUNG) {
      engine.wake(sagaId);
    }
    return saga;
  }

  /**
   * Lệnh nội bộ của từng loại saga cho bước đang chờ: ghi chú bền cho bộ xử lý bước rồi đưa bước
   * đang giữ chờ người vận hành quay lại chờ kết quả (ví dụ chạy lại hồ sơ lỗi, chấp nhận để lại
   * lỗi của bước tính điểm chốt kỳ, DV-06-07).
   */
  @Transactional
  public SagaInstance annotateWaitingStep(
      UUID sagaId, String stepCode, Action action, Map<String, Object> notes) {
    SagaInstance saga = require(sagaId);
    int stepNo = saga.currentStepNo();
    if (saga.status().isTerminal()
        || stepNo < 1
        || stepNo > saga.steps().size()
        || !saga.step(stepNo).stepCode().equals(stepCode)) {
      throw new BusinessException(
          VERSION_CONFLICT, "Quy trình không ở bước cho phép thao tác này.");
    }
    notes.forEach(saga::putNote);
    try {
      if (saga.status() == SagaStatus.LOI_CAN_XU_LY) {
        saga.resumeAwaiting(stepNo, now());
      } else if (saga.status() != SagaStatus.CHO_SU_KIEN) {
        throw new SagaRuleViolation(
            SagaRuleViolation.Reason.KHONG_O_TRANG_THAI_LOI,
            "Bước " + stepNo + " của saga " + sagaId + " không đang chờ kết quả");
      }
    } catch (SagaRuleViolation e) {
      throw violation(saga, e);
    }
    journal.record(saga, action, stepNo, true);
    sagas.update(saga);
    return saga;
  }

  /** Mã lỗi riêng của loại saga cho vi phạm luật điều khiển, mặc định xung đột phiên bản. */
  private BusinessException violation(SagaInstance saga, SagaRuleViolation e) {
    String code =
        e.reason() == SagaRuleViolation.Reason.DA_QUA_DIEM_KHONG_QUAY_LAI
            ? catalog.blueprint(saga.type()).map(b -> b.pointOfNoReturnCode()).orElse(null)
            : null;
    if (code != null) {
      return new BusinessException(
          code,
          "Quy trình đã qua bước không thể hoàn tác, chỉ có thể mở lại theo quy trình hậu kiểm.");
    }
    return new BusinessException(VERSION_CONFLICT, e.getMessage());
  }

  // --- Nội bộ ------------------------------------------------------------------------------

  private void execute(SagaInstance saga, SagaDefinition definition, int stepNo, Instant now) {
    SagaStep step = saga.beginAttempt(stepNo, now);
    journal.record(saga, Action.BAT_DAU_BUOC, stepNo, true);
    StepResult result =
        catalog.handler(saga.type(), step.stepCode()).execute(invocation(saga, step, false));
    switch (result) {
      case StepResult.Completed c -> {
        saga.completeStep(stepNo, null, definition, now);
        journal.record(saga, Action.BUOC_XONG, stepNo, true);
      }
      case StepResult.AwaitingReply awaiting -> {
        saga.awaitReply(stepNo, awaiting.commandEventId());
        if (definition.step(stepNo).pointOfNoReturn()
            && definition.step(stepNo).compensationCode() == null) {
          // Bước điểm không quay lại không có bù trừ: lệnh đã phát là không hoàn tác được nữa.
          saga.passPointOfNoReturn();
        }
        journal.record(saga, Action.CHO_TRA_LOI, stepNo, true);
      }
      case StepResult.Rejected rejected -> {
        FailureDecision decision = saga.rejectStep(stepNo, rejected.error(), now);
        journal.record(saga, Action.BUOC_LOI, stepNo, false);
        applyDecision(saga, decision, stepNo);
      }
    }
  }

  private Optional<SagaStepHandler.StepReply> poll(SagaInstance saga) {
    int stepNo = saga.currentStepNo();
    if (saga.status() != SagaStatus.CHO_SU_KIEN || !saga.acceptsReplyFor(stepNo)) {
      return Optional.empty();
    }
    SagaStep step = saga.step(stepNo);
    return catalog
        .handler(saga.type(), step.stepCode())
        .poll(invocation(saga, step, false), step.commandEventId(), step.startedAt());
  }

  private void compensate(SagaInstance saga, int stepNo, Instant now) {
    SagaStep step = saga.beginAttempt(stepNo, now);
    catalog.handler(saga.type(), step.stepCode()).compensate(invocation(saga, step, true));
    saga.compensated(stepNo, now);
    journal.record(saga, Action.BU_TRU_XONG, stepNo, true);
  }

  private void applyDecision(SagaInstance saga, FailureDecision decision, int stepNo) {
    switch (decision) {
      case FailureDecision.StartCompensation s ->
          journal.record(saga, Action.BAT_DAU_BU_TRU, stepNo, true);
      case FailureDecision.NeedsOperator n -> journal.record(saga, Action.CHO_XU_LY, stepNo, false);
      case FailureDecision.Retry r -> {
        // Thử lại do engine giao lại lượt hoặc do quét đối chiếu đánh thức.
      }
    }
  }

  private void finishIfTerminal(SagaInstance saga) {
    if (saga.status() == SagaStatus.HOAN_TAT && saga.finishedAt() != null) {
      journal.record(saga, Action.HOAN_TAT, null, true);
    } else if (saga.status() == SagaStatus.DA_BU_TRU && saga.finishedAt() != null) {
      journal.record(saga, Action.DA_BU_TRU, null, true);
    }
  }

  private static SagaEngine.Next nextFor(SagaInstance saga) {
    if (saga.status().isTerminal()) {
      return SagaEngine.Next.XONG;
    }
    if (saga.status() == SagaStatus.CHO_SU_KIEN || saga.status() == SagaStatus.LOI_CAN_XU_LY) {
      return SagaEngine.Next.CHO;
    }
    return SagaEngine.Next.TIEP;
  }

  private static StepInvocation invocation(SagaInstance saga, SagaStep step, boolean compensation) {
    return new StepInvocation(
        saga.id(),
        saga.tenantId(),
        saga.type(),
        saga.correlationId(),
        step.stepNo(),
        step.stepCode(),
        compensation ? step.compensationCode() : null,
        step.attempts(),
        saga.parameters(),
        saga.notes());
  }

  private SagaInstance require(UUID sagaId) {
    return sagas
        .findById(sagaId)
        .orElseThrow(() -> new IllegalStateException("Không tìm thấy saga " + sagaId));
  }

  private Instant now() {
    return clock.instant();
  }
}
