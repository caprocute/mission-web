package vn.viettel.okrkpi.org.domain.sync;

/** Mức lệch (TL-02a QT-01 bước 8): chỉ mức Cao chặn kích hoạt tenant (BR-01-10). */
public enum Severity {
  /** Sai người được đánh giá hoặc sai người đánh giá: chức vụ lãnh đạo, không khớp người, đơn vị chính. */
  CAO,
  /** Sai thời điểm: điều động, nghỉ hưu chưa cập nhật. */
  VUA,
  /** Thông tin phụ. */
  THAP
}
