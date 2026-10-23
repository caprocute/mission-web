package vn.viettel.okrkpi.oversight.domain.complaint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Chọn người xử lý khiếu nại hợp lệ (QT-12 bước 3, BR-12-04), cùng logic với quy tắc Rego {@code
 * okrkpi.complaint.candidates}: người có vai trò {@code appraiser} ở đơn vị có thẩm quyền, loại trừ
 * người đã nghiệm thu, thẩm định, đề xuất, quyết định hồ sơ và chính người khiếu nại; không còn ai
 * thì nâng lên đơn vị cấp trên trực tiếp theo cây tổ chức.
 */
public final class ComplaintHandlerRules {

  /** Thời hạn tiếp nhận, phân loại: 2 ngày làm việc kể từ khi gửi (QT-12 bước 4). */
  public static final int ACCEPT_WORKING_DAYS = 2;

  /** Hạn giải trình mặc định: 3 ngày làm việc (QT-12 bước 5). */
  public static final int EXPLANATION_WORKING_DAYS = 3;

  public static final int REJECT_REASON_MIN = 20;
  public static final int WITHDRAW_REASON_MIN = 10;
  public static final int REASON_MAX = 2000;

  private ComplaintHandlerRules() {}

  /**
   * Ứng viên ở cấp gần nhất còn người hợp lệ.
   *
   * @param levels người giữ vai trò {@code appraiser}, cấp đơn vị của hồ sơ trước rồi lần lượt cấp
   *     trên
   * @param excluded người không được xử lý
   */
  public static Candidates candidates(List<? extends Set<UUID>> levels, Predicate<UUID> excluded) {
    for (int i = 0; i < levels.size(); i++) {
      Set<UUID> ok = new LinkedHashSet<>();
      for (UUID p : levels.get(i)) {
        if (!excluded.test(p)) {
          ok.add(p);
        }
      }
      if (!ok.isEmpty()) {
        return new Candidates(List.copyOf(ok), i);
      }
    }
    return new Candidates(List.of(), -1);
  }

  /**
   * Kết quả chọn người xử lý.
   *
   * @param people người hợp lệ
   * @param level số cấp đã nâng (0 là đơn vị của hồ sơ), -1 khi không có ai
   */
  public record Candidates(List<UUID> people, int level) {
    public Candidates {
      people = people == null ? List.of() : new ArrayList<>(people);
      people = List.copyOf(people);
    }

    public boolean escalated() {
      return level > 0;
    }
  }
}
