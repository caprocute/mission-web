package vn.viettel.okrkpi.workflow.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ảnh chụp một user task đang chạy trong engine, đủ để kiểm điều kiện hoàn thành.
 *
 * @param taskId mã user task
 * @param tenantId tenant của tiến trình
 * @param assigneeId người được giao, {@code null} khi chưa nhận
 * @param candidateGroups nhóm ứng viên hợp lệ của task
 * @param revision {@code REV_} hiện tại
 * @param businessKey đối tượng nghiệp vụ
 * @param initiatorId người khởi tạo tiến trình (người soạn, dùng cho tách vai trò), có thể {@code
 *     null}
 */
public record OpenTask(
    String taskId,
    UUID tenantId,
    UUID assigneeId,
    List<CandidateGroup> candidateGroups,
    int revision,
    BusinessKey businessKey,
    UUID initiatorId) {

  public OpenTask {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(businessKey, "businessKey");
    candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
  }

  /**
   * Người gọi được hoàn thành task khi là người được giao, hoặc task chưa giao và người gọi thuộc
   * một nhóm ứng viên.
   */
  public boolean actionableBy(Approver approver) {
    if (!tenantId.equals(approver.tenantId())) {
      return false;
    }
    if (assigneeId != null) {
      return assigneeId.equals(approver.actorId());
    }
    return candidateGroups.stream().anyMatch(approver::belongsTo);
  }

  /** Đúng khi người gọi phải nhận việc trước khi hoàn thành. */
  public boolean needsClaim() {
    return assigneeId == null;
  }
}
