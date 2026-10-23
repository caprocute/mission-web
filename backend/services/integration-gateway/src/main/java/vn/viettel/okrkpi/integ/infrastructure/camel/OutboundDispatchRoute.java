package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;

/** Worker gửi ra: theo chu kỳ, gửi các thông điệp đến hạn của mọi tenant. */
public class OutboundDispatchRoute extends RouteBuilder {

  private final OutboundPipeline pipeline;
  private final Duration period;
  private final int batch;

  public OutboundDispatchRoute(OutboundPipeline pipeline, Duration period, int batch) {
    this.pipeline = pipeline;
    this.period = period;
    this.batch = batch;
  }

  @Override
  public void configure() {
    from("timer:integ-outbound-dispatch?period=" + period.toMillis())
        .routeId("integ-outbound-dispatch")
        .process(e -> pipeline.dispatchDue(batch));
  }
}
