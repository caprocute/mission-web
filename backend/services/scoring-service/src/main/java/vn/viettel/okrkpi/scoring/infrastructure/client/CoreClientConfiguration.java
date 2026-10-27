package vn.viettel.okrkpi.scoring.infrastructure.client;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Client credentials của client {@code scoring-service} để gọi mission-core ({@code
 * mission.scoring.core-client.*}); bí mật đọc từ môi trường. Để trống {@code token-url} thì lời gọi
 * không kèm token (kiểm thử với máy chủ giả).
 */
@Configuration(proxyBeanMethods = false)
public class CoreClientConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(CoreClientConfiguration.class);

  @Bean
  ServiceToken coreServiceToken(
      ObjectProvider<RestClient.Builder> builders,
      @Value("${mission.scoring.core-client.token-url:}") String tokenUrl,
      @Value("${mission.scoring.core-client.client-id:scoring-service}") String clientId,
      @Value("${mission.scoring.core-client.client-secret:}") String clientSecret) {
    ServiceToken token =
        new ServiceToken(
            builders.getIfAvailable(RestClient::builder).build(),
            tokenUrl,
            clientId,
            clientSecret,
            Clock.systemUTC());
    if (token.configured()) {
      LOG.info("Gọi mission-core bằng token client dịch vụ {}", clientId);
    } else {
      LOG.warn("Chưa khai mission.scoring.core-client.token-url: gọi mission-core không kèm token");
    }
    return token;
  }
}
