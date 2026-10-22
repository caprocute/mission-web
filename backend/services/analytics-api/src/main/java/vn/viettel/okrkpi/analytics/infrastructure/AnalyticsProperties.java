package vn.viettel.okrkpi.analytics.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình {@code mission.analytics.*} của analytics-api; bí mật chỉ đặt bằng biến môi trường. */
@ConfigurationProperties("mission.analytics")
public record AnalyticsProperties(
    String cubeUrl,
    String cubeApiSecret,
    Duration cubeTimeout,
    String clickhouseUrl,
    String clickhouseDatabase,
    String clickhouseUsername,
    String clickhousePassword,
    String valkeyUrl,
    String valkeyPassword,
    Duration cacheTtl,
    Duration staleAfter,
    Duration modelRefresh,
    String coreUrl,
    String tokenUrl,
    String clientId,
    String clientSecret) {

  public AnalyticsProperties {
    cubeTimeout = cubeTimeout == null ? Duration.ofSeconds(20) : cubeTimeout;
    cacheTtl = cacheTtl == null ? Duration.ofSeconds(60) : cacheTtl;
    staleAfter = staleAfter == null ? Duration.ofMinutes(5) : staleAfter;
    modelRefresh = modelRefresh == null ? Duration.ofMinutes(5) : modelRefresh;
  }
}
