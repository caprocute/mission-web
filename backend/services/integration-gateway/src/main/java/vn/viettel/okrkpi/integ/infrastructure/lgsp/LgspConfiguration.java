package vn.viettel.okrkpi.integ.infrastructure.lgsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.IndicatorSource;
import vn.viettel.okrkpi.integ.application.KtxhIndicatorSource;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.application.port.KtxhValues;

/**
 * Bộ kết nối LGSP nhận chỉ tiêu KT-XH (DV-13-06): route kéo cho mỗi mã {@code
 * okrkpi.integ.lgsp.codes} và nguồn số liệu cho KR đọc giá trị đã công bố. Địa chỉ, bí mật và bật
 * tắt theo tenant nằm ở {@code integ.connector}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "okrkpi.integ.lgsp.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LgspConfiguration {

  /** Các bộ kết nối kéo và nguồn số liệu KR của LGSP. */
  public record LgspBeans(List<InboundConnector> connectors, List<IndicatorSource> indicators) {}

  @Bean
  LgspBeans integLgsp(
      ConnectorSecrets secrets,
      KtxhValues values,
      TransactionTemplate integTransactions,
      ObjectProvider<ObjectMapper> mapper,
      ObjectProvider<Clock> clock,
      @Value("${okrkpi.integ.lgsp.codes:LGSP}") List<String> codes,
      @Value("${okrkpi.integ.lgsp.path:/api/chi-tieu-ktxh}") String path,
      @Value("${okrkpi.integ.lgsp.page-size:200}") int pageSize,
      @Value("${okrkpi.integ.lgsp.period:PT15M}") Duration period,
      @Value("${okrkpi.integ.lgsp.request-timeout:PT15S}") Duration timeout) {
    ObjectMapper json = mapper.getIfAvailable(ObjectMapper::new);
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    LgspIndicatorTranslator translator =
        new LgspIndicatorTranslator(values, json, clock.getIfAvailable(Clock::systemUTC));
    List<String> active = codes.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
    return new LgspBeans(
        active.stream()
            .map(
                c ->
                    (InboundConnector)
                        new LgspIndicatorConnector(
                            c,
                            http,
                            secrets,
                            json,
                            path,
                            Math.max(1, Math.min(pageSize, 1000)),
                            period,
                            timeout,
                            translator))
            .toList(),
        active.stream()
            .map(c -> (IndicatorSource) new KtxhIndicatorSource(c, values, integTransactions))
            .toList());
  }
}
