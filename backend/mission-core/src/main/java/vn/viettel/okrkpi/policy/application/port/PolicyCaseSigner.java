package vn.viettel.okrkpi.policy.application.port;

/**
 * Ký số nội dung ca kiểm thử vàng đã chuẩn hoá RFC 8785 (CORE-05-06, BR-15-08). Hiện thực tạm dùng
 * khoá nền tảng ES256; chuyển sang ký qua SVC-10 khi nhà cung cấp ký từ xa sẵn sàng (DV-10-02, suy
 * luận, wiki trang 14).
 */
public interface PolicyCaseSigner {

  /** Mã khoá đang ký. */
  String keyId();

  byte[] sign(byte[] data);
}
