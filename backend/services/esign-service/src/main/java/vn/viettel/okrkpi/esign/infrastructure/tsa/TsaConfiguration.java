package vn.viettel.okrkpi.esign.infrastructure.tsa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Lắp {@link TsaClient} khi {@code okrkpi.esign.tsa.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TsaProperties.class)
@ConditionalOnProperty(prefix = "okrkpi.esign.tsa", name = "enabled", havingValue = "true")
public class TsaConfiguration {

  /** Tiền tố instance Resilience4j; instance chưa khai báo dùng cấu hình {@code default}. */
  static final String INSTANCE_PREFIX = "esign-tsa-";

  @Bean
  TsaClient tsaClient(TsaProperties properties, ResilientClientFactory factory) {
    List<TsaClient.Endpoint> endpoints = new ArrayList<>();
    for (int i = 0; i < properties.urls().size(); i++) {
      String url = properties.urls().get(i);
      endpoints.add(new TsaClient.Endpoint(url, factory.create(INSTANCE_PREFIX + (i + 1), url)));
    }
    return new TsaClient(endpoints, loadAnchors(properties.trustAnchors()));
  }

  static List<X509Certificate> loadAnchors(List<String> files) {
    List<X509Certificate> out = new ArrayList<>();
    try {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      for (String f : files) {
        try (InputStream in = Files.newInputStream(Path.of(f))) {
          cf.generateCertificates(in).forEach(c -> out.add((X509Certificate) c));
        }
      }
    } catch (IOException | CertificateException e) {
      throw new IllegalStateException("Không đọc được neo tin cậy của máy chủ dấu thời gian", e);
    }
    return out;
  }
}
