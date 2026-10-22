package vn.viettel.okrkpi.task.domain.catalog;

/**
 * Trạng thái phiên bản danh mục theo TL-03 ({@code task.work_catalog.status}, ràng buộc {@code
 * ck_work_catalog__status}). Hợp đồng dùng tên khác; lớp ánh xạ duy nhất nằm ở adapter web.
 */
public enum CatalogStatus {
  /** Phiên bản nháp, được sửa. */
  NHAP,
  /** Phiên bản đang áp dụng; mỗi dòng danh mục có nhiều nhất một phiên bản ở trạng thái này. */
  HIEU_LUC,
  /** Phiên bản đã thay thế, chỉ xem. */
  NGUNG
}
