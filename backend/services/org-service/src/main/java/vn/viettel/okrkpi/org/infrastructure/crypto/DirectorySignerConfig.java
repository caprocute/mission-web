package vn.viettel.okrkpi.org.infrastructure.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.org.application.port.DirectorySigner;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;

/**
 * Bộ ký danh bạ khoá C3: OpenBao Transit khi có {@code okrkpi.org.directory-signer.base-url}; chưa cấu hình thì từ
 * chối ký (503), không bao giờ ký bằng khoá đặt trong mã.
 */
@Configuration(proxyBeanMethods = false)
public class DirectorySignerConfig {

  @Bean
  @ConditionalOnMissingBean(DirectorySigner.class)
  DirectorySigner directorySigner(ObjectMapper json,
      @Value("${okrkpi.org.directory-signer.base-url:}") String baseUrl,
      @Value("${okrkpi.org.directory-signer.mount:transit}") String mount,
      @Value("${okrkpi.org.directory-signer.key-prefix:org-directory-}") String keyPrefix,
      @Value("${okrkpi.org.directory-signer.token-file:/vault/secrets/token}") String tokenFile,
      @Value("${okrkpi.org.directory-signer.auto-create:false}") boolean autoCreate) {
    if (baseUrl.isBlank()) {
      return new Unavailable();
    }
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    return new OpenBaoDirectorySigner(http, json, URI.create(baseUrl), mount, keyPrefix, Path.of(tokenFile),
        autoCreate);
  }

  static final class Unavailable implements DirectorySigner {

    private static final String REASON = "Chưa cấu hình khoá ký danh bạ khoá công khai.";

    @Override
    public Signed sign(UUID tenantId, byte[] payload) {
      throw new IntegrationUnavailableException(REASON, null);
    }

    @Override
    public List<VerificationKey> verificationKeys(UUID tenantId) {
      throw new IntegrationUnavailableException(REASON, null);
    }
  }
}
