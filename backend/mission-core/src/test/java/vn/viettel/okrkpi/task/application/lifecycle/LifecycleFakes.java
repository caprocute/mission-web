package vn.viettel.okrkpi.task.application.lifecycle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

/** Bộ giả lập trong bộ nhớ cho kiểm thử đơn vị lớp ứng dụng CORE-02-13. */
final class LifecycleFakes {

  private LifecycleFakes() {}

  static TaskLifecycleRepository.TaskRow task(
      UUID id, UUID period, TaskStatus status, UUID assigner) {
    return new TaskLifecycleRepository.TaskRow(
        id,
        period,
        202610,
        "NV-1",
        "Nhiệm vụ",
        null,
        "THUONG_XUYEN",
        "KE_HOACH",
        null,
        assigner,
        null,
        null,
        LocalDate.of(2026, 12, 31),
        null,
        null,
        BigDecimal.valueOf(2),
        BigDecimal.valueOf(2),
        "báo cáo",
        null,
        null,
        null,
        status,
        false,
        null,
        "mac-dinh@2.1.0",
        3);
  }

  static final class Repo implements TaskLifecycleRepository {
    final Map<UUID, TaskRow> rows = new HashMap<>();
    final List<StateLogRow> log = new ArrayList<>();
    final List<String> beforeAfter = new ArrayList<>();
    final List<StatusUpdate> updates = new ArrayList<>();
    UUID lead;
    final List<UUID> coordinators = new ArrayList<>();
    ChangeRequestRow change;

    @Override
    public Optional<TaskRow> find(TenantId tenant, UUID taskId) {
      return Optional.ofNullable(rows.get(taskId));
    }

    @Override
    public Optional<TaskRow> lock(TenantId tenant, UUID taskId) {
      return find(tenant, taskId);
    }

    @Override
    public Optional<UUID> leadAssignee(TenantId tenant, int periodBucket, UUID taskId) {
      return Optional.ofNullable(lead);
    }

    @Override
    public List<UUID> activeAssignees(TenantId tenant, int periodBucket, UUID taskId) {
      List<UUID> all = new ArrayList<>(coordinators);
      if (lead != null) {
        all.add(0, lead);
      }
      return all;
    }

    @Override
    public List<TaskListRow> listByAssignee(
        TenantId tenant,
        UUID periodId,
        AssigneeKind kind,
        UUID assigneeId,
        UUID afterId,
        int limit) {
      return rows.values().stream()
          .filter(r -> r.periodId().equals(periodId))
          .filter(r -> afterId == null || r.id().compareTo(afterId) > 0)
          .sorted(java.util.Comparator.comparing(TaskRow::id))
          .limit(limit)
          .map(r -> new TaskListRow(r, lead))
          .toList();
    }

    @Override
    public Optional<ChangeRequestRow> latestChangeRequest(
        TenantId tenant, int periodBucket, UUID taskId) {
      return Optional.ofNullable(change);
    }

    @Override
    public long changeStatus(
        TenantId tenant, int periodBucket, UUID taskId, long expectedVersion, StatusUpdate u) {
      TaskRow r = rows.get(taskId);
      if (r == null || r.rowVersion() != expectedVersion) {
        throw new NoSuchElementException();
      }
      updates.add(u);
      TaskRow n =
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
              r.doneQuantity(),
              r.unit(),
              r.difficulty(),
              r.standardScore(),
              r.progressPct(),
              u.to(),
              r.overdue(),
              r.delayRisk(),
              u.stateMachineVersion(),
              r.rowVersion() + 1);
      rows.put(taskId, n);
      return n.rowVersion();
    }

    @Override
    public void appendStateLog(
        TenantId tenant, UUID periodId, int periodBucket, UUID taskId, StateLogRow row, String ba) {
      log.add(row);
      beforeAfter.add(ba);
    }

    @Override
    public List<StateLogRow> stateLog(TenantId tenant, int periodBucket, UUID taskId) {
      return List.copyOf(log);
    }
  }

  static final class Periods implements PeriodApi {
    final Set<UUID> locked = new HashSet<>();

    @Override
    public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
      return Optional.empty();
    }

    @Override
    public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId periodId) {
      return Optional.empty();
    }

    @Override
    public void assertOpen(TenantId tenantId, PeriodId periodId) {
      if (locked.contains(periodId.value())) {
        throw new PeriodLockedException(periodId);
      }
    }
  }
}
