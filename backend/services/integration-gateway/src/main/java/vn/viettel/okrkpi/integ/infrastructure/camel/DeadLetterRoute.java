package vn.viettel.okrkpi.integ.infrastructure.camel;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.integ.application.DeadLetterService;
import vn.viettel.okrkpi.integ.application.InboundProcessingException;
import vn.viettel.okrkpi.integ.domain.ErrorText;

/**
 * Đích {@value IntegRouteBuilder#DEAD_LETTER} chung: thông điệp đã lưu {@code inbox_message} mà hết
 * lượt thử thì ghi {@code dead_letter} của đúng kết nối để chạy lại; lỗi trước khi lưu chỉ ghi nhật
 * ký (con trỏ chưa tiến nên lượt kéo sau lấy lại).
 */
public class DeadLetterRoute extends RouteBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(DeadLetterRoute.class);

  private final DeadLetterService deadLetters;

  public DeadLetterRoute(DeadLetterService deadLetters) {
    this.deadLetters = deadLetters;
  }

  @Override
  public void configure() {
    from(IntegRouteBuilder.DEAD_LETTER).routeId("integ-dead-letter").process(this::record);
  }

  void record(Exchange exchange) {
    Throwable error = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof InboundProcessingException failed) {
        deadLetters.record(
            failed.tenant(),
            failed.connectorId(),
            DeadLetterService.INBOUND,
            failed.inboxId(),
            failed.getCause());
        return;
      }
    }
    LOG.warn(
        "Thông điệp lỗi trước khi lưu, không vào thư chết (route {}): {}",
        exchange.getFromRouteId(),
        ErrorText.code(error));
  }
}
