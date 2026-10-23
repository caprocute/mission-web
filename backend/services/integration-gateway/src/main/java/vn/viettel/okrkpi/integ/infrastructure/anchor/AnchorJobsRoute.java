package vn.viettel.okrkpi.integ.infrastructure.anchor;

import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import vn.viettel.okrkpi.integ.application.anchor.AnchorService;

/** Ba việc theo chu kỳ của luồng neo: tạo bản neo, chuyển biên nhận, kiểm đủ biên nhận. */
public class AnchorJobsRoute extends RouteBuilder {

  private final AnchorService anchors;
  private final Duration period;
  private final int batch;

  public AnchorJobsRoute(AnchorService anchors, Duration period, int batch) {
    this.anchors = anchors;
    this.period = period;
    this.batch = batch;
  }

  @Override
  public void configure() {
    from("timer:integ-anchor-fanout?period=" + period.toMillis())
        .routeId("integ-anchor-fanout")
        .process(e -> anchors.fanOutDue(batch));
    from("timer:integ-anchor-relay?period=" + period.toMillis())
        .routeId("integ-anchor-relay")
        .process(e -> anchors.relayReceipts(batch));
    from("timer:integ-anchor-check?period=" + period.toMillis())
        .routeId("integ-anchor-check")
        .process(e -> anchors.checkShortfalls(batch));
  }
}
