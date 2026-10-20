package vn.viettel.okrkpi.shared.kernel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Đồng hồ của hệ thống cho lớp domain và application (TL-01 gói {@code shared.kernel}). Thời điểm
 * luôn là UTC (TL-03: {@code timestamptz} lưu UTC); ngày nghiệp vụ tính theo múi giờ {@link
 * #BUSINESS_ZONE}. Mã nghiệp vụ nhận {@code Clock} qua hàm dựng thay vì gọi {@code Instant.now()},
 * để kiểm thử thay bằng {@link #fixed(Instant)} hoặc một lambda.
 */
@FunctionalInterface
public interface Clock {

  /** Múi giờ hiển thị và tính ngày nghiệp vụ (TL-03 "Kiểu dữ liệu chuẩn"). */
  ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  /** Thời điểm hiện tại (UTC). */
  Instant instant();

  /** Thời điểm hiện tại dạng {@link OffsetDateTime} ở UTC. */
  default OffsetDateTime nowUtc() {
    return instant().atOffset(ZoneOffset.UTC);
  }

  /** Ngày lịch hiện tại ở múi giờ cho trước. */
  default LocalDate today(ZoneId zone) {
    return LocalDate.ofInstant(instant(), Objects.requireNonNull(zone, "zone"));
  }

  /** Ngày nghiệp vụ hiện tại theo {@link #BUSINESS_ZONE}. */
  default LocalDate businessDate() {
    return today(BUSINESS_ZONE);
  }

  /** Bản chuyển sang {@link java.time.Clock} ở UTC cho thư viện cần kiểu chuẩn. */
  default java.time.Clock toJavaClock() {
    Clock self = this;
    return new java.time.Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public java.time.Clock withZone(ZoneId zone) {
        return java.time.Clock.fixed(self.instant(), zone);
      }

      @Override
      public Instant instant() {
        return self.instant();
      }
    };
  }

  /** Đồng hồ thật của máy (UTC). */
  static Clock systemUtc() {
    return Instant::now;
  }

  /** Đồng hồ đứng yên, dùng trong kiểm thử. */
  static Clock fixed(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return () -> instant;
  }

  /** Bọc một {@link java.time.Clock} sẵn có. */
  static Clock of(java.time.Clock clock) {
    Objects.requireNonNull(clock, "clock");
    return clock::instant;
  }
}
