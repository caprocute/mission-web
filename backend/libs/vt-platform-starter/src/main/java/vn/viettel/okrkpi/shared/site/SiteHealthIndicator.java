package vn.viettel.okrkpi.shared.site;

import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * INF-28 · QA-15 · Báo vai trò TTDL qua Actuator ({@code /actuator/health/site}). Luôn UP: ở TTDL
 * chờ việc tạm dừng là chủ ý, không phải sự cố; chi tiết {@code paused} liệt kê phần đang dừng để
 * standby-check và người trực đối chiếu khi chuyển vùng.
 */
public class SiteHealthIndicator implements HealthIndicator {

  static final List<String> PAUSED = List.of("kafka-consumer", "scheduler", "flyway");

  private final SiteProperties properties;

  public SiteHealthIndicator(SiteProperties properties) {
    this.properties = properties;
  }

  @Override
  public Health health() {
    return Health.up()
        .withDetail("role", properties.role())
        .withDetail("site", properties.name())
        .withDetail("paused", properties.standby() ? PAUSED : List.of())
        .build();
  }
}
