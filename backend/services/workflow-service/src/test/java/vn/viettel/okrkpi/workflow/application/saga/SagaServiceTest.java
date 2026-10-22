package vn.viettel.okrkpi.workflow.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.workflow.application.saga.SagaEngine.Next;
import vn.viettel.okrkpi.workflow.domain.saga.FailureDecision;
import vn.viettel.okrkpi.workflow.domain.saga.RetryPolicy;
import vn.viettel.okrkpi.workflow.domain.saga.SagaDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaRepository;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStep;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/** Khung saga DV-06-05 ở tầng ứng dụng, với kho, engine và sổ ghi giả lập. */
class SagaServiceTest {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-000000000001");
  private static final UUID ACTOR = UUID.fromString("0192f1a2-0000-7000-8000-000000000002");
  private static final RetryPolicy TWO_TRIES =
      new RetryPolicy(2, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10));

  private final MemoryRepository repo = new MemoryRepository();
  private final RecordingEngine engine = new RecordingEngine();
  private final List<String> journal = new ArrayList<>();
  private final Map<String, Behaviour> behaviour = new HashMap<>();
  private final List<String> calls = new ArrayList<>();
  private SagaService service;
  private boolean pnrOnStep2;

  enum Behaviour {
    OK,
    FAIL,
    AWAIT
  }

  @BeforeEach
  void setUp() {
    pnrOnStep2 = false;
    rebuild();
  }

  private void rebuild() {
    SagaBlueprint blueprint =
        new SagaBlueprint() {
          @Override
          public SagaDefinition definition() {
            return new SagaDefinition(
                SagaType.DONG_GOI_HO_SO,
                List.of(
                    step("A", "HOAN_A", false, null),
                    step("B", null, pnrOnStep2, "B_XONG"),
                    step("C", null, false, null)),
                true);
          }

          @Override
          public String exclusiveKey(Map<String, Object> parameters) {
            return (String) parameters.get("khoa");
          }
        };
    List<SagaStepHandler> handlers = List.of(handler("A"), handler("B"), handler("C"));
    SagaCatalog catalog = new SagaCatalog(List.of(blueprint), handlers);
    SagaJournal recording =
        (saga, action, stepNo, success) -> journal.add(action + ":" + stepNo + ":" + success);
    service =
        new SagaService(
            repo,
            catalog,
            engine,
            recording,
            Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));
  }

  private static SagaStepDefinition step(
      String code, String compensation, boolean pnr, String reply) {
    return new SagaStepDefinition(
        code, "LENH_" + code, reply, Duration.ofMinutes(5), TWO_TRIES, compensation, pnr);
  }

  private SagaStepHandler handler(String code) {
    return new SagaStepHandler() {
      @Override
      public SagaType sagaType() {
        return SagaType.DONG_GOI_HO_SO;
      }

      @Override
      public String stepCode() {
        return code;
      }

      @Override
      public StepResult execute(StepInvocation invocation) {
        calls.add("exec:" + code + ":" + invocation.attempt());
        return switch (behaviour.getOrDefault(code, Behaviour.OK)) {
          case OK -> StepResult.completed();
          case AWAIT -> StepResult.awaiting(UUID.randomUUID());
          case FAIL -> throw new IllegalStateException("hỏng " + code);
        };
      }

      @Override
      public void compensate(StepInvocation invocation) {
        calls.add("comp:" + code);
      }
    };
  }

  private SagaInstance start(UUID correlation, String key) {
    return service
        .start(
            new SagaService.StartCommand(
                TENANT, SagaType.DONG_GOI_HO_SO, correlation, Map.of("khoa", key), ACTOR))
        .saga();
  }

  /** Chạy lượt như worker: lỗi thì huỷ thay đổi (nạp lại) và ghi lần thử thất bại. */
  private Next turn(UUID sagaId) {
    repo.snapshot();
    try {
      return service.runTurn(sagaId, "turn");
    } catch (IllegalStateException e) {
      repo.rollback();
      FailureDecision decision = service.recordTurnFailure(sagaId, "turn", e.getMessage());
      return decision instanceof FailureDecision.Retry ? Next.TIEP : engine.lastNext;
    }
  }

  @Test
  void runsAllStepsToCompletionAndClosesProcess() {
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    assertThat(engine.launched).containsExactly(saga.id());
    assertThat(turn(saga.id())).isEqualTo(Next.TIEP);
    behaviour.put("B", Behaviour.OK);
    assertThat(turn(saga.id())).isEqualTo(Next.TIEP);
    assertThat(turn(saga.id())).isEqualTo(Next.XONG);
    SagaInstance done = repo.get(saga.id());
    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(done.completedSteps()).isEqualTo(3);
    assertThat(calls).containsExactly("exec:A:1", "exec:B:1", "exec:C:1");
    assertThat(journal).contains("KHOI_DONG:null:true").anyMatch(e -> e.startsWith("HOAN_TAT"));
  }

  @Test
  void duplicateCorrelationReturnsExistingSaga() {
    UUID correlation = UUID.randomUUID();
    SagaInstance first = start(correlation, "k1");
    SagaService.StartResult again =
        service.start(
            new SagaService.StartCommand(
                TENANT, SagaType.DONG_GOI_HO_SO, correlation, Map.of("khoa", "k1"), ACTOR));
    assertThat(again.created()).isFalse();
    assertThat(again.saga().id()).isEqualTo(first.id());
    assertThat(engine.launched).hasSize(1);
  }

  @Test
  void otherSagaOnSameExclusiveKeyIsRejected() {
    start(UUID.randomUUID(), "k1");
    assertThatThrownBy(() -> start(UUID.randomUUID(), "k1")).isInstanceOf(BusinessException.class);
    assertThat(start(UUID.randomUUID(), "k2")).isNotNull();
  }

  @Test
  void unknownSagaTypeIsValidationError() {
    assertThatThrownBy(
            () ->
                service.start(
                    new SagaService.StartCommand(
                        TENANT, SagaType.CHOT_KY, UUID.randomUUID(), Map.of(), ACTOR)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void failingStepRetriesThenCompensatesCompletedStepsInReverse() {
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    turn(saga.id());
    behaviour.put("B", Behaviour.FAIL);
    assertThat(turn(saga.id())).isEqualTo(Next.TIEP);
    assertThat(engine.failures).hasSize(1);
    assertThat(repo.get(saga.id()).step(2).attempts()).isEqualTo(1);
    turn(saga.id());
    SagaInstance compensating = repo.get(saga.id());
    assertThat(compensating.status()).isEqualTo(SagaStatus.DANG_BU_TRU);
    assertThat(compensating.step(2).status()).isEqualTo(SagaStepStatus.LOI);
    turn(saga.id());
    assertThat(turn(saga.id())).isEqualTo(Next.XONG);
    SagaInstance undone = repo.get(saga.id());
    assertThat(undone.status()).isEqualTo(SagaStatus.DA_BU_TRU);
    assertThat(undone.step(1).status()).isEqualTo(SagaStepStatus.DA_BU_TRU);
    assertThat(calls).containsExactly("exec:A:1", "exec:B:1", "exec:B:2", "comp:A");
    assertThat(journal).contains("BAT_DAU_BU_TRU:2:true", "BU_TRU_XONG:1:true");
  }

  @Test
  void failureAfterPointOfNoReturnWaitsForOperatorAndCanBeRetried() {
    pnrOnStep2 = true;
    rebuild();
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    turn(saga.id());
    turn(saga.id());
    behaviour.put("C", Behaviour.FAIL);
    turn(saga.id());
    turn(saga.id());
    assertThat(repo.get(saga.id()).status()).isEqualTo(SagaStatus.LOI_CAN_XU_LY);
    assertThat(engine.lastNext).isEqualTo(Next.CHO);
    behaviour.put("C", Behaviour.OK);
    service.control(saga.id(), SagaService.Control.THU_LAI);
    assertThat(engine.woken).contains(saga.id());
    assertThat(turn(saga.id())).isEqualTo(Next.XONG);
    assertThat(repo.get(saga.id()).status()).isEqualTo(SagaStatus.HOAN_TAT);
  }

  @Test
  void stepAwaitingReplyContinuesOnReplyAndIgnoresDuplicates() {
    behaviour.put("B", Behaviour.AWAIT);
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    turn(saga.id());
    assertThat(turn(saga.id())).isEqualTo(Next.CHO);
    assertThat(repo.get(saga.id()).status()).isEqualTo(SagaStatus.CHO_SU_KIEN);
    UUID reply = UUID.randomUUID();
    assertThat(service.onReply(saga.id(), 2, reply, true, null)).isTrue();
    assertThat(service.onReply(saga.id(), 2, reply, true, null)).isFalse();
    assertThat(repo.get(saga.id()).step(2).replyEventId()).isEqualTo(reply);
    assertThat(turn(saga.id())).isEqualTo(Next.XONG);
  }

  @Test
  void negativeReplyCountsAsFailedAttempt() {
    behaviour.put("B", Behaviour.AWAIT);
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    turn(saga.id());
    turn(saga.id());
    service.onReply(saga.id(), 2, UUID.randomUUID(), false, "từ chối");
    SagaInstance after = repo.get(saga.id());
    assertThat(after.step(2).status()).isEqualTo(SagaStepStatus.LOI);
    assertThat(after.step(2).lastError()).isEqualTo("từ chối");
    assertThat(after.status()).isEqualTo(SagaStatus.DANG_CHAY);
  }

  @Test
  void reconcileWakesSagaWhoseReplyArrivedFirst() {
    behaviour.put("B", Behaviour.AWAIT);
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    turn(saga.id());
    turn(saga.id());
    service.reconcileWaiting(saga.id());
    assertThat(engine.woken).isEmpty();
    service.onReply(saga.id(), 2, UUID.randomUUID(), true, null);
    engine.woken.clear();
    service.reconcileWaiting(saga.id());
    assertThat(engine.woken).containsExactly(saga.id());
  }

  @Test
  void pauseTakesEffectAfterCurrentStepAndResumeContinues() {
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    service.control(saga.id(), SagaService.Control.TAM_DUNG);
    assertThat(turn(saga.id())).isEqualTo(Next.CHO);
    SagaInstance paused = repo.get(saga.id());
    assertThat(paused.paused()).isTrue();
    assertThat(paused.status()).isEqualTo(SagaStatus.CHO_SU_KIEN);
    service.control(saga.id(), SagaService.Control.TIEP_TUC);
    assertThat(turn(saga.id())).isEqualTo(Next.TIEP);
    assertThat(calls).containsExactly("exec:A:1");
  }

  @Test
  void operatorCompensationRejectedAfterPointOfNoReturnAndForFinishedSaga() {
    pnrOnStep2 = true;
    rebuild();
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    service.control(saga.id(), SagaService.Control.BU_TRU);
    turn(saga.id());
    assertThat(repo.get(saga.id()).status()).isEqualTo(SagaStatus.DA_BU_TRU);
    assertThatThrownBy(() -> service.control(saga.id(), SagaService.Control.TIEP_TUC))
        .isInstanceOf(BusinessException.class);

    SagaInstance second = start(UUID.randomUUID(), "k2");
    turn(second.id());
    turn(second.id());
    turn(second.id());
    assertThatThrownBy(() -> service.control(second.id(), SagaService.Control.BU_TRU))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void operatorCompensationMidwayUndoesCompletedSteps() {
    behaviour.put("B", Behaviour.AWAIT);
    SagaInstance saga = start(UUID.randomUUID(), "k1");
    turn(saga.id());
    turn(saga.id());
    service.control(saga.id(), SagaService.Control.BU_TRU);
    turn(saga.id());
    assertThat(turn(saga.id())).isEqualTo(Next.XONG);
    assertThat(repo.get(saga.id()).status()).isEqualTo(SagaStatus.DA_BU_TRU);
    assertThat(calls).containsExactly("exec:A:1", "exec:B:1", "comp:A");
    assertThat(service.onReply(saga.id(), 2, UUID.randomUUID(), true, null)).isFalse();
  }

  @Test
  void catalogRejectsBlueprintWithoutHandlers() {
    SagaBlueprint lonely =
        new SagaBlueprint() {
          @Override
          public SagaDefinition definition() {
            return new SagaDefinition(
                SagaType.CHOT_KY, List.of(step("X", null, false, null)), true);
          }

          @Override
          public String exclusiveKey(Map<String, Object> parameters) {
            return null;
          }
        };
    assertThatThrownBy(() -> new SagaCatalog(List.of(lonely), List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  // --- Giả lập --------------------------------------------------------------------------

  /** Kho trong bộ nhớ; {@link #snapshot()}/{@link #rollback()} giả lập huỷ giao dịch. */
  static final class MemoryRepository implements SagaRepository {
    private final Map<UUID, SagaInstance> rows = new HashMap<>();
    private final Set<String> locked = new HashSet<>();
    private Map<UUID, Copy> saved = Map.of();

    record Copy(SagaInstance saga) {}

    SagaInstance get(UUID id) {
      return rows.get(id);
    }

    void snapshot() {
      Map<UUID, Copy> copy = new HashMap<>();
      rows.forEach((k, v) -> copy.put(k, new Copy(SagaCopies.copy(v))));
      saved = copy;
    }

    void rollback() {
      saved.forEach((k, v) -> rows.put(k, v.saga()));
    }

    @Override
    public Optional<SagaInstance> findById(UUID sagaId) {
      return Optional.ofNullable(rows.get(sagaId));
    }

    @Override
    public Optional<SagaInstance> findByCorrelation(SagaType type, UUID correlationId) {
      return rows.values().stream()
          .filter(s -> s.type() == type && s.correlationId().equals(correlationId))
          .findFirst();
    }

    @Override
    public void lockExclusiveKey(SagaType type, String exclusiveKey) {
      locked.add(type + ":" + exclusiveKey);
    }

    @Override
    public boolean existsBlockingFor(SagaType type, String exclusiveKey) {
      return rows.values().stream()
          .anyMatch(
              s ->
                  s.type() == type
                      && exclusiveKey.equals(s.exclusiveKey())
                      && s.status() != SagaStatus.DA_BU_TRU);
    }

    @Override
    public void insert(SagaInstance saga) {
      rows.put(saga.id(), saga);
    }

    @Override
    public void update(SagaInstance saga) {
      rows.put(saga.id(), saga);
    }
  }

  /** Bản sao sâu để giả lập dữ liệu trước giao dịch bị huỷ. */
  static final class SagaCopies {
    static SagaInstance copy(SagaInstance s) {
      List<SagaStep> steps =
          s.steps().stream()
              .map(
                  t ->
                      new SagaStep(
                          t.id(),
                          t.stepNo(),
                          t.stepCode(),
                          t.compensationCode(),
                          t.status(),
                          t.attempts(),
                          t.commandEventId(),
                          t.replyEventId(),
                          t.lastError(),
                          t.startedAt(),
                          t.finishedAt()))
              .toList();
      return new SagaInstance(
          s.id(),
          s.tenantId(),
          s.type(),
          s.correlationId(),
          s.exclusiveKey(),
          s.initiator(),
          s.parameters(),
          steps,
          s.status(),
          s.currentStepNo(),
          s.pointOfNoReturnPassed(),
          s.pauseRequested(),
          s.paused(),
          s.compensating(),
          s.startedAt(),
          s.finishedAt(),
          s.rowVersion());
    }
  }

  static final class RecordingEngine implements SagaEngine {
    final List<UUID> launched = new ArrayList<>();
    final List<UUID> woken = new ArrayList<>();
    final List<Duration> failures = new ArrayList<>();
    Next lastNext;

    @Override
    public String launch(UUID sagaId, UUID tenantId, String businessKey) {
      launched.add(sagaId);
      return "pi-" + sagaId;
    }

    @Override
    public boolean wake(UUID sagaId) {
      woken.add(sagaId);
      return true;
    }

    @Override
    public void completeTurn(String turnId, Next next) {
      lastNext = next;
    }

    @Override
    public void failTurn(String turnId, String error, Duration backoff) {
      failures.add(backoff);
    }
  }
}
