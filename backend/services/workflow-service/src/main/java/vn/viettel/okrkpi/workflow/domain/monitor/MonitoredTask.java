package vn.viettel.okrkpi.workflow.domain.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;

/**
 * User task đang chạy mà quản trị chuyển người xử lý hoặc gia hạn (BS-01 SCR-1506).
 *
 * @param taskId mã user task
 * @param tenantId tenant của tiến trình
 * @param processInstanceId tiến trình
 * @param activityId mã bước BPMN
 * @param assigneeId người đang xử lý, {@code null} khi việc còn ở nhóm ứng viên
 * @param candidateGroups nhóm ứng viên của bước
 * @param revision {@code REV_} hiện tại
 * @param dueAt hạn hiện tại, có thể {@code null}
 * @param businessKey đối tượng nghiệp vụ
 * @param initiatorId người khởi tạo tiến trình (tách vai trò), có thể {@code null}
 * @param templateCode mã mẫu luồng
 */
public record MonitoredTask(
    String taskId,
    UUID tenantId,
    String processInstanceId,
    String activityId,
    UUID assigneeId,
    List<CandidateGroup> candidateGroups,
    int revision,
    Instant dueAt,
    BusinessKey businessKey,
    UUID initiatorId,
    String templateCode) {

  public MonitoredTask {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(businessKey, "businessKey");
    candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
  }

  /** Nhóm ứng viên đầu tiên cùng tenant, {@code null} khi bước giao đích danh. */
  public CandidateGroup primaryCandidate() {
    return candidateGroups.stream()
        .filter(g -> g.tenantId().equals(tenantId))
        .findFirst()
        .orElse(null);
  }
}
