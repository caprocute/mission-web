package vn.viettel.okrkpi.shared.kernel;

/**
 * Phân loại dữ liệu bốn mức theo ADR-06 (baseline), cùng bộ giá trị với lược đồ {@code
 * Classification} của {@code contracts/openapi/common/common.yaml}. Cách bảo vệ từng mức (mã hoá
 * trường C2, E2EE C3) thuộc NEN-015; kiểu này chỉ là nhãn dùng chung.
 */
public enum Classification {
  /** Công khai. */
  C0,
  /** Nội bộ, dữ liệu thường (kể cả điểm số và mức xếp loại, bảo vệ bằng RLS, ABAC, sổ cái). */
  C1,
  /** Nhạy cảm: dữ liệu cá nhân, nhận xét đánh giá, khiếu nại, góp ý kiểm điểm; mã hoá trường. */
  C2,
  /** Hạn chế: mã hoá đầu cuối tại client, máy chủ chỉ lưu bản mã. */
  C3;

  /** Đúng khi mức này cao bằng hoặc hơn {@code other}. */
  public boolean isAtLeast(Classification other) {
    return compareTo(other) >= 0;
  }

  /**
   * Đúng khi dữ liệu mức này không được ghi dạng rõ vào nhật ký, cache hay chỉ mục tìm kiếm (C2,
   * C3; BACKEND.md mục 3 quy ước 9, ADR-18).
   */
  public boolean isSensitive() {
    return isAtLeast(C2);
  }
}
