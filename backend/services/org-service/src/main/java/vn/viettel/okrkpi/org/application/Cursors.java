package vn.viettel.okrkpi.org.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;

/** Con trỏ mờ: các khoá sắp xếp nối bằng ký tự phân cách rồi mã base64url. */
public final class Cursors {

  private static final String SEP = "\u001f";

  private Cursors() {
  }

  public static String encode(String... keys) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(String.join(SEP, keys).getBytes(StandardCharsets.UTF_8));
  }

  public static List<String> decode(String cursor, int parts) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      List<String> keys = List.of(raw.split(SEP, -1));
      if (keys.size() != parts) {
        throw new IllegalArgumentException("số phần");
      }
      return keys;
    } catch (IllegalArgumentException e) {
      throw new OrgRuleViolation("COMMON-422-VALIDATION", null, null, "cursor", "Con trỏ trang không hợp lệ.");
    }
  }
}
