package vn.viettel.okrkpi.shared.paging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Giá trị keyset của dòng cuối trang: các cột sắp xếp theo thứ tự, cột cuối là {@code id} để thứ tự
 * duy nhất (baseline mục 13 "phân trang con trỏ"). Giá trị lưu dạng chuỗi chuẩn ({@link
 * Instant#toString()}, {@link UUID#toString()}...), đọc lại bằng các hàm có kiểu.
 */
public final class CursorKey {

  private final List<String> parts;

  private CursorKey(List<String> parts) {
    this.parts = List.copyOf(parts);
  }

  /**
   * Dựng từ các giá trị cột sắp xếp; mỗi giá trị là {@link String}, {@link Number}, {@link
   * Boolean}, {@link UUID}, {@link Instant}, {@link LocalDate} hoặc {@link Enum}.
   */
  public static CursorKey of(Object... values) {
    Objects.requireNonNull(values, "values");
    if (values.length == 0) {
      throw new IllegalArgumentException("CursorKey cần ít nhất một giá trị");
    }
    List<String> out = new ArrayList<>(values.length);
    for (Object v : values) {
      out.add(stringOf(v));
    }
    return new CursorKey(out);
  }

  static CursorKey ofParts(List<String> parts) {
    if (parts.isEmpty() || parts.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("CursorKey rỗng hoặc có phần tử null");
    }
    return new CursorKey(parts);
  }

  private static String stringOf(Object v) {
    if (v instanceof String
        || v instanceof Number
        || v instanceof Boolean
        || v instanceof UUID
        || v instanceof Instant
        || v instanceof LocalDate) {
      return v.toString();
    }
    if (v instanceof Enum<?> e) {
      return e.name();
    }
    throw new IllegalArgumentException(
        "Kiểu không dùng được trong con trỏ: " + (v == null ? "null" : v.getClass().getName()));
  }

  public int size() {
    return parts.size();
  }

  List<String> parts() {
    return parts;
  }

  public String getString(int index) {
    return parts.get(index);
  }

  public long getLong(int index) {
    return Long.parseLong(parts.get(index));
  }

  public UUID getUuid(int index) {
    return UUID.fromString(parts.get(index));
  }

  public Instant getInstant(int index) {
    return Instant.parse(parts.get(index));
  }

  public LocalDate getDate(int index) {
    return LocalDate.parse(parts.get(index));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CursorKey k && parts.equals(k.parts);
  }

  @Override
  public int hashCode() {
    return parts.hashCode();
  }

  @Override
  public String toString() {
    return "CursorKey" + parts;
  }
}
