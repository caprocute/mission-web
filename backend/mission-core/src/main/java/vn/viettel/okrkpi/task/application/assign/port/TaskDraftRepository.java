package vn.viettel.okrkpi.task.application.assign.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;

/**
 * Nhiệm vụ đang soạn và danh sách việc của đơn vị trên {@code task.task}, {@code
 * task.task_assignee} (CORE-02-14).
 */
public interface TaskDraftRepository {

  /** Nhiệm vụ ở dạng soạn: nội dung kèm các trường do máy chủ giữ. */
  record DraftRow(
      UUID id,
      String code,
      String status,
      UUID periodId,
      int periodBucket,
      UUID orgUnitId,
      UUID assignerId,
      boolean restricted,
      TaskDraftSpec spec,
      long rowVersion) {}

  /** Người hoặc đơn vị được giao còn hiệu lực; {@code kind} là {@code PERSON} hoặc {@code UNIT}. */
  record AssigneeRow(String kind, UUID id, String role) {}

  /** Nhiệm vụ mới ở {@code NHAP}. */
  record NewDraft(
      UUID id,
      String code,
      UUID periodId,
      int periodBucket,
      UUID orgUnitId,
      UUID assignerId,
      TaskDraftSpec spec,
      Integer difficulty,
      java.math.BigDecimal standardScore,
      byte[] fingerprint,
      String stateMachineVersion,
      UUID leadUnitId) {}

  /** Bộ lọc của {@code listUnitTasks}. */
  record UnitFilter(
      Collection<UUID> unitIds,
      UUID periodId,
      Set<String> statuses,
      UUID keyResultId,
      Boolean overdue,
      String q,
      String source,
      Boolean pendingChangeRequest) {}

  /** Một dòng danh sách đơn vị: nhiệm vụ, chủ trì và cờ hạn chế. */
  /** {@code orgUnitId} là {@code task.task.org_unit_id} (đơn vị của nhiệm vụ). */
  record UnitTaskRow(TaskRow task, UUID leadAssigneeId, boolean restricted, UUID orgUnitId) {}

  /**
   * Số thứ tự kế tiếp của mã trong phân vùng; giữ khoá tư vấn theo tenant và phân vùng tới hết giao
   * dịch để hai bản nháp không trùng mã.
   */
  int nextCodeNumber(TenantId tenant, int periodBucket);

  void insertDraft(TenantId tenant, NewDraft draft, Instant at);

  Optional<DraftRow> findDraft(TenantId tenant, UUID taskId);

  /**
   * Ghi nội dung nháp và thay danh sách người được giao khi khớp {@code expectedVersion} và còn
   * {@code NHAP}; trả phiên bản mới, {@code -1} khi không ghi được.
   */
  long storeDraft(
      TenantId tenant,
      DraftRow current,
      long expectedVersion,
      TaskDraftSpec spec,
      Integer difficulty,
      java.math.BigDecimal standardScore,
      byte[] fingerprint,
      UUID actorId,
      Instant at);

  /** Xoá nhiệm vụ {@code NHAP} và người được giao; {@code false} khi không còn đúng phiên bản. */
  boolean discardDraft(TenantId tenant, int periodBucket, UUID taskId, long expectedVersion);

  /** Ghi thời điểm giao sau lệnh ASSIGN của máy trạng thái. */
  void markAssigned(TenantId tenant, int periodBucket, UUID taskId, Instant at);

  List<AssigneeRow> assignees(TenantId tenant, int periodBucket, UUID taskId);

  /** Dòng kế hoạch thuộc phiên bản {@code DA_DUYET} mới nhất của kế hoạch (BR-05-04). */
  boolean planItemApproved(TenantId tenant, int periodBucket, UUID planItemId);

  /** Mục danh mục: mức khó và điểm chuẩn để chép vào nhiệm vụ; rỗng khi không có. */
  Optional<CatalogDefaults> catalogDefaults(TenantId tenant, UUID catalogItemId);

  record CatalogDefaults(Integer difficulty, java.math.BigDecimal standardScore) {}

  /** Danh sách việc của đơn vị theo {@code id} tăng dần sau {@code afterId}. */
  List<UnitTaskRow> listUnitTasks(
      TenantId tenant, int periodBucket, UnitFilter filter, UUID afterId, int limit);
}
