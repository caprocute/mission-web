package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Cổng engine cho bốn mẫu phần 2 (DV-06-04): đọc trạng thái tiến trình đang chạy, hoàn thành bước
 * khi lõi báo xong, đi tắt tới bước sau khi lõi đã đi trước, giao tín hiệu ký số và phát hành, đọc
 * lịch sử tham gia của hồ sơ đánh giá. Khởi tạo dùng chung {@link ApprovalProcesses}.
 */
public interface StageProcesses {

  /** Tiến trình đang chạy của mẫu cho đối tượng, nếu có. */
  Optional<Snapshot> active(UUID tenantId, StageTemplate template, UUID businessId);

  /** Hoàn thành user task với người làm (ghi vào lịch sử để tách vai trò về sau) và biến mới. */
  void completeStep(String taskId, UUID actorId, Map<String, Object> variables);

  /** Chuyển các phần tử đang chạy sang {@code toElementId} (lõi đã đi trước tiến trình). */
  void moveTo(String processInstanceId, List<String> fromElementIds, String toElementId);

  /** Giao message {@code ky-so-hoan-tat} cho phần tử đang chờ ký. */
  void deliverSigned(String processInstanceId, Map<String, Object> variables);

  /** Giao message {@code policy-pack-da-phat-hanh}; {@code false} khi không có ai chờ. */
  boolean deliverPublished(String processInstanceId);

  /** Ghi biến tiến trình. */
  void setVariables(String processInstanceId, Map<String, Object> variables);

  /** Người đã tham gia và đơn vị thẩm định của hồ sơ đánh giá, đọc từ tiến trình hồ sơ gần nhất. */
  Optional<ProfileHistory> appraisalHistory(UUID tenantId, UUID profileId);

  /**
   * Trạng thái tiến trình.
   *
   * @param processInstanceId mã thể hiện
   * @param activeElementIds phần tử đang chạy (user task, chờ ký, worker chọn người)
   * @param openTaskId user task đang mở, có thể {@code null}
   * @param openStep mã phần tử của user task đang mở, có thể {@code null}
   * @param variables biến tiến trình
   */
  record Snapshot(
      String processInstanceId,
      List<String> activeElementIds,
      String openTaskId,
      String openStep,
      Map<String, Object> variables) {

    public Snapshot {
      Objects.requireNonNull(processInstanceId, "processInstanceId");
      activeElementIds = List.copyOf(activeElementIds);
      variables =
          variables == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(variables));
    }
  }

  /**
   * Lịch sử tham gia của hồ sơ đánh giá.
   *
   * @param flow luồng của hồ sơ
   * @param subjectUnitId đơn vị của người được đánh giá
   * @param appraisalUnitId đơn vị thẩm định (cấp xử lý khiếu nại, suy luận)
   * @param participants nhóm ({@code appraiser}, {@code proposer}, {@code decider}) → người
   */
  record ProfileHistory(
      ApprovalFlow flow,
      UUID subjectUnitId,
      UUID appraisalUnitId,
      Map<String, List<UUID>> participants) {

    public ProfileHistory {
      Objects.requireNonNull(flow, "flow");
      Objects.requireNonNull(subjectUnitId, "subjectUnitId");
      participants = Map.copyOf(participants);
    }
  }
}
