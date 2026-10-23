package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapper;
import vn.viettel.okrkpi.integ.application.outbound.ExternalRefs;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.application.port.PayloadSigner;

/**
 * Lắp bộ chuyển CloudEvents, bộ ký JWS, máy khách mTLS và nhà máy adapter chiều ra (DV-13-02). Chưa
 * cấu hình tệp khoá ký thì bộ ký từ chối ký và JWKS công bố rỗng, nên không có thông điệp nào ra
 * ngoài mà không có chữ ký.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudEventsProperties.class)
public class CloudEventsConfiguration {

  @Bean
  CloudEventsMapper integCloudEventsMapper(
      CloudEventsProperties props, ObjectProvider<ObjectMapper> json, ObjectProvider<Clock> clock) {
    return new CloudEventsMapper(
        props.cell(),
        props.dataSchemaBase(),
        json.getIfAvailable(ObjectMapper::new),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  ExternalRefs integExternalRefs(MappingXrefService xrefs, ObjectProvider<ObjectMapper> json) {
    return new ExternalRefs(xrefs, json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  PayloadSigner integPayloadSigner(CloudEventsProperties props) {
    if (props.signingJwksFile() == null || props.signingJwksFile().toString().isBlank()) {
      return UnconfiguredSigner.INSTANCE;
    }
    return new NimbusPayloadSigner(new JwkSigningKeys(props.signingJwksFile()));
  }

  @Bean
  MtlsHttpClients integMtlsHttpClients(CloudEventsProperties props) {
    return new MtlsHttpClients(props.tlsDir(), props.connectTimeout());
  }

  @Bean
  CloudEventsSenderFactory integCloudEventsSenderFactory(
      CloudEventsProperties props,
      CloudEventsMapper mapper,
      PayloadSigner signer,
      MtlsHttpClients mtls,
      ConnectorSecrets secrets,
      ObjectProvider<ObjectMapper> json) {
    HttpClient plain =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return new CloudEventsSenderFactory(
        props, mapper, signer, mtls, plain, secrets, json.getIfAvailable(ObjectMapper::new));
  }

  /** Bộ ký khi chưa cấu hình khoá: không ký, nên không gửi được; JWKS rỗng. */
  enum UnconfiguredSigner implements PayloadSigner {
    INSTANCE;

    @Override
    public String signDetached(byte[] payload) {
      throw new IllegalStateException("Chưa cấu hình khoá ký của cell");
    }

    @Override
    public String signCompact(byte[] payload) {
      throw new IllegalStateException("Chưa cấu hình khoá ký của cell");
    }

    @Override
    public String publicJwks() {
      return "{\"keys\":[]}";
    }
  }
}
