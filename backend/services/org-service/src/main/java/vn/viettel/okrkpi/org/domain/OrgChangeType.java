package vn.viettel.okrkpi.org.domain;

/**
 * Loại thay đổi tổ chức theo hợp đồng ({@code OrgChangeType}). TL-03 CHECK {@code org.org_change_order.kind} chưa có
 * THANH_LAP, DOI_TEN, HOP_NHAT; không tự thêm giá trị vào CHECK mà ánh xạ sang giá trị gần nhất và giữ loại thật
 * trong {@code payload.changeType} (ghi vào wiki trang 14 để người phụ trách chốt).
 */
public enum OrgChangeType {
  THANH_LAP("DINH_CHINH"),
  DOI_TEN("DINH_CHINH"),
  SAP_NHAP("SAP_NHAP"),
  HOP_NHAT("SAP_NHAP"),
  CHIA_TACH("CHIA_TACH"),
  GIAI_THE("GIAI_THE");

  private final String orderKind;

  OrgChangeType(String orderKind) {
    this.orderKind = orderKind;
  }

  /** Giá trị ghi vào cột {@code kind} (thuộc CHECK của TL-03). */
  public String orderKind() {
    return orderKind;
  }

  /** BR-01-07: sáp nhập, hợp nhất phải khai báo đơn vị tiền thân. */
  public boolean requiresPredecessor() {
    return this == SAP_NHAP || this == HOP_NHAT;
  }
}
