package vn.viettel.okrkpi.task.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.TaskDelayRiskService.ForecastInput;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDelayRisks;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.lifecycle.DelayForecast;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class TaskDelayRiskServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant T = Instant.parse("2027-02-10T01:00:00Z");

  private final LifecycleFakes.Repo repo = new LifecycleFakes.Repo();
  private final Store store = new Store();
  private final TaskDelayRiskService service = new TaskDelayRiskService(repo, store);

  @Test
  void luuVaDocLaiDuBao() {
    UUID id = add(TaskStatus.DANG_THUC_HIEN);
    service.applyForecast(TENANT, id, input("CAO", T));
    assertThat(store.rows.get(id).level()).isEqualTo("CAO");
    // Cùng thời điểm tính vẫn ghi đè được (gửi lại).
    service.applyForecast(TENANT, id, input("VUA", T));
    assertThat(store.rows.get(id).level()).isEqualTo("VUA");

    TaskRow withRisk = withRisk(repo.rows.get(id), "VUA");
    assertThat(service.find(TENANT, withRisk))
        .get()
        .extracting(DelayForecast::level)
        .isEqualTo("VUA");
    assertThat(service.find(TENANT, repo.rows.get(id))).isEmpty();
    assertThat(
            service.find(
                TENANT,
                withRisk(LifecycleFakes.task(id, UUID.randomUUID(), TaskStatus.HUY, null), "CAO")))
        .isEmpty();
  }

  @Test
  void tuChoiKetQuaCuVaNhiemVuDaDong() {
    UUID id = add(TaskStatus.CHO_NGHIEM_THU);
    service.applyForecast(TENANT, id, input("CAO", T));
    assertThatThrownBy(() -> service.applyForecast(TENANT, id, input("THAP", T.minusSeconds(60))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo(TaskDelayRiskService.STALE);
    for (TaskStatus closed : DelayForecast.CLOSED) {
      UUID c = add(closed);
      assertThatThrownBy(() -> service.applyForecast(TENANT, c, input("CAO", T)))
          .extracting(e -> ((BusinessException) e).errorCode().code())
          .isEqualTo(TaskDelayRiskService.INVALID_STATE);
    }
    assertThatThrownBy(() -> service.applyForecast(TENANT, UUID.randomUUID(), input("CAO", T)))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void duLieuKhongHopLe() {
    UUID id = add(TaskStatus.DANG_THUC_HIEN);
    assertThatThrownBy(() -> service.applyForecast(TENANT, id, null))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.applyForecast(TENANT, id, input("RAT_CAO", T)))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.applyForecast(TENANT, id, input("CAO", null)))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () ->
                service.applyForecast(
                    TENANT, id, new ForecastInput("CAO", null, "Giải thích", T, null)))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () -> service.applyForecast(TENANT, id, new ForecastInput("CAO", 1, " ", T, null)))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () ->
                service.applyForecast(
                    TENANT, id, new ForecastInput("CAO", 1, "x".repeat(1001), T, null)))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(store.rows).isEmpty();
  }

  private UUID add(TaskStatus status) {
    UUID id = UUID.randomUUID();
    repo.rows.put(id, LifecycleFakes.task(id, UUID.randomUUID(), status, null));
    return id;
  }

  private static ForecastInput input(String level, Instant at) {
    return new ForecastInput(level, 3, "Nhịp thực hiện chậm", at, UUID.randomUUID());
  }

  private static TaskRow withRisk(TaskRow t, String level) {
    return new TaskRow(
        t.id(),
        t.periodId(),
        t.periodBucket(),
        t.code(),
        t.title(),
        t.description(),
        t.taskGroup(),
        t.source(),
        t.keyResultId(),
        t.assignerId(),
        t.assignedAt(),
        t.acceptorId(),
        t.dueDate(),
        t.extendedDueDate(),
        t.scoreDueDate(),
        t.plannedQuantity(),
        t.doneQuantity(),
        t.unit(),
        t.difficulty(),
        t.standardScore(),
        t.progressPct(),
        t.status(),
        t.overdue(),
        level,
        t.stateMachineVersion(),
        t.rowVersion());
  }

  private static final class Store implements TaskDelayRisks {
    final Map<UUID, DelayForecast> rows = new HashMap<>();

    @Override
    public Optional<Instant> storedAt(TenantId tenant, int periodBucket, UUID taskId) {
      return Optional.ofNullable(rows.get(taskId)).map(DelayForecast::computedAt);
    }

    @Override
    public Optional<DelayForecast> find(TenantId tenant, int periodBucket, UUID taskId) {
      return Optional.ofNullable(rows.get(taskId));
    }

    @Override
    public void store(TenantId tenant, int periodBucket, UUID taskId, DelayForecast forecast) {
      rows.put(taskId, forecast);
    }
  }
}
