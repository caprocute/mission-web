package vn.viettel.okrkpi.integ.infrastructure.truc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.OutboundSender;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;

/**
 * Bộ kết nối Trục liên thông văn bản quốc gia qua X-Road (DV-13-05): route kéo cho mỗi mã {@code
 * okrkpi.integ.truc.inbound-codes} và adapter gửi cho mỗi mã {@code
 * okrkpi.integ.truc.outbound-codes} (nơi nhận như BTC_TW, VPTW). Tắt bằng {@code
 * okrkpi.integ.truc.enabled=false} ở Deployment chính khi route Trục chạy ở Deployment riêng
 * (values {@code values-truc.yaml} của chart).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "okrkpi.integ.truc.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TrucConfiguration {

  /** Các bộ kết nối kéo và adapter gửi của Trục. */
  public record TrucBeans(List<InboundConnector> connectors, List<OutboundSender> senders) {}

  @Bean
  TrucBeans integTruc(
      ConnectorSecrets secrets,
      ObjectProvider<ObjectMapper> mapper,
      @Value("${okrkpi.integ.truc.inbound-codes:TRUC}") List<String> inbound,
      @Value("${okrkpi.integ.truc.outbound-codes:BTC_TW,VPTW}") List<String> outbound,
      @Value("${okrkpi.integ.truc.period:PT1M}") Duration period,
      @Value("${okrkpi.integ.truc.request-timeout:PT30S}") Duration timeout,
      @Value("${okrkpi.integ.truc.max-bytes:31457280}") int maxBytes) {
    ObjectMapper json = mapper.getIfAvailable(ObjectMapper::new);
    XRoadClient client =
        new XRoadClient(
            HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            secrets,
            timeout);
    TrucTranslator translator = new TrucTranslator(maxBytes);
    return new TrucBeans(
        inbound.stream()
            .filter(c -> !c.isBlank())
            .map(c -> (InboundConnector) new TrucEdocConnector(c, client, json, period, translator))
            .toList(),
        outbound.stream()
            .filter(c -> !c.isBlank())
            .map(c -> (OutboundSender) new TrucEdocSender(c, client, json))
            .toList());
  }
}
