package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Ký bằng khoá của tổ chức nằm trong HSM qua PKCS#11 (DV-10-03, ADR-17). Khoá riêng không bao giờ
 * rời HSM: bên gọi chỉ đưa băm và nhận chữ ký. Dùng chung cho nhà cung cấp {@code hsm}, dịch vụ ký
 * niêm phong sổ cái và nhánh token/HSM của các nhà cung cấp khác (DV-10-04).
 */
public interface Pkcs11Signer {

  /** Thuật toán duy nhất hỗ trợ: ECDSA P-256 trên băm SHA-256, chữ ký dạng DER. */
  String SHA256_WITH_ECDSA = "SHA256withECDSA";

  /** Chuỗi chứng thư của khoá {@code keyAlias}, chứng thư của khoá đứng đầu. */
  List<X509Certificate> chain(String keyAlias);

  /**
   * Chữ ký DER ({@code ECDSA-Sig-Value}) trên băm {@code digest} 32 byte.
   *
   * @param algorithm phải là {@link #SHA256_WITH_ECDSA}
   * @throws IllegalArgumentException khi thuật toán hoặc độ dài băm sai, hoặc không có khoá
   * @throws HsmUnavailableException khi HSM không phục vụ được (hết phiên, mất kết nối)
   */
  byte[] sign(String keyAlias, byte[] digest, String algorithm);
}
