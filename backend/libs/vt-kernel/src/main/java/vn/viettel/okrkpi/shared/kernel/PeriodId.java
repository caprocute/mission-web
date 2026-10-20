package vn.viettel.okrkpi.shared.kernel;

import java.util.Objects;
import java.util.UUID;

/**
 * Định danh kỳ đánh giá ({@code config.period.id}, tham số {@code period} của hợp đồng).
 *
 * @param value giá trị UUID, không rỗng
 */
public record PeriodId(UUID value) {

  public PeriodId {
    Objects.requireNonNull(value, "PeriodId.value");
  }

  public static PeriodId of(UUID value) {
    return new PeriodId(value);
  }

  public static PeriodId parse(String value) {
    return new PeriodId(Ids.parse(value));
  }

  /** Kỳ mới với mã UUIDv7. */
  public static PeriodId newId() {
    return new PeriodId(Ids.newUuidV7());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
