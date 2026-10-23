package vn.viettel.okrkpi.esign.infrastructure.provider.ca;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.esign.infrastructure.provider.csc.CscRemoteProvider;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.CscClient;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.S3ObjectStorage;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.TimestampClient;
import vn.viettel.okrkpi.esign.infrastructure.provider.trust.CertificateTrust;
import vn.viettel.okrkpi.esign.infrastructure.provider.trust.TrustAnchors;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * Lắp nhà cung cấp {@code viettel-ca} (Viettel-CA) và {@code gov-ca} (CA chuyên dùng Chính phủ) khi
 * {@code okrkpi.esign.viettel-ca.enabled} hoặc {@code okrkpi.esign.gov-ca.enabled} bật (DV-10-04).
 * Mỗi nhà cung cấp có instance Resilience4j riêng ({@code esign-viettel-ca}, {@code esign-gov-ca}),
 * kho gốc tin cậy và máy chủ OCSP, CRL riêng; máy chủ dấu thời gian dùng chung instance {@code
 * esign-tsa}.
 */
@Configuration(proxyBeanMethods = false)
public class CaProvidersConfiguration {

  public static final String VIETTEL_CA = "viettel-ca";
  public static final String GOV_CA = "gov-ca";

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "okrkpi.esign.viettel-ca", name = "enabled", havingValue = "true")
  ClosableProvider viettelCaProvider(
      Environment environment,
      ResilientClientFactory factory,
      ObjectProvider<ObjectMapper> json,
      Clock clock) {
    return remote(VIETTEL_CA, "Viettel-CA", bind(environment, VIETTEL_CA), factory, json, clock);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "okrkpi.esign.gov-ca", name = "enabled", havingValue = "true")
  ClosableProvider govCaProvider(
      Environment environment,
      ResilientClientFactory factory,
      ObjectProvider<ObjectMapper> json,
      Clock clock) {
    CaProviderProperties properties = bind(environment, GOV_CA);
    if (properties.mode() == CaProviderProperties.Mode.PKCS11) {
      // Nhánh token/HSM tại đơn vị cần bộ ký PKCS#11 của DV-10-03; chưa có thì không khởi động.
      throw new IllegalStateException(
          "okrkpi.esign.gov-ca.mode=PKCS11 cần bộ ký PKCS#11 (DV-10-03), chưa được lắp");
    }
    return remote(GOV_CA, "CA chuyên dùng Chính phủ", properties, factory, json, clock);
  }

  static CaProviderProperties bind(Environment environment, String id) {
    return Binder.get(environment)
        .bind("okrkpi.esign." + id, CaProviderProperties.class)
        .orElseThrow(() -> new IllegalStateException("Thiếu cấu hình okrkpi.esign." + id));
  }

  private static ClosableProvider remote(
      String id,
      String label,
      CaProviderProperties p,
      ResilientClientFactory factory,
      ObjectProvider<ObjectMapper> json,
      Clock clock) {
    String instance = "esign-" + id;
    CscClient csc =
        new CscClient(
            factory.create(instance, p.baseUrl()),
            factory.create(instance, p.tokenUrl()),
            new CscClient.Connection(
                p.clientId(), p.clientSecret(), p.scope(), p.paths(), p.tokenSkew()),
            clock);
    TimestampClient tsa =
        blank(p.tsaUrl()) ? null : new TimestampClient(factory.create("esign-tsa", p.tsaUrl()));
    CertificateTrust trust =
        new CertificateTrust(
            id,
            TrustAnchors.load(p.trustAnchors()),
            blank(p.ocspUrl()) ? null : factory.create("esign-ocsp-" + id, p.ocspUrl()),
            blank(p.crlUrl()) ? null : factory.create("esign-crl-" + id, p.crlUrl()),
            clock);
    S3Client s3 = S3ObjectStorage.client(p.storage());
    return new ClosableProvider(
        new CscRemoteProvider.Settings(id, label, p.signatureSize(), p.storage().signedPrefix()),
        csc,
        new S3ObjectStorage(s3, p.storage().bucket()),
        tsa,
        trust,
        json.getIfAvailable(ObjectMapper::new),
        clock,
        s3);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  /** Nhà cung cấp CSC kèm client S3 riêng, đóng khi tắt ứng dụng. */
  public static final class ClosableProvider extends CscRemoteProvider implements AutoCloseable {

    private final S3Client s3;

    ClosableProvider(
        Settings settings,
        CscClient csc,
        S3ObjectStorage storage,
        TimestampClient tsa,
        CertificateTrust trust,
        ObjectMapper json,
        Clock clock,
        S3Client s3) {
      super(settings, csc, storage, tsa, trust, json, clock);
      this.s3 = s3;
    }

    @Override
    public void close() {
      s3.close();
    }
  }
}
