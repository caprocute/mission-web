package vn.viettel.okrkpi.org.application.sync;

import java.util.List;

/**
 * Kết quả xử lý lệch hàng loạt: từng mục và số phân công đã ghi phiên bản mới ({m} của MSG-01-13).
 *
 * @param results kết quả từng mục; mục lỗi không làm hỏng mục khác
 */
public record ResolveResult(List<Item> results, int assignmentsUpdated) {

  /** Kết quả một mục, {@code status} là mã HTTP tương đương. */
  public record Item(String id, int status, String code, String detail) {
  }
}
