package vn.viettel.okrkpi.integ.infrastructure.alert;

import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import vn.viettel.okrkpi.integ.application.alert.OverdueAlertService;

/** Quét theo chu kỳ thông điệp ra quá hạn thử lại của các kết nối theo dõi. */
public class OverdueAlertRoute extends RouteBuilder {

  private final OverdueAlertService service;
  private final Duration period;
  private final int batch;

  public OverdueAlertRoute(OverdueAlertService service, Duration period, int batch) {
    this.service = service;
    this.period = period;
    this.batch = batch;
  }

  @Override
  public void configure() {
    from("timer:integ-outbound-overdue?period=" + period.toMillis())
        .routeId("integ-outbound-overdue")
        .process(e -> service.scan(batch));
  }
}
