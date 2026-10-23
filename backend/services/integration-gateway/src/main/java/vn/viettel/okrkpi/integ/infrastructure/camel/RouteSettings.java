package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thông số khung route (TL-01 "Lớp chống ô nhiễm", DV-13-01): thư chết sau 5 lần thử, trễ 2.000 ms,
 * nhân 2; circuit breaker tỷ lệ lỗi 50% trên cửa sổ 20 lời gọi, mở 30 giây, quá hạn 5.000 ms.
 */
@ConfigurationProperties(prefix = "okrkpi.integ.route")
public record RouteSettings(
    Integer maxRedeliveries,
    Duration redeliveryDelay,
    Double backOffMultiplier,
    Float failureRateThreshold,
    Integer slidingWindowSize,
    Duration waitInOpenState,
    Duration callTimeout) {

  public RouteSettings {
    maxRedeliveries = maxRedeliveries == null ? 5 : maxRedeliveries;
    redeliveryDelay = redeliveryDelay == null ? Duration.ofMillis(2000) : redeliveryDelay;
    backOffMultiplier = backOffMultiplier == null ? 2.0 : backOffMultiplier;
    failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
    slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
    waitInOpenState = waitInOpenState == null ? Duration.ofSeconds(30) : waitInOpenState;
    callTimeout = callTimeout == null ? Duration.ofMillis(5000) : callTimeout;
  }

  public static RouteSettings defaults() {
    return new RouteSettings(null, null, null, null, null, null, null);
  }
}
