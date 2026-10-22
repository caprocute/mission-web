package vn.viettel.okrkpi.task.application.lifecycle.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

/** Đọc và ghi trạng thái {@code task.task}, nhật ký {@code task.task_state_log}. */
public interface TaskLifecycleRepository {

  /** Các cột của {@code task.task} cần cho chuyển trạng thái và phản hồi {@code TaskDetail}. */
  record TaskRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      String code,
      String title,
      String description,
      String taskGroup,
      String source,
      UUID keyResultId,
      UUID assignerId,
      Instant assignedAt,
      UUID acceptorId,
      LocalDate dueDate,
      LocalDate extendedDueDate,
      LocalDate scoreDueDate,
      BigDecimal plannedQuantity,
      BigDecimal doneQuantity,
      String unit,
      Integer difficulty,
      BigDecimal standardScore,
      BigDecimal progressPct,
      TaskStatus status,
      boolean overdue,
      String delayRisk,
      String stateMachineVersion,
      long rowVersion) {}

  /** Một dòng {@code task.task_state_log}. */
  record StateLogRow(
      Instant occurredAt,
      TaskStatus from,
      TaskStatus to,
      String transition,
      UUID actorId,
      String reason,
      String stateMachineVersion) {}

  /** Đề nghị điều chỉnh gần nhất đã có quyết định, cho guard {@code change_request}. */
  record ChangeRequestRow(String requestType, String status) {}

  /** Thay đổi ghi vào {@code task.task} khi chuyển trạng thái. */
  record StatusUpdate(
      TaskStatus to,
      String stateMachineVersion,
      UUID actorId,
      Instant at,
      Instant completedAt,
      String cancelledReason) {}

  Optional<TaskRow> find(TenantId tenant, UUID taskId);

  /** Khoá dòng ({@code SELECT … FOR UPDATE}) trong giao dịch đang mở. */
  Optional<TaskRow> lock(TenantId tenant, UUID taskId);

  /** Người chủ trì đang hiệu lực ({@code task_assignee.role = 'CHU_TRI'}). */
  Optional<UUID> leadAssignee(TenantId tenant, int periodBucket, UUID taskId);

  /** Đề nghị tạm dừng hoặc huỷ gần nhất của nhiệm vụ, nếu có. */
  Optional<ChangeRequestRow> latestChangeRequest(TenantId tenant, int periodBucket, UUID taskId);

  /** Ghi trạng thái mới, tăng {@code row_version}; trả {@code row_version} mới. */
  long changeStatus(
      TenantId tenant, int periodBucket, UUID taskId, long expectedVersion, StatusUpdate update);

  void appendStateLog(
      TenantId tenant,
      UUID periodId,
      int periodBucket,
      UUID taskId,
      StateLogRow row,
      String beforeAfterJson);

  List<StateLogRow> stateLog(TenantId tenant, int periodBucket, UUID taskId);
}
