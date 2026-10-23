package vn.viettel.okrkpi.platform.reconcile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Dạng chuẩn hoá và băm SHA-256 của một dòng đem đối soát (TL-03 "Đối soát và kiểm tra nhất quán").
 * Các cột nối bằng {@code |}; {@code null} ghi {@code -}; ký tự {@code \} và {@code |} trong giá
 * trị được thoát để hai dòng khác nhau không cho cùng một chuỗi chuẩn hoá. UUID viết chữ thường như
 * {@code UUID.toString()}, ngày theo ISO-8601.
 */
public final class RowHash {

  private RowHash() {}

  /** Chuỗi chuẩn hoá của một dòng. */
  public static String canonical(Object... columns) {
    StringJoiner out = new StringJoiner("|");
    for (Object c : columns) {
      out.add(c == null ? "-" : escape(c.toString()));
    }
    return out.toString();
  }

  /** SHA-256 của chuỗi chuẩn hoá. */
  public static byte[] sha256(String canonical) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Mã thực thể dạng chuẩn (chữ thường, bỏ khoảng trắng hai đầu) dùng để chia bucket. */
  public static String key(Object id) {
    return id.toString().trim().toLowerCase(Locale.ROOT);
  }

  private static String escape(String v) {
    if (v.indexOf('|') < 0 && v.indexOf('\\') < 0 && !"-".equals(v)) {
      return v;
    }
    return "-".equals(v) ? "\\-" : v.replace("\\", "\\\\").replace("|", "\\|");
  }
}
