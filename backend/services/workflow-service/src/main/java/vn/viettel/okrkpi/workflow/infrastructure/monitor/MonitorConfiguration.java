package vn.viettel.okrkpi.workflow.infrastructure.monitor;

import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.workflow.application.monitor.MonitorSettings;
import vn.viettel.okrkpi.workflow.application.port.MonitorLedger;

/** Lắp giám sát tiến trình theo mẫu SCR-1506 (BS-01, DV-06-10). */
@Configuration(proxyBeanMethods = false)
public class MonitorConfiguration {

  static final String PREFIX = "mission.workflow.monitor";

  @Bean
  MonitorSettings workflowMonitorSettings(
      @Value("${" + PREFIX + ".cell-code:cell-shared-01}") String cellCode,
      @Value("${" + PREFIX + ".max-extension-working-days:10}") int maxExtension,
      @Value("${" + PREFIX + ".zone:Asia/Ho_Chi_Minh}") String zone) {
    return new MonitorSettings(cellCode, maxExtension, ZoneId.of(zone));
  }

  @Bean
  MonitorLedger workflowMonitorLedger(OutboxWriter outbox) {
    return new OutboxMonitorLedger(outbox);
  }
}
