package vn.viettel.okrkpi.config.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Trạng thái phiên bản UI Pack ({@code config.ui_pack_version.status}) theo bảng "Trạng thái đối
 * tượng" của QT-18 (TL-02b): {@code NHAP → DANG_KIEM_TRA → CHO_DUYET → DA_PHAT_HANH → THAY_THE},
 * kiểm tra lỗi hoặc bị từ chối thì về {@code NHAP}, phiên bản thay thế quay lui được.
 */
public enum UiPackVersionStatus {
  NHAP,
  DANG_KIEM_TRA,
  CHO_DUYET,
  DA_PHAT_HANH,
  THAY_THE;

  /** Các trạng thái kế tiếp hợp lệ. */
  public Set<UiPackVersionStatus> next() {
    return switch (this) {
      case NHAP -> EnumSet.of(DANG_KIEM_TRA);
      case DANG_KIEM_TRA -> EnumSet.of(NHAP, CHO_DUYET);
      case CHO_DUYET -> EnumSet.of(NHAP, DA_PHAT_HANH);
      case DA_PHAT_HANH -> EnumSet.of(THAY_THE);
      case THAY_THE -> EnumSet.of(DA_PHAT_HANH);
    };
  }

  public boolean canMoveTo(UiPackVersionStatus to) {
    return next().contains(to);
  }

  /** Phiên bản đang trong vòng soạn và duyệt (chưa phát hành lần nào). */
  public boolean inProgress() {
    return this == NHAP || this == DANG_KIEM_TRA || this == CHO_DUYET;
  }
}
