package vn.viettel.okrkpi.org.infrastructure.crypto;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.BlindIndexer;
import vn.viettel.okrkpi.shared.crypto.CryptoProperties;

/**
 * Mã hoá trường C2: thư viện NEN-015 khi đã cấu hình xác thực OpenBao ({@code okrkpi.crypto.token-file} hoặc
 * {@code okrkpi.crypto.kubernetes-role}); chưa cấu hình thì từ chối mọi thao tác, không bao giờ ghi cột C2 dạng rõ.
 */
@Configuration(proxyBeanMethods = false)
public class FieldEncryptorConfig {

  @Bean
  FieldEncryptor orgFieldEncryptor(ObjectProvider<CryptoProperties> properties,
      ObjectProvider<vn.viettel.okrkpi.shared.crypto.FieldEncryptor> encryptor, ObjectProvider<BlindIndexer> indexer) {
    CryptoProperties p = properties.getIfAvailable();
    boolean configured = p != null && p.isEnabled()
        && (p.getTokenFile() != null || (p.getKubernetesRole() != null && !p.getKubernetesRole().isBlank()));
    if (!configured || encryptor.getIfAvailable() == null || indexer.getIfAvailable() == null) {
      return new UnavailableFieldEncryptor();
    }
    return new KeyringFieldEncryptor(encryptor.getObject(), indexer.getObject());
  }
}
