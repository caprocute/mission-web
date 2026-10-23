package vn.viettel.okrkpi.integ.infrastructure.anchor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.apache.camel.RoutesBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;
import vn.viettel.okrkpi.integ.application.OutboundSender;
import vn.viettel.okrkpi.integ.application.alert.AdminAlerts;
import vn.viettel.okrkpi.integ.application.anchor.AnchorRecords;
import vn.viettel.okrkpi.integ.application.anchor.AnchorService;
import vn.viettel.okrkpi.integ.application.anchor.AnchorSettings;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.infrastructure.camel.CompositeRoutes;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventsProperties;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventsSenderFactory;

/**
 * Lắp luồng neo (DV-13-09) khi {@code okrkpi.integ.anchor.enabled=true}: dịch vụ neo, cổng
 * ledger-service, consumer EVT-19, việc theo lịch, và một adapter CloudEvents chiều ra cho mỗi đích
 * khai báo. Mỗi đích là một kết nối riêng của tenant vận hành (địa chỉ, chứng thư mTLS, chính sách
 * chia sẻ theo mã kết nối như DV-13-02).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "okrkpi.integ.anchor", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AnchorProperties.class)
public class AnchorConfiguration {

  @Bean
  AnchorRecords integAnchorRecords(JdbcClient jdbc) {
    return new JdbcAnchorRecords(jdbc);
  }

  @Bean
  AnchorService integAnchorService(
      AnchorProperties props,
      CloudEventsProperties cloudEvents,
      AnchorRecords records,
      OutboundPipeline pipeline,
      Connectors connectors,
      AdminAlerts alerts,
      CloudEventsSenderFactory senders,
      ConnectorSecrets secrets,
      ObjectProvider<ObjectMapper> json,
      TransactionTemplate integTransactions,
      ObjectProvider<Clock> clock) {
    AnchorSettings settings = props.settings(cloudEvents.cell());
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    HttpAnchorLedger ledger =
        new HttpAnchorLedger(
            props.ledgerUrl(),
            HttpClient.newBuilder()
                .connectTimeout(cloudEvents.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            props.ledgerTimeout(),
            () ->
                props.ledgerTokenPath() == null
                    ? Optional.empty()
                    : secrets.token(props.ledgerTokenPath()),
            mapper);
    return new AnchorService(
        settings,
        records,
        ledger,
        pipeline,
        connectors,
        alerts,
        senders.mapper(),
        senders::policy,
        mapper,
        integTransactions,
        clock.getIfAvailable(Clock::systemUTC));
  }

  /**
   * Một adapter chiều ra cho mỗi đích khai báo ở {@code okrkpi.integ.anchor.targets}, đăng ký thành
   * bean {@link OutboundSender} để pipeline dùng chung lịch thử lại.
   */
  @Bean
  static AnchorSenderRegistrar integAnchorSenderRegistrar() {
    return new AnchorSenderRegistrar();
  }

  @Bean
  RoutesBuilder integAnchorRoutes(
      AnchorService anchors,
      AnchorProperties props,
      @Value("${mission.service.kafka.bootstrap-servers}") String brokers,
      @Value("${okrkpi.integ.anchor.consumer.enabled:true}") boolean consume) {
    AnchorJobsRoute jobs = new AnchorJobsRoute(anchors, props.period(), props.batch());
    return consume
        ? new CompositeRoutes(List.of(new AnchorSealRoute(anchors, brokers), jobs))
        : jobs;
  }
}
