package vn.viettel.okrkpi.integ.infrastructure.hrm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.IndicatorSource;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.application.port.PersonnelSink;
import vn.viettel.okrkpi.integ.infrastructure.hrm.HrmProperties.Profile;

/**
 * Bộ kết nối HRM, ERP, HIS, quản lý trường học theo hồ sơ {@code okrkpi.integ.hrm.connectors}
 * (DV-13-08): hồ sơ có {@code rest} thì có route kéo nhân sự REST, có {@code batch} thì có route
 * kéo tệp lô SFTP, có {@code indicator} thì có nguồn số liệu cho KR. Nhân sự chuyển sang
 * org-service ở {@code okrkpi.integ.hrm.org.base-url}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HrmProperties.class)
@ConditionalOnProperty(
    name = "okrkpi.integ.hrm.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class HrmConfiguration {

  /** Các bộ kết nối kéo và nguồn chỉ số dựng từ hồ sơ. */
  public record HrmBeans(List<InboundConnector> connectors, List<IndicatorSource> indicators) {}

  @Bean
  PersonnelSink integPersonnelSink(
      ObjectProvider<ObjectMapper> mapper,
      ObjectProvider<Clock> clock,
      @Value("${okrkpi.integ.hrm.org.base-url:}") String baseUrl,
      @Value("${okrkpi.integ.hrm.org.token-url:}") String tokenUrl,
      @Value("${okrkpi.integ.hrm.org.client-id:integration-gateway}") String clientId,
      @Value("${okrkpi.integ.hrm.org.client-secret:}") String clientSecret,
      @Value("${okrkpi.integ.hrm.org.timeout:PT30S}") Duration timeout) {
    HttpClient http = client(timeout);
    ObjectMapper json = json(mapper);
    return new OrgServicePersonnelSink(
        http,
        json,
        baseUrl,
        new ServiceToken(
            http, json, tokenUrl, clientId, clientSecret, clock.getIfAvailable(Clock::systemUTC)),
        timeout);
  }

  @Bean
  HrmBeans integHrm(
      HrmProperties properties,
      ConnectorSecrets secrets,
      PersonnelSink sink,
      ObjectProvider<ObjectMapper> mapper) {
    ObjectMapper json = json(mapper);
    List<InboundConnector> connectors = new ArrayList<>();
    List<IndicatorSource> indicators = new ArrayList<>();
    for (Map.Entry<String, Profile> e : properties.connectors().entrySet()) {
      String code = e.getKey();
      Profile p = e.getValue();
      HrmHttp http = new HrmHttp(client(p.requestTimeout()), json, secrets);
      if (p.rest() != null && p.batch() != null) {
        throw new IllegalStateException(
            "Hồ sơ " + code + " chỉ được chọn một trong REST hoặc tệp lô cho nhân sự");
      }
      Translator translator = new HrmPersonTranslator(code, p, sink, json);
      if (p.rest() != null) {
        connectors.add(
            new RestHrmConnector(code, http, p.rest(), p.period(), p.requestTimeout(), translator));
      }
      if (p.batch() != null) {
        connectors.add(
            new SftpBatchConnector(
                code, secrets, p.batch(), p.period(), p.requestTimeout(), translator));
      }
      if (p.indicator() != null) {
        indicators.add(new RestIndicatorSource(code, http, p.indicator(), p.requestTimeout()));
      }
    }
    return new HrmBeans(List.copyOf(connectors), List.copyOf(indicators));
  }

  private static HttpClient client(Duration timeout) {
    return HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  static ObjectMapper json(ObjectProvider<ObjectMapper> mapper) {
    return mapper.getIfAvailable(
        () ->
            JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build());
  }
}
