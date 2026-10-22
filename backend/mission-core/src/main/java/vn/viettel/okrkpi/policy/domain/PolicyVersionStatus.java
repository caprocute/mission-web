package vn.viettel.okrkpi.policy.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Trạng thái phiên bản Policy Pack theo baseline mục 9.1 và TL-02b QT-15 "Trạng thái đối tượng"
 * (CHECK {@code ck_policy_pack_version__status}).
 */
public enum PolicyVersionStatus {
  NHAP,
  DANG_KIEM_THU,
  DAT_KIEM_THU,
  CHO_PHE_DUYET,
  DA_PHAT_HANH,
  NGUNG;

  /** Các trạng thái kế tiếp hợp lệ; mọi sửa nội dung sau {@code DAT_KIEM_THU} quay về NHAP. */
  public Set<PolicyVersionStatus> next() {
    return switch (this) {
      case NHAP -> EnumSet.of(DANG_KIEM_THU);
      case DANG_KIEM_THU -> EnumSet.of(NHAP, DAT_KIEM_THU);
      case DAT_KIEM_THU -> EnumSet.of(NHAP, CHO_PHE_DUYET);
      case CHO_PHE_DUYET -> EnumSet.of(NHAP, DA_PHAT_HANH);
      case DA_PHAT_HANH -> EnumSet.of(NGUNG);
      case NGUNG -> EnumSet.noneOf(PolicyVersionStatus.class);
    };
  }

  public boolean canMoveTo(PolicyVersionStatus target) {
    return next().contains(target);
  }

  /** Nội dung (manifest, quy tắc, bảng quyết định) đã đóng băng, khớp trigger của TL-03. */
  public boolean frozen() {
    return this != NHAP && this != DANG_KIEM_THU;
  }
}
