package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import vn.viettel.okrkpi.integ.application.CellTransferService;

/** Worker gói chuyển liên cell: theo chu kỳ gửi gói đi, nhập gói đến, báo kết quả (DV-13-10). */
public class CellTransferRoute extends RouteBuilder {

  private final CellTransferService service;
  private final Duration period;
  private final int batch;

  public CellTransferRoute(CellTransferService service, Duration period, int batch) {
    this.service = service;
    this.period = period;
    this.batch = batch;
  }

  @Override
  public void configure() {
    from("timer:integ-cell-transfer?period=" + period.toMillis())
        .routeId("integ-cell-transfer")
        .process(e -> service.processDue(batch));
  }
}
