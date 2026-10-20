package vn.viettel.okrkpi.platform.db.migration.lint;

/** Các luật lint migration, mỗi luật truy vết về một mục của TL-03 chương "Giới thiệu". */
public enum LintRule {
  /** Định danh không được có chữ hoa (TL-03 "Quy ước đặt tên"; trừ bảng Flowable). */
  IDENTIFIER_LOWERCASE,
  /** Khoá chính {@code pk_<bảng>}. */
  NAMING_PRIMARY_KEY,
  /** Khoá ngoại {@code fk_<bảng>__<bảng đích>}. */
  NAMING_FOREIGN_KEY,
  /** Ràng buộc duy nhất {@code uq_<bảng>__<cột>}. */
  NAMING_UNIQUE,
  /** Ràng buộc kiểm tra {@code ck_<bảng>__<nội dung>}. */
  NAMING_CHECK,
  /** Ràng buộc loại trừ {@code ex_<bảng>__<nội dung>}. */
  NAMING_EXCLUSION,
  /** Chỉ mục {@code ix_<bảng>__<cột>}. */
  NAMING_INDEX,
  /** Chính sách RLS {@code rls_<bảng>__tenant}. */
  NAMING_POLICY,
  /** Trigger {@code tg_<bảng>__<hành vi>}. */
  NAMING_TRIGGER,
  /** Hàm, thủ tục {@code fn_<động từ>_<đối tượng>}. */
  NAMING_FUNCTION,
  /** Kiểu cột nằm trong danh sách "Kiểu dữ liệu chuẩn". */
  TYPE_STANDARD,
  /** Cột {@code is_}/{@code has_} là {@code boolean NOT NULL DEFAULT false}. */
  FLAG_COLUMN,
  /** Cột {@code _at} là {@code timestamptz}. */
  TIMESTAMP_COLUMN,
  /** Mọi cột có chú thích JSON với khoá {@code cls} thuộc C0..C3. */
  CLASSIFICATION_COMMENT,
  /** Cột C2 là {@code bytea}, hậu tố {@code _enc}, bảng có {@code dek_id}. */
  CLASSIFICATION_C2_ENCRYPTED
}
