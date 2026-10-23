package vn.viettel.okrkpi.iam.vneid;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Quy tắc thuần của môi giới VNeID (DV-02-04), tách khỏi Keycloak để kiểm thử đơn vị: đọc claim, kiểm mức tài khoản
 * (BR-00-03) và băm mã chủ thể VNeID trước khi lưu liên kết, để bảng liên kết danh tính của Keycloak không chứa mã
 * gốc của VNeID (có thể trùng số định danh cá nhân).
 */
final class VneidIdentity {

  /** Độ dài mã chủ thể đã băm: 16 byte như chỉ mục mù (TL-03 "Chỉ mục mù"). */
  static final int HASH_BYTES = 16;

  private VneidIdentity() {
  }

  /** Giá trị claim dạng chuỗi; số và chuỗi đều chấp nhận, thiếu hoặc rỗng là {@code null}. */
  static String claim(Map<String, Object> claims, String name) {
    if (claims == null || name == null || name.isBlank()) {
      return null;
    }
    Object v = claims.get(name);
    if (v == null) {
      return null;
    }
    String s = String.valueOf(v).trim();
    return s.isEmpty() ? null : s;
  }

  /**
   * Mức tài khoản đạt yêu cầu khi bằng mức tối thiểu hoặc cao hơn; nhận dạng số ({@code 2}) hoặc có tiền tố chữ
   * ({@code muc2}, {@code level-2}), so theo chữ số cuối cùng (suy luận, VNeID chưa công bố tên và dạng claim).
   */
  static boolean levelAccepted(String level, int required) {
    if (level == null) {
      return false;
    }
    String digits = level.toLowerCase(Locale.ROOT).replaceAll("[^0-9]", "");
    if (digits.isEmpty() || digits.length() > 3) {
      return false;
    }
    return Integer.parseInt(digits) >= required;
  }

  /** {@code HMAC-SHA256(key, "vneid|" + realm + "|" + subject)} cắt 16 byte, dạng hex chữ thường. */
  static String hashedSubject(byte[] key, String realm, String subject) {
    if (key == null || key.length < 32) {
      throw new IllegalStateException("Thiếu khoá liên kết danh tính VNeID (tối thiểu 32 byte)");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] full = mac.doFinal(("vneid|" + realm + "|" + subject).getBytes(StandardCharsets.UTF_8));
      byte[] cut = Arrays.copyOf(full, HASH_BYTES);
      Arrays.fill(full, (byte) 0);
      return HexFormat.of().formatHex(cut);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Không tính được HMAC liên kết danh tính", e);
    }
  }
}
