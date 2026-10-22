package vn.viettel.okrkpi.config.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Trạng thái lệnh khoá kỳ ({@code config.period_lock_order.status}, TL-03, QT-08). Lệnh lập qua
 * {@code createPeriodLockOrder} vào thẳng {@code CHO_DUYET}; {@code NHAP} dành cho bản nháp chưa
 * gửi duyệt (chưa có thao tác nào tạo, suy luận).
 */
public enum LockOrderStatus {
  NHAP,
  CHO_DUYET,
  DA_DUYET,
  DANG_CHAY,
  HOAN_TAT,
  DA_HUY;

  /** Chuyển hợp lệ: lập → duyệt → khởi chạy → hoàn tất; huỷ được trước khi hoàn tất. */
  public Set<LockOrderStatus> next() {
    return switch (this) {
      case NHAP -> EnumSet.of(CHO_DUYET, DA_HUY);
      case CHO_DUYET -> EnumSet.of(DA_DUYET, DA_HUY);
      case DA_DUYET -> EnumSet.of(DANG_CHAY, DA_HUY);
      case DANG_CHAY -> EnumSet.of(HOAN_TAT, DA_HUY);
      case HOAN_TAT, DA_HUY -> EnumSet.noneOf(LockOrderStatus.class);
    };
  }

  public boolean canMoveTo(LockOrderStatus target) {
    return next().contains(target);
  }

  /** Lệnh còn hiệu lực (chiếm chỗ "một lệnh chưa huỷ mỗi kỳ"). */
  public boolean active() {
    return this != DA_HUY;
  }
}
