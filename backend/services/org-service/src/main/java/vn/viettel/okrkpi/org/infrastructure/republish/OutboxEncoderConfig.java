package vn.viettel.okrkpi.org.infrastructure.republish;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Bộ mã hoá Avro qua Apicurio cho sự kiện outbox đi topic dùng chung ({@code sys.audit.v1}) khi service chạy web. Hồ
 * sơ {@code republish} đã có bộ mã hoá riêng ở {@link RepublishConfig}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!republish")
public class OutboxEncoderConfig {

  @Bean(destroyMethod = "close")
  PayloadEncoder outboxPayloadEncoder(
      @Value("${okrkpi.org.outbox.registry-url:${APICURIO_REGISTRY_URL:http://localhost:8080/apis/registry/v3}}")
      String registryUrl) {
    return new ApicurioPayloadEncoder(registryUrl, null);
  }
}
