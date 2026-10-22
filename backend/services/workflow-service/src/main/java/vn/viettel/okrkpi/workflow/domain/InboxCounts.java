package vn.viettel.okrkpi.workflow.domain;

/**
 * Số đếm của hộp việc cho nhắc MSG-02-12 "Còn {n} bộ chờ duyệt" và thẻ trang chủ.
 *
 * @param total tổng số việc đang chờ
 * @param overdue số việc đã quá hạn
 * @param dueToday số việc đến hạn trong hôm nay mà chưa quá hạn
 */
public record InboxCounts(int total, int overdue, int dueToday) {

  public InboxCounts {
    if (total < 0 || overdue < 0 || dueToday < 0) {
      throw new IllegalArgumentException("Số đếm không âm");
    }
  }
}
