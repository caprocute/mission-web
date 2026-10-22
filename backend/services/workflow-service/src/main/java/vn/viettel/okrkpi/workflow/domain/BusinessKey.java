package vn.viettel.okrkpi.workflow.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Khoá nghiệp vụ của tiến trình dạng {@code <businessType>:<businessId>} (hợp đồng {@code
 * platform.yaml} tham số {@code BusinessKey}; suy luận từ cặp {@code business_type}, {@code
 * business_id} của {@code flowable.wf_task_projection}).
 *
 * @param type loại đối tượng nghiệp vụ, ví dụ {@code OKR_OBJECTIVE}, {@code PLAN}
 * @param id mã đối tượng nghiệp vụ
 */
public record BusinessKey(String type, UUID id) {

  /** Tối đa 32 ký tự theo cột {@code business_type}. */
  private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

  public BusinessKey {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(id, "id");
    if (!TYPE.matcher(type).matches()) {
      throw new IllegalArgumentException("Loại đối tượng nghiệp vụ không hợp lệ");
    }
  }

  /** Đọc khoá dạng chuỗi; rỗng khi sai dạng. */
  public static Optional<BusinessKey> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    int sep = value.indexOf(':');
    if (sep <= 0 || sep == value.length() - 1) {
      return Optional.empty();
    }
    String type = value.substring(0, sep);
    if (!TYPE.matcher(type).matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new BusinessKey(type, UUID.fromString(value.substring(sep + 1))));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Dạng chuỗi {@code TYPE:uuid}. */
  public String value() {
    return type + ':' + id;
  }

  @Override
  public String toString() {
    return value();
  }
}
