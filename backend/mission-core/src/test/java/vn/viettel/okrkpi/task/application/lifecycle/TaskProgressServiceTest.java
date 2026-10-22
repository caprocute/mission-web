package vn.viettel.okrkpi.task.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.TaskProgressService.BatchItem;
import vn.viettel.okrkpi.task.application.lifecycle.TaskProgressService.BatchResult;
import vn.viettel.okrkpi.task.application.lifecycle.TaskProgressService.ProgressInput;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressEvents;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressRepository;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTables;

/** Cập nhật tiến độ đơn lẻ và hàng loạt (CORE-02-19). */
class TaskProgressServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

  private final LifecycleFakes.Repo repo = new LifecycleFakes.Repo();
  private final LifecycleFakes.Periods periods = new LifecycleFakes.Periods();
  private final Progress store = new Progress();
  private final List<TaskProgressEvents.Progressed> events = new ArrayList<>();
  private final List<Object> published = new ArrayList<>();
  private final UUID lead = UUID.randomUUID();
  private final UUID coordinator = UUID.randomUUID();
  private final TaskActor leadActor = new TaskActor(lead, Set.of("manager"));
  private final TaskProgressService service;

  TaskProgressServiceTest() {
    repo.lead = lead;
    repo.coordinators.add(coordinator);
    TaskTransitionService transitions =
        new TaskTransitionService(
            repo,
            (t, p) -> TransitionTables.defaults(),
            new CelGuardEvaluator(new CelEngine()),
            periods,
            e -> {},
            null,
            published::add,
            Clock.fixed(NOW, ZoneOffset.UTC));
    service =
        new TaskProgressService(
            repo,
            store,
            events::add,
            transitions,
            periods,
            new NoTx(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void chuTriCapNhatVaTuNhanViec() {
    UUID id = add(TaskStatus.DA_GIAO);
    TaskRow after =
        service.updateProgress(
            TENANT,
            id,
            3,
            new ProgressInput(60, "Đã xong dự thảo", BigDecimal.ONE, null),
            leadActor,
            "k1");
    assertThat(after.status()).isEqualTo(TaskStatus.DANG_THUC_HIEN);
    assertThat(after.rowVersion()).isEqualTo(5);
    assertThat(after.progressPct()).isEqualByComparingTo("0.6");
    assertThat(store.rows).hasSize(1);
    assertThat(store.rows.get(0).channel()).isEqualTo("WEB");
    assertThat(events)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.percent()).isEqualByComparingTo("60");
              assertThat(e.aggregateVersion()).isEqualTo(5);
            });
    assertThat(published).isNotEmpty();

    // Gửi lại cùng khoá: không thêm dòng, không phát thêm sự kiện.
    service.updateProgress(
        TENANT, id, 3, new ProgressInput(60, "", null, "MOBILE"), leadActor, "k1");
    assertThat(store.rows).hasSize(1);
    assertThat(events).hasSize(1);
  }

  @Test
  void phoiHopChiGhiNhatKy() {
    UUID id = add(TaskStatus.DANG_THUC_HIEN);
    TaskActor co = new TaskActor(coordinator, Set.of("member"));
    TaskRow after =
        service.updateProgress(
            TENANT, id, 3, new ProgressInput(0, "Đã gửi số liệu", null, "MOBILE"), co, "k2");
    assertThat(after.rowVersion()).isEqualTo(3);
    assertThat(store.rows).singleElement().satisfies(p -> assertThat(p.ratio()).isZero());
    assertThat(events).isEmpty();
    assertThatThrownBy(
            () ->
                service.updateProgress(
                    TENANT, id, 3, new ProgressInput(30, "x", null, null), co, "k3"))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(
            () ->
                service.updateProgress(
                    TENANT, id, 3, new ProgressInput(0, "x", BigDecimal.ONE, null), co, "k4"))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(
            () ->
                service.updateProgress(
                    TENANT,
                    id,
                    3,
                    new ProgressInput(0, "x", null, null),
                    new TaskActor(UUID.randomUUID(), Set.of("member")),
                    "k5"))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-403-OUT-OF-SCOPE");
  }

  @Test
  void tuChoiCacTruongHopSai() {
    UUID id = add(TaskStatus.DANG_THUC_HIEN);
    service.updateProgress(TENANT, id, 3, new ProgressInput(50, "", null, null), leadActor, "a");
    assertThatThrownBy(
            () ->
                service.updateProgress(
                    TENANT, id, 4, new ProgressInput(40, " ", null, null), leadActor, "b"))
        .satisfies(
            e -> {
              BusinessException b = (BusinessException) e;
              assertThat(b.errorCode().code()).isEqualTo("COMMON-422-VALIDATION");
              assertThat(b.msg()).isEqualTo("MSG-06-01");
            });
    assertThatThrownBy(
            () ->
                service.updateProgress(
                    TENANT, id, 3, new ProgressInput(60, "", null, null), leadActor, "c"))
        .isInstanceOf(VersionConflictException.class);
    for (ProgressInput bad :
        List.of(
            new ProgressInput(null, "", null, null),
            new ProgressInput(10, "", null, "FAX"),
            new ProgressInput(10, "", BigDecimal.valueOf(-1), null))) {
      assertThatThrownBy(() -> service.updateProgress(TENANT, id, 4, bad, leadActor, "d"))
          .extracting(e -> ((BusinessException) e).errorCode().code())
          .isEqualTo("COMMON-422-VALIDATION");
    }
    assertThatThrownBy(() -> service.updateProgress(TENANT, id, 4, null, leadActor, "d"))
        .isInstanceOf(BusinessException.class);
    UUID review = add(TaskStatus.CHO_NGHIEM_THU);
    assertThatThrownBy(
            () ->
                service.updateProgress(
                    TENANT, review, 3, new ProgressInput(10, "", null, null), leadActor, "e"))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("TASK-409-INVALID-STATE");
    UUID locked = add(TaskStatus.DANG_THUC_HIEN);
    periods.locked.add(repo.rows.get(locked).periodId());
    assertThatThrownBy(
            () ->
                service.updateProgress(
                    TENANT, locked, 3, new ProgressInput(10, "", null, null), leadActor, "f"))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("TASK-409-PERIOD-LOCKED");
    assertThatThrownBy(
            () ->
                service.updateProgress(
                    TENANT,
                    UUID.randomUUID(),
                    0,
                    new ProgressInput(10, "", null, null),
                    leadActor,
                    "g"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void hangLoatXuLyDocLapTungDong() {
    UUID ok = add(TaskStatus.DANG_THUC_HIEN);
    UUID stale = add(TaskStatus.DANG_THUC_HIEN);
    UUID review = add(TaskStatus.CHO_NGHIEM_THU);
    List<BatchItem> items = new ArrayList<>();
    items.add(new BatchItem(ok, 3L, 70, "Gần xong"));
    items.add(new BatchItem(stale, 1L, 70, ""));
    items.add(new BatchItem(review, 3L, 70, ""));
    items.add(new BatchItem(UUID.randomUUID(), 0L, 10, ""));
    items.add(null);
    List<BatchResult> results = service.applyProgressBatch(TENANT, items, leadActor, "lo");
    assertThat(results).extracting(BatchResult::status).containsExactly(200, 409, 409, 403, 422);
    assertThat(results.get(0).task().progressPct()).isEqualByComparingTo("0.7");
    assertThat(results.get(1).error().errorCode().code()).isEqualTo("TASK-409-VERSION-CONFLICT");
    assertThat(store.rows)
        .singleElement()
        .satisfies(p -> assertThat(p.idempotencyKey()).isEqualTo("lo:0"));
    assertThatThrownBy(() -> service.applyProgressBatch(TENANT, List.of(), leadActor, "x"))
        .isInstanceOf(BusinessException.class);
  }

  private UUID add(TaskStatus status) {
    UUID id = UUID.randomUUID();
    repo.rows.put(id, LifecycleFakes.task(id, UUID.randomUUID(), status, UUID.randomUUID()));
    return id;
  }

  private final class Progress implements TaskProgressRepository {
    final List<NewProgress> rows = new ArrayList<>();
    final Set<String> keys = new HashSet<>();

    @Override
    public boolean exists(TenantId tenant, int periodBucket, String idempotencyKey) {
      return keys.contains(idempotencyKey);
    }

    @Override
    public void insert(TenantId tenant, NewProgress progress) {
      rows.add(progress);
      keys.add(progress.idempotencyKey());
    }

    @Override
    public long applyProgress(
        TenantId tenant,
        int periodBucket,
        UUID taskId,
        long expectedVersion,
        BigDecimal ratio,
        BigDecimal doneQuantity,
        UUID actorId,
        Instant at) {
      TaskRow r = repo.rows.get(taskId);
      if (r.rowVersion() != expectedVersion) {
        return -1;
      }
      repo.rows.put(
          taskId,
          new TaskRow(
              r.id(),
              r.periodId(),
              r.periodBucket(),
              r.code(),
              r.title(),
              r.description(),
              r.taskGroup(),
              r.source(),
              r.keyResultId(),
              r.assignerId(),
              r.assignedAt(),
              r.acceptorId(),
              r.dueDate(),
              r.extendedDueDate(),
              r.scoreDueDate(),
              r.plannedQuantity(),
              doneQuantity == null ? r.doneQuantity() : doneQuantity,
              r.unit(),
              r.difficulty(),
              r.standardScore(),
              ratio,
              r.status(),
              r.overdue(),
              r.delayRisk(),
              r.stateMachineVersion(),
              r.rowVersion() + 1));
      return expectedVersion + 1;
    }
  }

  private static final class NoTx implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
