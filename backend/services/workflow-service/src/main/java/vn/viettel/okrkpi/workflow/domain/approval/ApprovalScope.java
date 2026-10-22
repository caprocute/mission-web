package vn.viettel.okrkpi.workflow.domain.approval;

import java.util.Optional;

/**
 * Phạm vi phân giải đơn vị duyệt từ cây tổ chức (đầu ra {@code scope} của DMN {@code
 * chon-cap-duyet}, TL-01 G2 "Ví dụ DMN chọn cấp duyệt").
 */
public enum ApprovalScope {
  /** Đơn vị trực tiếp của đối tượng được duyệt. */
  DON_VI_TRUC_TIEP,
  /** Tổ chức Đảng gần nhất phía trên trên cây Đảng (cấp uỷ có thẩm quyền). */
  CAP_UY_CO_THAM_QUYEN,
  /** Đơn vị cấp trên trực tiếp trên cây của luồng. */
  CAP_TREN_TRUC_TIEP,
  /** Đơn vị quản lý trực tiếp trên cây chuyên môn (người đứng đầu thì lên cấp trên). */
  QUAN_LY_TRUC_TIEP;

  /** Đọc mã phạm vi; rỗng khi không thuộc danh mục. */
  public static Optional<ApprovalScope> parse(String code) {
    for (ApprovalScope scope : values()) {
      if (scope.name().equals(code)) {
        return Optional.of(scope);
      }
    }
    return Optional.empty();
  }
}
