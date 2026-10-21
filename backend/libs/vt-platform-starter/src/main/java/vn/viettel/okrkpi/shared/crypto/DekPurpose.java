package vn.viettel.okrkpi.shared.crypto;

/**
 * Mục đích sử dụng khoá dữ liệu (DEK) theo TL-03 bảng {@code keyring.dek} ({@code CHECK (purpose IN
 * (...))}).
 *
 * <p>Lưu ý điểm lệch (NEN-015): TL-01 nêu ví dụ tên tiếng Anh ({@code pii}, {@code
 * appraisal-comment}, {@code complaint}), còn TL-03 chốt danh mục cố định trong ràng buộc CHECK của
 * {@code keyring.dek.purpose}; mã nguồn bám theo TL-03.
 */
public enum DekPurpose {
  /** Nhận xét đánh giá cán bộ ({@code appraisal}). */
  NHAN_XET_DANH_GIA,
  /** Nội dung khiếu nại, kiến nghị. */
  KHIEU_NAI,
  /** Góp ý, bản tự kiểm điểm. */
  GOP_Y_KIEM_DIEM,
  /** Định danh cá nhân nhạy cảm (CCCD, số điện thoại, tài khoản...). */
  DINH_DANH_CA_NHAN,
  /** Tệp minh chứng nhạy cảm mức C2. */
  TEP_MINH_CHUNG,
  /** Khoá gốc sinh khoá HMAC cho chỉ mục mù ({@code <cột>_bidx}). */
  BLIND_INDEX
}
