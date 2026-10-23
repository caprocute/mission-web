package vn.viettel.okrkpi.integ.domain;

/** Loại thực thể được ánh xạ mã ngoài với mã nội bộ ({@code integ.mapping_xref.entity_type}). */
public enum EntityType {
  DON_VI,
  NHAN_SU,
  VAN_BAN,
  /** Chỉ số chuyên môn ở hệ nguồn ↔ KR nội bộ (DV-13-08, BR-03-04). */
  CHI_SO,
  /** Mã chức danh ở HRM ↔ chức danh nội bộ (DV-13-08). */
  CHUC_DANH
}
