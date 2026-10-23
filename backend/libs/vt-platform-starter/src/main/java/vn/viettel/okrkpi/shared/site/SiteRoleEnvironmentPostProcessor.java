package vn.viettel.okrkpi.shared.site;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * INF-28 · QA-15 · Đọc vai trò TTDL ({@code okrkpi.site.role}, mặc định từ biến {@code
 * VT_SITE_ROLE}) và, ở TTDL chờ, ép tắt với độ ưu tiên cao nhất (service không ghi đè được):
 * Flyway, mọi container lắng nghe Kafka (của Spring Boot và của inbox). Job {@code @Scheduled} do
 * {@link StandbySchedulingConfigurer} chặn ở lúc chạy.
 */
public class SiteRoleEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String ROLE_PROPERTY = "okrkpi.site.role";
  public static final String SITE_PROPERTY = "okrkpi.site.name";
  public static final String SOURCE_NAME = "okrkpi-site-role";

  /** Thuộc tính bị ép ở TTDL chờ. */
  public static final Map<String, Object> STANDBY_OVERRIDES =
      Map.of(
          "spring.flyway.enabled", "false",
          "spring.kafka.listener.auto-startup", "false",
          "okrkpi.inbox.listener-auto-startup", "false");

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    SiteRole role =
        SiteRole.parse(
            environment.getProperty(ROLE_PROPERTY, environment.getProperty("VT_SITE_ROLE", "")));
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(ROLE_PROPERTY, role.value());
    props.put(
        SITE_PROPERTY,
        environment.getProperty(SITE_PROPERTY, environment.getProperty("VT_SITE", "")));
    if (role == SiteRole.STANDBY) {
      props.putAll(STANDBY_OVERRIDES);
    }
    environment.getPropertySources().remove(SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
  }

  @Override
  public int getOrder() {
    // Sau ConfigDataEnvironmentPostProcessor để đọc được application.yaml.
    return Ordered.LOWEST_PRECEDENCE - 10;
  }
}
