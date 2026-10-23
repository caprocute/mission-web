package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.esign.application.seal.LedgerSealService;
import vn.viettel.okrkpi.esign.application.seal.TimestampAuthority;
import vn.viettel.okrkpi.esign.infrastructure.tsa.TsaClient;

/**
 * Lắp HSM PKCS#11 khi {@code okrkpi.esign.hsm.enabled=true} (DV-10-03): bộ ký, nhà cung cấp chữ ký
 * tổ chức, bộ ký niêm phong và dịch vụ niêm phong sổ cái. Chỉ báo readiness {@code hsm} luôn có
 * mặt.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({HsmProperties.class, HsmConfiguration.SealProperties.class})
public class HsmConfiguration {

  /**
   * Bên được gọi niêm phong.
   *
   * @param allowedClients {@code client_id} được phép (mặc định {@code ledger-service})
   */
  @ConfigurationProperties(prefix = "okrkpi.esign.seal")
  public record SealProperties(List<String> allowedClients) {
    public SealProperties {
      allowedClients =
          allowedClients == null || allowedClients.isEmpty()
              ? List.of("ledger-service")
              : List.copyOf(allowedClients);
    }
  }

  @Bean("hsm")
  HsmHealthIndicator hsmHealthIndicator(ObjectProvider<HsmPkcs11Signer> signer) {
    return new HsmHealthIndicator(signer);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.esign.hsm", name = "enabled", havingValue = "true")
  static class Enabled {

    @Bean(destroyMethod = "close")
    HsmPkcs11Signer hsmPkcs11Signer(HsmProperties properties, MeterRegistry meters, Clock clock) {
      HsmPkcs11Signer signer = new HsmPkcs11Signer(properties, meters, clock);
      if (properties.provisionDevKeys()) {
        signer.ensureKey(properties.orgKeyAlias(), "CN=Mission dev org sign,O=Mission dev");
        signer.ensureKey(properties.sealKeyAlias(), "CN=Mission dev ledger seal,O=Mission dev");
      }
      return signer;
    }

    @Bean
    HsmPkcs11Provider hsmPkcs11Provider(
        HsmPkcs11Signer signer,
        HsmProperties properties,
        ObjectProvider<TsaClient> tsa,
        Clock clock) {
      return new HsmPkcs11Provider(signer, properties.orgKeyAlias(), tsa.getIfAvailable(), clock);
    }

    @Bean
    LedgerSealService ledgerSealService(
        HsmPkcs11Signer signer,
        HsmProperties properties,
        SealProperties seal,
        ObjectProvider<TsaClient> tsa,
        Clock clock) {
      TimestampAuthority authority = tsa.getIfAvailable();
      if (authority == null) {
        throw new IllegalStateException(
            "Niêm phong sổ cái cần máy chủ dấu thời gian (okrkpi.esign.tsa.enabled)");
      }
      return new LedgerSealService(
          new HsmSealSigner(signer, properties.sealKeyAlias()),
          authority,
          Set.copyOf(seal.allowedClients()),
          clock);
    }
  }
}
