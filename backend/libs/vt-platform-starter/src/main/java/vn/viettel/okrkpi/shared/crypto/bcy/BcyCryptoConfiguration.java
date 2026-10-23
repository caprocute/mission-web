package vn.viettel.okrkpi.shared.crypto.bcy;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import vn.viettel.okrkpi.shared.crypto.CryptoProvider;

/**
 * Chế độ mật mã Ban Cơ yếu cho bản cách ly (DV-10-05, TL-04 bảng ba mô hình phân phối, khoá {@code
 * crypto.provider}): khi {@code okrkpi.crypto.provider=bcy}, bean {@link CryptoProvider} duy nhất
 * là {@link BcyCryptoProvider}, nên nhà cung cấp JCA tiêu chuẩn không được tạo. Thiếu thư viện,
 * thiếu thiết bị hay thuật toán ngoài danh sách được phép thì ngữ cảnh không khởi động được (dịch
 * vụ không bao giờ sẵn sàng) và nhật ký nêu lý do bằng tiếng Việt.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "okrkpi.crypto", name = "provider", havingValue = "bcy")
@EnableConfigurationProperties(BcyCryptoProperties.class)
public class BcyCryptoConfiguration {

  static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");
  private static final Logger LOG = LoggerFactory.getLogger(BcyCryptoConfiguration.class);

  @Bean(destroyMethod = "close")
  public BcyCryptoProvider cryptoProvider(BcyCryptoProperties properties, Environment environment) {
    BcyCryptoLibrary library;
    try {
      library = BcyLibraryLoader.load(properties);
    } catch (BcyUnavailableException e) {
      LOG.error(
          "{}. Dịch vụ dừng khởi động, không dùng mật mã tiêu chuẩn thay thế.", e.getMessage());
      throw e;
    }
    if (library.simulated() && production(environment)) {
      library.close();
      String message =
          "Chế độ mật mã bcy: không được dùng thư viện Ban Cơ yếu giả lập ở hồ sơ production";
      LOG.error(message);
      throw new BcyUnavailableException(message);
    }
    BcyCryptoProvider provider = new BcyCryptoProvider(library);
    LOG.info("Chế độ mật mã bcy: dùng {}", provider.describe());
    return provider;
  }

  static boolean production(Environment environment) {
    return Arrays.stream(environment.getActiveProfiles())
        .map(p -> p.toLowerCase(Locale.ROOT))
        .anyMatch(PRODUCTION_PROFILES::contains);
  }
}
