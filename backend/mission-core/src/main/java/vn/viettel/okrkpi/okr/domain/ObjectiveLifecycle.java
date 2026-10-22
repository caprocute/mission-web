package vn.viettel.okrkpi.okr.domain;

import java.util.Optional;

/**
 * Bảng chuyển trạng thái của {@code okr.objective} theo QT-02 "Trạng thái đối tượng" (TL-02a):
 * trình {@code NHAP → CHO_DUYET}; trả lại hoặc rút lại {@code CHO_DUYET → NHAP}; phê duyệt {@code
 * CHO_DUYET → DA_DUYET}; người duyệt huỷ có lý do {@code CHO_DUYET|DA_DUYET → DONG}. Điều chỉnh
 * giữa kỳ (phiên bản mới) và đóng do saga khoá kỳ thuộc CORE-01-07, CORE-08.
 */
public final class ObjectiveLifecycle {

  public static final String NHAP = "NHAP";
  public static final String CHO_DUYET = "CHO_DUYET";
  public static final String DA_DUYET = "DA_DUYET";
  public static final String DONG = "DONG";

  /** Sự kiện của người dùng làm đổi trạng thái mục tiêu. */
  public enum Event {
    TRINH,
    DUYET,
    TRA_LAI,
    RUT_LAI,
    HUY
  }

  private ObjectiveLifecycle() {}

  /** Trạng thái mới khi {@code event} xảy ra ở {@code current}, rỗng khi không được phép. */
  public static Optional<String> next(String current, Event event) {
    String to =
        switch (event) {
          case TRINH -> NHAP.equals(current) ? CHO_DUYET : null;
          case DUYET -> CHO_DUYET.equals(current) ? DA_DUYET : null;
          case TRA_LAI, RUT_LAI -> CHO_DUYET.equals(current) ? NHAP : null;
          case HUY -> CHO_DUYET.equals(current) || DA_DUYET.equals(current) ? DONG : null;
        };
    return Optional.ofNullable(to);
  }
}
