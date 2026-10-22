package vn.viettel.okrkpi.workflow.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Dữ liệu thô của một user task do listener engine đọc (chưa kiểm dạng), chuyển cho {@link
 * TaskProjectionService} dựng dòng chiếu.
 *
 * @param taskId mã user task
 * @param tenantId {@code TENANT_ID_} của task
 * @param assignee {@code ASSIGNEE_}, có thể {@code null}
 * @param candidateGroups mã nhóm ứng viên
 * @param businessKey khoá nghiệp vụ của tiến trình, có thể {@code null}
 * @param templateCode khoá định nghĩa tiến trình, có thể {@code null}
 * @param name tên task đã phân giải biểu thức
 * @param periodId biến {@code periodId} của tiến trình, có thể {@code null}
 * @param dueAt hạn, có thể {@code null}
 * @param createdAt thời điểm tạo
 * @param revision {@code REV_} sau khi engine ghi
 */
public record EngineTaskEvent(
    String taskId,
    String tenantId,
    String assignee,
    List<String> candidateGroups,
    String businessKey,
    String templateCode,
    String name,
    String periodId,
    Instant dueAt,
    Instant createdAt,
    int revision) {

  public EngineTaskEvent {
    Objects.requireNonNull(taskId, "taskId");
    candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
  }
}
