package vn.viettel.okrkpi.shared.outbox;

import vn.viettel.okrkpi.shared.kernel.Classification;

/**
 * Từ chối phát sự kiện mang dữ liệu C2/C3 (TL-03 "Bảng outbox_event", "Lược đồ sự kiện": không sự
 * kiện nào mang nội dung C2; khi cần, sự kiện chỉ mang mã tham chiếu).
 */
public class SensitiveEventException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final Classification classification;

  public SensitiveEventException(String topic, Classification classification) {
    super(
        "Sự kiện "
            + topic
            + " có phân loại "
            + classification
            + ": không được phát dữ liệu C2/C3 qua outbox, chỉ phát mã tham chiếu");
    this.classification = classification;
  }

  public Classification classification() {
    return classification;
  }
}
