package vn.viettel.okrkpi.workflow.domain.stage;

import java.util.Optional;

/** Hành vi khi một bước quá hạn (TL-02b SCR-1504 bảng trường dòng 6, khoá {@code khi_qua_han}). */
public enum OverdueAction {
  /** Nhắc lại và cho thêm một khoảng hạn bằng hạn của bước. */
  NHAC_LAI,
  /**
   * Giao cho người thay (cấp phó được uỷ quyền) khai trong {@code wf_binding}; thiếu thì lên cấp
   * trên.
   */
  CHUYEN_CAP_PHO_UY_QUYEN,
  /** Giao cho cùng vai trò ở đơn vị cấp trên gần nhất. */
  CHUYEN_CAP_TREN;

  /** Đọc mã; rỗng khi không thuộc danh mục. */
  public static Optional<OverdueAction> parse(String code) {
    for (OverdueAction a : values()) {
      if (a.name().equals(code)) {
        return Optional.of(a);
      }
    }
    return Optional.empty();
  }
}
