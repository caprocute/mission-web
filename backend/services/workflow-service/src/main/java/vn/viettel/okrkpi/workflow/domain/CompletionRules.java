package vn.viettel.okrkpi.workflow.domain;

import java.util.Optional;

/**
 * Quy tắc nội dung khi hoàn thành user task (TL-02a SCR-0203; BR-02-09, MSG-02-10): trả lại phải
 * kèm lý do; ý kiến tối đa 2.000 ký tự theo hợp đồng.
 */
public final class CompletionRules {

  /** Độ dài tối đa của ý kiến ({@code WorkflowTaskCompletion.comment}). */
  public static final int MAX_COMMENT = 2000;

  /** Số user task tối đa của một lần duyệt hàng loạt (TL-01 PL C "tới 200 mục"). */
  public static final int MAX_BATCH_ITEMS = 200;

  private CompletionRules() {}

  /** Vi phạm về ý kiến, rỗng khi hợp lệ. */
  public static Optional<Violation> checkComment(Decision decision, String comment) {
    boolean blank = comment == null || comment.isBlank();
    if (decision == Decision.TRA_LAI && blank) {
      return Optional.of(Violation.COMMENT_REQUIRED);
    }
    if (!blank && comment.length() > MAX_COMMENT) {
      return Optional.of(Violation.COMMENT_TOO_LONG);
    }
    return Optional.empty();
  }

  /** Vi phạm có thể có. */
  public enum Violation {
    COMMENT_REQUIRED,
    COMMENT_TOO_LONG
  }
}
