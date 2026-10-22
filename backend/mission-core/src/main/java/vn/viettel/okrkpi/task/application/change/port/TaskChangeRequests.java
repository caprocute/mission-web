package vn.viettel.okrkpi.task.application.change.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code task.task_change_request} trên phân vùng kỳ của nhiệm vụ. */
public interface TaskChangeRequests {

  /** Một đề nghị; {@code createdBy} là người gửi, {@code escalated} là cờ vượt ngưỡng. */
  record ChangeRow(
      UUID id,
      UUID taskId,
      String type,
      String status,
      LocalDate proposedDueDate,
      BigDecimal proposedQuantity,
      String newProduct,
      String causeClaimed,
      UUID blockerId,
      String reason,
      UUID approverId,
      UUID createdBy,
      Instant createdAt,
      Instant decidedAt,
      UUID decidedBy,
      String causeDecided,
      String decisionNote,
      boolean escalated,
      long rowVersion) {}

  /** Đề nghị mới ở {@code CHO_DUYET}; {@code newValues} ghi vào cột {@code new_values}. */
  record NewChange(
      UUID id,
      UUID taskId,
      UUID periodId,
      int periodBucket,
      String type,
      LocalDate proposedDueDate,
      BigDecimal proposedQuantity,
      String reason,
      String causeClaimed,
      UUID blockerId,
      UUID approverId,
      Map<String, Object> newValues,
      UUID actorId) {}

  /** Khoá dòng nhiệm vụ để hai lần gửi cùng lúc xếp hàng. */
  void lockTask(TenantId tenant, int periodBucket, UUID taskId);

  boolean hasPending(TenantId tenant, int periodBucket, UUID taskId, String type);

  /** Số đề nghị gia hạn đã duyệt của nhiệm vụ trong kỳ (quý). */
  int approvedExtensions(TenantId tenant, int periodBucket, UUID taskId);

  /** Thêm đề nghị; {@code false} khi đã có đề nghị cùng loại đang chờ (chỉ mục duy nhất). */
  boolean insert(TenantId tenant, NewChange change, Instant at);

  Optional<ChangeRow> find(TenantId tenant, int periodBucket, UUID taskId, UUID requestId);

  /** {@code CHO_DUYET} → {@code DA_RUT}; {@code false} khi đề nghị không còn chờ. */
  boolean withdraw(TenantId tenant, int periodBucket, UUID requestId, UUID actorId, Instant at);

  List<ChangeRow> listOf(TenantId tenant, int periodBucket, UUID taskId);

  /** Quyết định {@code CHO_DUYET} → {@code status}; {@code false} khi đề nghị không còn chờ. */
  boolean decide(
      TenantId tenant,
      int periodBucket,
      UUID requestId,
      String status,
      String causeDecided,
      String note,
      UUID actorId,
      Instant at);

  /** Giá trị mới của nhiệm vụ khi áp dụng đề nghị; {@code null} là giữ nguyên. */
  record TaskChange(
      java.time.LocalDate extendedDueDate,
      java.time.LocalDate scoreDueDate,
      BigDecimal plannedQuantity,
      String productName,
      UUID actorId,
      Instant at) {}

  /**
   * Ghi giá trị mới lên {@code task.task} khi {@code row_version} khớp; trả phiên bản mới hoặc
   * {@code -1} khi lệch.
   */
  long applyToTask(
      TenantId tenant, int periodBucket, UUID taskId, long expectedVersion, TaskChange change);
}
