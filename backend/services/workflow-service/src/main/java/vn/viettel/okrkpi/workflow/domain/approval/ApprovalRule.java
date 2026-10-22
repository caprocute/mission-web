package vn.viettel.okrkpi.workflow.domain.approval;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Một dòng kết quả của bảng quyết định {@code chon-cap-duyet}, hoặc một cấp khai trong tham số
 * {@code cap} của {@code flowable.wf_binding}: thứ tự, vai trò baseline, phạm vi và hạn theo ngày
 * làm việc.
 *
 * @param order thứ tự cấp ({@code thuTu}), bắt đầu từ 1
 * @param role mã vai trò baseline, ví dụ {@code leader}
 * @param scope phạm vi phân giải đơn vị
 * @param workingDays hạn xử lý theo ngày làm việc ({@code hanNgayLamViec})
 */
public record ApprovalRule(int order, String role, ApprovalScope scope, int workingDays) {

  /** Hạn tối đa của một cấp (TL-02b SCR-1504 cho tham số 1 đến 15; nới cho bảng quyết định). */
  public static final int MAX_WORKING_DAYS = 30;

  /** Cùng khuôn vai trò với {@code CandidateGroup}. */
  private static final Pattern ROLE = Pattern.compile("[a-z][a-z0-9_]{0,31}");

  public ApprovalRule {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(scope, "scope");
    if (order < 1) {
      throw new IllegalArgumentException("Thứ tự cấp duyệt phải từ 1");
    }
    if (!ROLE.matcher(role).matches()) {
      throw new IllegalArgumentException("Mã vai trò cấp duyệt không hợp lệ");
    }
    if (workingDays < 1 || workingDays > MAX_WORKING_DAYS) {
      throw new IllegalArgumentException("Hạn cấp duyệt phải từ 1 đến 30 ngày làm việc");
    }
  }
}
