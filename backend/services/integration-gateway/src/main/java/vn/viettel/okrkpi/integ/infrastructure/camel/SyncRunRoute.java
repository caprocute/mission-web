package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import vn.viettel.okrkpi.integ.application.SyncRunService;

/**
 * Worker lần đồng bộ (DV-13-06): theo chu kỳ mở lần chạy theo lịch đêm đã đến giờ rồi chạy các lần
 * chạy đang chờ của mọi tenant; mỗi lần chạy kéo hết trang nên chạy tuần tự trên luồng của timer.
 */
public class SyncRunRoute extends RouteBuilder {

  public static final String ROUTE_ID = "integ-sync-runs";

  private final SyncRunService runs;
  private final Duration period;
  private final int batch;

  public SyncRunRoute(SyncRunService runs, Duration period, int batch) {
    this.runs = runs;
    this.period = period;
    this.batch = batch;
  }

  @Override
  public void configure() {
    from("timer:" + ROUTE_ID + "?period=" + period.toMillis())
        .routeId(ROUTE_ID)
        .process(
            e -> {
              runs.scheduleDue();
              runs.runDue(batch);
            });
  }
}
