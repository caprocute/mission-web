package vn.viettel.okrkpi.integ.infrastructure.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.RoutesBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.integ.application.alert.AdminAlerts;
import vn.viettel.okrkpi.integ.application.alert.OutboundAlerts;
import vn.viettel.okrkpi.integ.application.alert.OverdueAlertService;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Cảnh báo quản trị của chiều ra (DV-13-09, {@code okrkpi.integ.alerts}): danh sách người nhận
 * EVT-21 và các mã kết nối cần theo dõi quá hạn thử lại (mặc định kết nối CSDL CBCCVC).
 */
@Configuration(proxyBeanMethods = false)
public class AlertConfiguration {

  @Bean
  AdminAlerts integAdminAlerts(
      OutboxWriter outbox,
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<ObjectMapper> json,
      @Value("${okrkpi.integ.alerts.recipients:}") List<UUID> recipients,
      @Value("${okrkpi.integ.anchor.zone:Asia/Ho_Chi_Minh}") ZoneId zone) {
    return new OutboxAdminAlerts(
        outbox,
        meters.getIfAvailable(SimpleMeterRegistry::new),
        recipients,
        zone,
        json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  OutboundAlerts integOutboundAlerts(JdbcClient jdbc) {
    return new JdbcOutboundAlerts(jdbc);
  }

  @Bean
  OverdueAlertService integOverdueAlertService(
      OutboundAlerts store,
      AdminAlerts alerts,
      @Value("${okrkpi.integ.alerts.overdue-connectors:CBCCVC}") Set<String> codes,
      TransactionTemplate integTransactions) {
    return new OverdueAlertService(store, alerts, codes, integTransactions);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "okrkpi.integ.alerts.overdue",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  RoutesBuilder integOverdueAlertRoute(
      OverdueAlertService service,
      @Value("${okrkpi.integ.alerts.overdue.period:PT5M}") Duration period,
      @Value("${okrkpi.integ.alerts.overdue.batch:100}") int batch) {
    return new OverdueAlertRoute(service, period, batch);
  }
}
