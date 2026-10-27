package vn.viettel.okrkpi.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import vn.viettel.okrkpi.shared.authz.OpaProperties;

/**
 * Địa chỉ OPA lấy từ OPA_BASE_URL của môi trường; thiếu thì mọi thao tác bị từ chối (fail-closed).
 */
class OpaConfigTest {

  @Test
  void opaBaseUrlFollowsEnvironment() throws Exception {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(new MapPropertySource("env", Map.of("OPA_BASE_URL", "http://opa:8181")));
    new YamlPropertySourceLoader()
        .load("app", new ClassPathResource("application.yaml"))
        .forEach(env.getPropertySources()::addLast);
    OpaProperties opa =
        Binder.get(env).bind("okrkpi.opa", OpaProperties.class).orElseGet(OpaProperties::new);
    assertThat(opa.getBaseUrl()).hasToString("http://opa:8181");
  }
}
