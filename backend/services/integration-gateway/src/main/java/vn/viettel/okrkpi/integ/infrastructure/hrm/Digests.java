package vn.viettel.okrkpi.integ.infrastructure.hrm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Băm SHA-256 dạng hex làm mã thông điệp chống trùng theo nội dung. */
final class Digests {

  private Digests() {}

  static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
