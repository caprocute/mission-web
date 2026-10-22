package vn.viettel.okrkpi.esign.domain.sign;

/**
 * Định dạng chữ ký lưu ở CSDL và trả qua API ({@code esign.sign_item.signature_format}, TL-03 và
 * hợp đồng). Giao diện nhà cung cấp dùng tên của TL-01; việc ánh xạ nằm ở tầng ứng dụng.
 */
public enum SignatureFormat {
  PADES_LTA,
  CADES_T,
  JWS
}
