package vn.viettel.okrkpi.analytics.infrastructure.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.shared.cache.TenantCache;
import vn.viettel.okrkpi.shared.cache.ValkeyTenantCache;

/**
 * Kết nối Valkey của cell cho {@link TenantCache} (NEN-035). Đây là chỗ duy nhất của service chạm
 * client Lettuce; mã nghiệp vụ chỉ thấy {@link TenantCache}. Valkey dev chạy một nút (standalone).
 */
@Configuration(proxyBeanMethods = false)
public class ValkeyConfiguration {

  @Bean(destroyMethod = "shutdown")
  RedisClient analyticsValkeyClient(
      @Value("${mission.analytics.valkey-url}") String url,
      @Value("${mission.analytics.valkey-password:}") String password) {
    RedisURI uri = RedisURI.create(url);
    if (password != null && !password.isBlank()) {
      uri.setPassword(password.toCharArray());
    }
    uri.setTimeout(Duration.ofSeconds(2));
    return RedisClient.create(uri);
  }

  @Bean(destroyMethod = "close")
  StatefulRedisConnection<String, String> analyticsValkeyConnection(RedisClient client) {
    return client.connect(StringCodec.UTF8);
  }

  @Bean
  TenantCache tenantCache(StatefulRedisConnection<String, String> connection) {
    return ValkeyTenantCache.standalone(connection);
  }
}
