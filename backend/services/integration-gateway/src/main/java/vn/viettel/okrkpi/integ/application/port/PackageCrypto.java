package vn.viettel.okrkpi.integ.application.port;

import java.security.PublicKey;
import java.util.UUID;

/**
 * Mã hoá gói (SEC-20): khoá nội dung ngẫu nhiên AES-256-GCM, bọc bằng khoá công khai của cell nhận;
 * ký và kiểm chữ ký của cell. Khoá nội dung không rời khỏi bộ nhớ của lời gọi.
 */
public interface PackageCrypto {

  /** Bản mã và khoá nội dung đã bọc. */
  record Sealed(byte[] ciphertext, byte[] wrappedKey) {}

  /** Gói không giải được (khoá bọc sai, bản mã bị sửa). */
  final class InvalidPackageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidPackageException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  Sealed seal(UUID packageId, byte[] plain, PublicKey recipient);

  /** Mở bằng khoá riêng nhận gói của cell này. */
  byte[] open(UUID packageId, byte[] ciphertext, byte[] wrappedKey);

  /** Ký bằng khoá ký của cell này (chữ ký tổ chức của cell). */
  byte[] sign(byte[] data);

  boolean verify(byte[] data, byte[] signature, PublicKey signer);
}
