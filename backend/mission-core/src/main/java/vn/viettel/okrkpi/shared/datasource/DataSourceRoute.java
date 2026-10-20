package vn.viettel.okrkpi.shared.datasource;

/** Đích định tuyến kết nối cơ sở dữ liệu: chính ({@link #RW}) hoặc bản sao đọc ({@link #RO}). */
public enum DataSourceRoute {
  /** Cụm chính đọc-ghi (Patroni leader qua PgBouncer RW). */
  RW,
  /** Bản sao chỉ đọc (Patroni replica qua PgBouncer RO). */
  RO
}
