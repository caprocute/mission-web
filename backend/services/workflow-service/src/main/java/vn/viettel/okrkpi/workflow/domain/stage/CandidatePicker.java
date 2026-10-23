package vn.viettel.okrkpi.workflow.domain.stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Chọn người làm có tách vai trò (BR-09-14, BR-12-04, BR-13-02, BR-13-03, BR-15-09): đi theo chuỗi
 * đơn vị từ đơn vị xử lý lên cấp trên, tại đơn vị đầu tiên có đủ {@code needed} ứng viên không bị
 * loại thì lấy những người ít việc đang mở nhất (bằng nhau thì theo mã). Không bao giờ hạ số người
 * cần (mở lại kỳ luôn hai người): đơn vị thiếu người thì lên cấp trên.
 *
 * <p>Cùng quy tắc với {@code backend/opa/okrkpi/complaint_handler.rego}; mẫu khiếu nại hỏi OPA, các
 * mẫu khác dùng lớp này.
 */
public final class CandidatePicker {

  private CandidatePicker() {}

  /**
   * Kết quả chọn.
   *
   * @param unitId đơn vị của người được chọn
   * @param assignees người được chọn, theo thứ tự giao
   * @param escalated phải lên đơn vị cấp trên
   */
  public record Pick(UUID unitId, List<UUID> assignees, boolean escalated) {

    public Pick {
      Objects.requireNonNull(unitId, "unitId");
      assignees = List.copyOf(assignees);
    }
  }

  /**
   * Chọn người.
   *
   * @param units chuỗi đơn vị, phần tử đầu là đơn vị xử lý
   * @param holders người giữ vai trò theo đơn vị
   * @param excluded người không được nhận việc (đã tham gia hồ sơ, người tạo yêu cầu, người soạn)
   * @param workload số việc đang mở theo người
   * @param needed số người cần, từ 1
   * @return rỗng khi cả chuỗi không đủ người
   */
  public static Optional<Pick> pick(
      List<UUID> units,
      Map<UUID, List<UUID>> holders,
      Set<UUID> excluded,
      Map<UUID, Long> workload,
      int needed) {
    if (needed < 1) {
      throw new IllegalArgumentException("Số người cần chọn phải từ 1");
    }
    for (int i = 0; i < units.size(); i++) {
      UUID unit = units.get(i);
      List<UUID> eligible = new ArrayList<>();
      for (UUID p : holders.getOrDefault(unit, List.of())) {
        if (!excluded.contains(p) && !eligible.contains(p)) {
          eligible.add(p);
        }
      }
      if (eligible.size() >= needed) {
        eligible.sort(
            Comparator.<UUID>comparingLong(p -> workload.getOrDefault(p, 0L))
                .thenComparing(UUID::toString));
        return Optional.of(new Pick(unit, eligible.subList(0, needed), i > 0));
      }
    }
    return Optional.empty();
  }
}
