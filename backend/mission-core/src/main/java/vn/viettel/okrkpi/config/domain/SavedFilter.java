package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bộ lọc danh sách đã đặt tên của người dùng ({@code config.saved_filter}, CNH-02). Điều kiện lọc
 * chỉ gồm mã, mã định danh và khoảng giá trị (C1), không có văn bản tự do.
 */
public record SavedFilter(
    UUID id,
    UUID userId,
    String screenCode,
    String name,
    String criteriaJson,
    boolean isDefault,
    int displayOrder,
    Instant updatedAt,
    int rowVersion) {

  public static final Pattern SCREEN = Pattern.compile("^SCR-[0-9]{4}$");

  /** Số bộ lọc tối đa trên một màn hình của một người (đề xuất CNH-01). */
  public static final int MAX_PER_SCREEN = 20;

  public static final int MAX_NAME_LENGTH = 120;

  public static final int MAX_CRITERIA_BYTES = 8192;

  public SavedFilter {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(screenCode, "screenCode");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(criteriaJson, "criteriaJson");
  }

  /** Bản kế tiếp khi người dùng sửa bộ lọc. */
  public SavedFilter changed(
      String newName, String newCriteria, boolean newDefault, int newOrder, Instant at) {
    return new SavedFilter(
        id, userId, screenCode, newName, newCriteria, newDefault, newOrder, at, rowVersion + 1);
  }
}
