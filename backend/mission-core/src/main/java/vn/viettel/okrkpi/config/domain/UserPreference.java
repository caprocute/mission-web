package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Một khoá tuỳ chọn trình bày của người dùng ({@code config.user_preference}, CNH-02 theo đề xuất
 * CNH-01): chế độ hiển thị, lớp phủ trang chủ, cột bảng, bảng điều khiển mặc định, kênh thông báo.
 * Giá trị là JSON đã kiểm lược đồ, giữ dạng chuỗi để miền không phụ thuộc thư viện JSON.
 */
public record UserPreference(
    UUID id,
    UUID userId,
    String prefKey,
    String valueJson,
    int schemaVersion,
    Instant updatedAt,
    int rowVersion) {

  /** Khoá hợp lệ, khớp CHECK {@code ck_user_preference__pref_key}. */
  public static final Pattern KEY =
      Pattern.compile(
          "^(ui\\.display|ui\\.home\\.layout|ui\\.dashboard\\.default|ui\\.table\\.SCR-[0-9]{4}"
              + "|notify\\.channel_pref)$");

  public static final String DISPLAY = "ui.display";
  public static final String HOME_LAYOUT = "ui.home.layout";

  /** Kích thước tối đa của giá trị (byte UTF-8), khớp CHECK của bảng. */
  public static final int MAX_VALUE_BYTES = 16384;

  public UserPreference {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(prefKey, "prefKey");
    Objects.requireNonNull(valueJson, "valueJson");
  }

  /** Bản kế tiếp khi người dùng thay giá trị. */
  public UserPreference changed(String newValueJson, int newSchemaVersion, Instant at) {
    return new UserPreference(
        id, userId, prefKey, newValueJson, newSchemaVersion, at, rowVersion + 1);
  }
}
