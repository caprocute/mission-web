package vn.viettel.okrkpi.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Một dòng {@code flowable.wf_task_projection}: user task đang chờ trong hộp việc (TL-03).
 *
 * @param taskId mã {@code ACT_RU_TASK.ID_}
 * @param tenantId tenant
 * @param assigneeId người được giao, {@code null} khi chưa nhận
 * @param candidateRole vai trò ứng viên, có thể {@code null}
 * @param candidateUnitId đơn vị ứng viên, có thể {@code null}
 * @param businessKey đối tượng nghiệp vụ
 * @param templateCode mã mẫu luồng, có thể {@code null}
 * @param title tiêu đề hiển thị (C0/C1)
 * @param periodId kỳ, có thể {@code null}
 * @param dueAt hạn, có thể {@code null}
 * @param createdAt thời điểm tạo user task
 * @param rowVersion {@code REV_} của {@code ACT_RU_TASK}
 */
public record TaskProjection(
    String taskId,
    UUID tenantId,
    UUID assigneeId,
    String candidateRole,
    UUID candidateUnitId,
    BusinessKey businessKey,
    String templateCode,
    String title,
    UUID periodId,
    Instant dueAt,
    Instant createdAt,
    int rowVersion) {

  /** Tiêu đề tối đa theo cột {@code title varchar(255)}. */
  public static final int MAX_TITLE = 255;

  public TaskProjection {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(businessKey, "businessKey");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(createdAt, "createdAt");
    if (title.isBlank() || title.length() > MAX_TITLE) {
      throw new IllegalArgumentException("Tiêu đề việc rỗng hoặc quá 255 ký tự");
    }
    if (rowVersion < 1) {
      throw new IllegalArgumentException("rowVersion phải từ 1");
    }
  }

  /** Quá hạn khi có hạn và hạn trước thời điểm xét. */
  public boolean overdueAt(Instant now) {
    return dueAt != null && dueAt.isBefore(now);
  }
}
