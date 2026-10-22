package vn.viettel.okrkpi.workflow.domain.approval;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Tham số của một mẫu luồng cho một tenant, đọc từ {@code flowable.wf_binding.params_json} (TL-01
 * "Bảng tham số theo tenant": {@code remindAfter}, {@code allowDelegation}; TL-02b SCR-1504: danh
 * sách {@code cap}). Không có dòng phù hợp thì dùng {@link #defaults(ApprovalFlow)} của khối.
 *
 * @param remindAfter thời lượng ISO-8601 trước khi nhắc người duyệt (timer biên không ngắt)
 * @param allowDelegation cho phép chuyển người duyệt (uỷ quyền) khi điều động
 * @param levels các cấp tuỳ chỉnh của tenant; rỗng thì dùng kết quả DMN {@code chon-cap-duyet}
 * @param signMethod phương thức ký khi lõi duyệt kế hoạch ({@code signMethod} của {@code
 *     approvePlanVersion})
 */
public record ApprovalParams(
    String remindAfter, boolean allowDelegation, List<ApprovalRule> levels, String signMethod) {

  /** Số cấp tối đa của một luồng (BS-01: tối đa 12 user task trên một nhánh; SCR-1504: 2 đến 4). */
  public static final int MAX_LEVELS = 8;

  /** Phương thức ký theo hợp đồng {@code approvePlanVersion}. */
  public static final Set<String> SIGN_METHODS = Set.of("MYSIGN", "USB_TOKEN", "CHUNG_THU_TO_CHUC");

  /** Nhắc sau tối thiểu một giờ để timer không dồn job. */
  private static final Duration MIN_REMIND = Duration.ofHours(1);

  /** Nhắc sau tối đa 30 ngày. */
  private static final Duration MAX_REMIND = Duration.ofDays(30);

  public ApprovalParams {
    Objects.requireNonNull(remindAfter, "remindAfter");
    Objects.requireNonNull(signMethod, "signMethod");
    levels = levels == null ? List.of() : List.copyOf(levels);
    Duration remind;
    try {
      remind = Duration.parse(remindAfter);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Thời lượng nhắc phải theo ISO-8601, ví dụ P2D", e);
    }
    if (remind.compareTo(MIN_REMIND) < 0 || remind.compareTo(MAX_REMIND) > 0) {
      throw new IllegalArgumentException("Thời lượng nhắc phải từ 1 giờ đến 30 ngày");
    }
    if (levels.size() > MAX_LEVELS) {
      throw new IllegalArgumentException("Luồng duyệt tối đa 8 cấp");
    }
    if (!SIGN_METHODS.contains(signMethod)) {
      throw new IllegalArgumentException("Phương thức ký không thuộc danh mục");
    }
  }

  /**
   * Mặc định của khối khi tenant chưa có dòng {@code wf_binding} (suy luận: nhắc sau 2 ngày theo
   * ví dụ TL-01, cho phép uỷ quyền theo TL-02a QT-02 "người duyệt vắng mặt", ký bằng MySign).
   */
  public static ApprovalParams defaults(ApprovalFlow flow) {
    Objects.requireNonNull(flow, "flow");
    return new ApprovalParams("P2D", true, List.of(), "MYSIGN");
  }
}
