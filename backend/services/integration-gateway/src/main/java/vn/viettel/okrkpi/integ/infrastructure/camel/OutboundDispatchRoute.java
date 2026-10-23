package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;

/** Worker gửi ra: theo chu kỳ, gửi các thông điệp đến hạn của mọi tenant. */
public class OutboundDispatchRoute extends RouteBuilder {

  private final OutboundPipeline pipeline;
  private final Duration period;
  private final int batch;

  private final java.util.Set<String> codes;
  private final boolean include;

  public OutboundDispatchRoute(OutboundPipeline pipeline, Duration period, int batch) {
    this(pipeline, period, batch, java.util.Set.of(), false);
  }

  /** Chỉ gửi mã {@code codes} ({@code include}) hoặc mọi mã trừ {@code codes} (DV-13-05). */
  public OutboundDispatchRoute(
      OutboundPipeline pipeline,
      Duration period,
      int batch,
      java.util.Set<String> codes,
      boolean include) {
    this.pipeline = pipeline;
    this.period = period;
    this.batch = batch;
    this.codes = java.util.Set.copyOf(codes);
    this.include = include;
  }

  @Override
  public void configure() {
    from("timer:integ-outbound-dispatch?period=" + period.toMillis())
        .routeId("integ-outbound-dispatch")
        .process(e -> pipeline.dispatchDue(batch, codes, include));
  }
}
