package vn.viettel.okrkpi.config.application.port;

/**
 * Ký gói UI Pack bằng khoá nền tảng (CORE-08-11, X-G1-05): ECDSA P-256 với SHA-256, chữ ký dạng
 * {@code r || s} (IEEE P1363) để shell kiểm được bằng WebCrypto với khoá công khai nền tảng.
 */
public interface UiPackSigner {

  /** Thuật toán, ghi kèm gói để bên kiểm biết cách kiểm. */
  String ALGORITHM = "ES256";

  /** Mã khoá đang ký. */
  String keyId();

  byte[] sign(byte[] data);

  /** Khoá công khai X.509 SubjectPublicKeyInfo (DER), để kiểm thử và phát cho shell. */
  byte[] publicKey();
}
