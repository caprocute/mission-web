package vn.viettel.okrkpi.task.application.direct.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đọc, ghi {@code task.task}, {@code task.task_assignee}, {@code task.task_state_log} cho chỉ đạo.
 */
public interface TaskDirections {

  /** Dòng chủ trì còn hiệu lực: cá nhân ({@code CA_NHAN}) hoặc đơn vị ({@code DON_VI}). */
  record Lead(UUID rowId, String type, UUID personId, UUID orgUnitId, LocalDate validFrom) {}

  /** Nhiệm vụ đang mở do một người chủ trì. */
  record LedTask(
      UUID id,
      UUID periodId,
      int periodBucket,
      String code,
      String status,
      UUID assignerId,
      LocalDate scoreDueDate,
      String stateMachineVersion,
      long rowVersion) {}

  /** Thuộc tính nhiệm vụ cần cho phạm vi và EVT-04. */
  record Facts(UUID orgUnitId, UUID planItemId, boolean restricted, boolean keyTask) {}

  Optional<Facts> facts(TenantId tenant, int periodBucket, UUID taskId);

  Optional<Lead> lead(TenantId tenant, int periodBucket, UUID taskId);

  /** Đã có lần nhắc của {@code actor} cho nhiệm vụ từ {@code since}. */
  boolean remindedSince(
      TenantId tenant, int periodBucket, UUID taskId, UUID actor, String transition, Instant since);

  /** Tăng {@code row_version} khi khớp {@code expected}; trả phiên bản mới hoặc -1 khi lệch. */
  long bumpVersion(
      TenantId tenant, int periodBucket, UUID taskId, long expected, UUID actor, Instant at);

  /**
   * Kết thúc dòng chủ trì cũ từ {@code from} (giữ lịch sử cho người cũ) và thêm dòng chủ trì cá
   * nhân mới hiệu lực từ {@code from}.
   */
  void replaceLead(
      TenantId tenant,
      UUID periodId,
      int periodBucket,
      UUID taskId,
      Lead old,
      UUID newPerson,
      LocalDate from,
      UUID actor,
      Instant at);

  /** Nhiệm vụ {@code DA_GIAO}, {@code DANG_THUC_HIEN}, {@code TAM_DUNG} người này đang chủ trì. */
  List<LedTask> openTasksLedBy(TenantId tenant, UUID personId);
}
