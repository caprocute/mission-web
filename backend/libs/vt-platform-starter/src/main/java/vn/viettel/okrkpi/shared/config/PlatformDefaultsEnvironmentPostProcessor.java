package vn.viettel.okrkpi.shared.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Nạp cấu hình mặc định của thư viện nền (ADR-24: "service chỉ ghi đè có lý do") với độ ưu tiên
 * thấp nhất, sau mọi nguồn của service ({@code application.yml}, biến môi trường, tham số dòng
 * lệnh): Resilience4j nguyên văn TL-01, bảng timeout mặc định, cấu hình quan sát.
 */
public class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Tài nguyên mặc định, theo thứ tự ưu tiên giảm dần. */
  public static final List<String> RESOURCES =
      List.of(
          "vn/viettel/okrkpi/shared/resilience/resilience4j-defaults.yml",
          "vn/viettel/okrkpi/shared/resilience/timeouts-defaults.yml",
          "vn/viettel/okrkpi/shared/observability/observability-defaults.yml");

  /** Tiền tố tên nguồn thuộc tính, để tra cứu khi gỡ lỗi cấu hình. */
  public static final String SOURCE_PREFIX = "okrkpi-platform-defaults:";

  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    for (String resource : RESOURCES) {
      String name = SOURCE_PREFIX + resource;
      if (environment.getPropertySources().contains(name)) {
        continue;
      }
      try {
        for (PropertySource<?> source : loader.load(name, new ClassPathResource(resource))) {
          environment.getPropertySources().addLast(source);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Không nạp được cấu hình mặc định " + resource, e);
      }
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
