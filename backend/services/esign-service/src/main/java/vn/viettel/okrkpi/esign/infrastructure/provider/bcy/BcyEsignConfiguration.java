package vn.viettel.okrkpi.esign.infrastructure.provider.bcy;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
import vn.viettel.okrkpi.esign.infrastructure.sign.EsignConfiguration.EsignProperties;
import vn.viettel.okrkpi.esign.infrastructure.sign.FakeSignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.shared.crypto.bcy.BcyCryptoProvider;

/**
 * Lắp {@link BcySignatureProvider} ở chế độ {@code okrkpi.crypto.provider=bcy} (DV-10-05). Bean
 * {@link BcyCryptoProvider} do vt-platform-starter tạo; thiếu thư viện hoặc thiết bị thì ngữ cảnh
 * đã dừng ở đó với lý do tiếng Việt. Nhãn khe khoá ký lấy từ {@code okrkpi.esign.bcy.key-label}
 * ({@code {signer}} thay bằng mã người ký).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "okrkpi.crypto", name = "provider", havingValue = "bcy")
public class BcyEsignConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(BcyEsignConfiguration.class);

  @Bean
  BcySignatureProvider bcySignatureProvider(
      BcyCryptoProvider crypto,
      SignRequestRepository requests,
      Clock clock,
      @Value("${okrkpi.esign.bcy.key-label:ky-van-ban}") String keyLabel) {
    return new BcySignatureProvider(crypto, requests, keyLabel, clock);
  }

  /**
   * Ở chế độ {@code bcy} chỉ nhà cung cấp Ban Cơ yếu được nạp (nhà cung cấp giả lập của dev được
   * phép vì đã bị chặn ở production) và nhà cung cấp mặc định phải là {@code BAN_CO_YEU}.
   */
  @Bean
  SmartInitializingSingleton bcyEsignModeGuard(
      ObjectProvider<SignatureProvider> providers, EsignProperties properties) {
    return () -> check(providers.orderedStream().toList(), properties);
  }

  static void check(List<SignatureProvider> providers, EsignProperties properties) {
    Set<String> others = new TreeSet<>();
    providers.stream()
        .map(SignatureProvider::id)
        .filter(id -> !BcySignatureProvider.ID.equals(id) && !FakeSignatureProvider.ID.equals(id))
        .forEach(others::add);
    String reason = null;
    if (!others.isEmpty()) {
      reason = "Chế độ mật mã bcy: không được nạp nhà cung cấp chữ ký " + others;
    } else if (properties.defaultProvider() != ProviderCode.BAN_CO_YEU) {
      reason =
          "Chế độ mật mã bcy: nhà cung cấp chữ ký mặc định phải là BAN_CO_YEU"
              + " (okrkpi.esign.default-provider)";
    } else if (!BcySignatureProvider.ID.equals(
        properties.providerIds().get(ProviderCode.BAN_CO_YEU))) {
      reason = "Chế độ mật mã bcy: mã BAN_CO_YEU phải trỏ tới nhà cung cấp bcy";
    }
    if (reason != null) {
      LOG.error("{}. Dịch vụ dừng khởi động.", reason);
      throw new IllegalStateException(reason);
    }
  }
}
