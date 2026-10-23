package vn.viettel.okrkpi.integ.application.anchor;

/**
 * Nơi neo gốc cell, theo bộ giá trị {@code ledger.anchor_receipt.anchor_target} của TL-03 trừ
 * {@code OBJECT_LOCK} (ledger-service tự ghi vào bucket Object Lock, INF-12). Ánh xạ đích của TL-01
 * (L2607): hệ thống cơ quan chủ quản qua LGSP là {@link #KHO_VPTW}, lưu trữ của đơn vị kiểm toán
 * độc lập hoặc TSA thứ hai là {@link #TSA_DOC_LAP}, cell khác là {@link #CELL_KHAC} (suy luận, wiki
 * trang 14).
 */
public enum AnchorTarget {
  CELL_KHAC,
  KHO_VPTW,
  TSA_DOC_LAP
}
