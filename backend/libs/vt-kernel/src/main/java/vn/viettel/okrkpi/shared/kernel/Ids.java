package vn.viettel.okrkpi.shared.kernel;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sinh và kiểm mã định danh UUIDv7 (TL-03 "Kiểu dữ liệu chuẩn": định danh {@code uuid} UUIDv7 sinh
 * ở ứng dụng bằng uuid-creator, vì PostgreSQL 17 chưa có hàm UUIDv7 dựng sẵn).
 *
 * <p>UUIDv7 (RFC 9562) mang 48 bit thời điểm Unix tính bằng mili giây ở đầu, nên mã sinh sau luôn
 * lớn hơn mã sinh trước khi so theo chuỗi hoặc theo byte; uuid-creator tăng phần ngẫu nhiên trong
 * cùng một mili giây để giữ thứ tự đơn điệu trong một tiến trình.
 */
public final class Ids {

  private static final int VERSION_7 = 7;
  private static final int VARIANT_RFC_9562 = 2;

  private Ids() {}

  /** Mã UUIDv7 mới, tăng dần theo thời gian. */
  public static UUID newUuidV7() {
    return UuidCreator.getTimeOrderedEpoch();
  }

  /** Đúng khi {@code id} là UUID phiên bản 7, biến thể RFC 9562. */
  public static boolean isUuidV7(UUID id) {
    return id != null && id.version() == VERSION_7 && id.variant() == VARIANT_RFC_9562;
  }

  /**
   * Thời điểm (độ phân giải mili giây) ghi trong một UUIDv7.
   *
   * @throws IllegalArgumentException khi {@code id} không phải UUIDv7
   */
  public static Instant timestampOf(UUID id) {
    Objects.requireNonNull(id, "id");
    if (!isUuidV7(id)) {
      throw new IllegalArgumentException("Không phải UUIDv7: " + id);
    }
    return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
  }

  /**
   * Đọc UUID từ chuỗi.
   *
   * @throws IllegalArgumentException khi chuỗi không đúng định dạng UUID
   */
  public static UUID parse(String value) {
    Objects.requireNonNull(value, "value");
    return UUID.fromString(value);
  }
}
