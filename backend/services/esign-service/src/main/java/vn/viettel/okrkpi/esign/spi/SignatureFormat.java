package vn.viettel.okrkpi.esign.spi;

/**
 * Định dạng chữ ký theo tên của TL-01. {@code JWS} không có ở TL-01 nhưng có ở TL-03 và hợp đồng
 * (ký bản ghi JSON chuẩn hoá); {@code XADES_B_T} không có cột tương ứng ở CSDL nên chưa dùng.
 */
public enum SignatureFormat {
  PADES_B_LTA,
  CADES_B_T,
  XADES_B_T,
  JWS
}
