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
 * @param processInstanceId mã tiến trình, có thể {@code null}
 * @param activityId mã bước BPMN, có thể {@code null}
 * @param activityName tên bước hằng trong BPMN, có thể {@code null}
 * @param templateVersion phiên bản định nghĩa, có thể {@code null}
 * @param processStartedAt thời điểm khởi tạo tiến trình, có thể {@code null}
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
    int revision,
    String processInstanceId,
    String activityId,
    String activityName,
    Integer templateVersion,
    Instant processStartedAt) {

  public EngineTaskEvent {
    Objects.requireNonNull(taskId, "taskId");
    candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
  }

  /** Sự kiện không kèm thông tin tiến trình. */
  public EngineTaskEvent(
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
    this(
        taskId,
        tenantId,
        assignee,
        candidateGroups,
        businessKey,
        templateCode,
        name,
        periodId,
        dueAt,
        createdAt,
        revision,
        null,
        null,
        null,
        null,
        null);
  }
}
