package vn.viettel.okrkpi.docgen.application.render;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 dùng cho băm dữ liệu, tệp mẫu và tệp kết quả. */
public final class Hashes {

  private Hashes() {}

  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }
}
