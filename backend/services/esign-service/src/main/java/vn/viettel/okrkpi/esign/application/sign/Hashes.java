package vn.viettel.okrkpi.esign.application.sign;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Băm SHA-256 (dấu vân tay chứng thư). */
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
