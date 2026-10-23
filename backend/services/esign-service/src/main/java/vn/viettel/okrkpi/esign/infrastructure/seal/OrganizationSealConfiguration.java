package vn.viettel.okrkpi.esign.infrastructure.seal;

import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.esign.application.seal.OrganizationSealService;
import vn.viettel.okrkpi.esign.application.seal.OrganizationSealer;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.CscClient;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.MySignProperties;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Lắp thao tác ký tổ chức khi {@code okrkpi.esign.org-seal.enabled=true} (DV-13-10). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrganizationSealProperties.class)
@ConditionalOnProperty(prefix = "okrkpi.esign.org-seal", name = "enabled", havingValue = "true")
public class OrganizationSealConfiguration {

  static final String INSTANCE = "esign-mysign";

  @Bean
  OrganizationSealer organizationSealer(
      OrganizationSealProperties p,
      ObjectProvider<MySignProperties> mysign,
      ObjectProvider<ResilientClientFactory> factory,
      Clock clock) {
    if ("MYSIGN".equals(p.provider())) {
      MySignProperties m = mysign.getIfAvailable();
      ResilientClientFactory f = factory.getIfAvailable();
      if (m == null || f == null) {
        throw new IllegalStateException("Ký tổ chức qua MySign cần bật okrkpi.esign.mysign");
      }
      return new CscOrganizationSealer(
          new CscClient(
              f.create(INSTANCE, m.baseUrl()), f.create(INSTANCE, m.tokenUrl()), m, clock),
          p.credentialId());
    }
    if (!"LOCAL".equals(p.provider())) {
      throw new IllegalStateException("Nhà cung cấp ký tổ chức chưa hỗ trợ: " + p.provider());
    }
    if (p.privateKeyPath() == null || p.publicKeyPath() == null) {
      throw new IllegalStateException("Thiếu tệp khoá tổ chức");
    }
    return LocalOrganizationSealer.fromPem(Path.of(p.privateKeyPath()), Path.of(p.publicKeyPath()));
  }

  @Bean
  OrganizationSealService organizationSealService(
      OrganizationSealer organizationSealer, OrganizationSealProperties p) {
    return new OrganizationSealService(organizationSealer, new HashSet<>(p.purposes()));
  }
}
