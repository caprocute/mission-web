package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Quy tắc ứng viên xử lý khiếu nại của OPA (gói {@code okrkpi.complaint}, {@code
 * backend/opa/okrkpi/complaint_handler.rego}, BR-12-04) mà SVC-06 gọi khi giao task.
 */
public interface ComplaintHandlerPolicy {

  /**
   * Quyết định của OPA; rỗng khi không còn ứng viên trên cả chuỗi.
   *
   * @param units chuỗi đơn vị, phần tử đầu là đơn vị xử lý
   * @param appraisers người giữ vai trò {@code appraiser} theo đơn vị
   * @param participants nhóm đã tham gia hồ sơ ({@code acceptor}, {@code appraiser}, {@code
   *     proposer}, {@code decider})
   */
  Optional<Decision> decide(
      List<UUID> units, Map<UUID, List<UUID>> appraisers, Map<String, List<UUID>> participants);

  /**
   * Quyết định.
   *
   * @param unitId đơn vị được chọn
   * @param candidates ứng viên hợp lệ (sắp tăng dần)
   * @param escalated phải lên cấp trên
   */
  record Decision(UUID unitId, List<UUID> candidates, boolean escalated) {

    public Decision {
      Objects.requireNonNull(unitId, "unitId");
      candidates = List.copyOf(candidates);
    }
  }
}
