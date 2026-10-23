package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.util.List;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.InboundPipeline;
import vn.viettel.okrkpi.integ.application.PullService;
import vn.viettel.okrkpi.integ.application.PullService.Pull;
import vn.viettel.okrkpi.integ.domain.InboundMessage;

/**
 * Route kéo theo lịch của một bộ kết nối (mẫu route TL-01): với mỗi tenant đã bật kết nối, đọc con
 * trỏ, gọi adapter trong circuit breaker (mạch mở hay lỗi thì giữ con trỏ), tách trang, chống trùng
 * theo {@code inbox_message}, chuyển đổi và phát; lỗi một thông điệp thử lại rồi vào thư chết, các
 * thông điệp khác vẫn chạy; xong trang mới tiến con trỏ.
 */
public class PollingConnectorRoute extends IntegRouteBuilder {

  static final String PULL = "integPull";
  static final String PAGE = "integPage";
  static final String KEY = "integKey";

  private final InboundConnector source;
  private final PullService pulls;
  private final InboundPipeline pipeline;
  private final InboxIdempotentRepository idempotent;

  public PollingConnectorRoute(
      RouteSettings settings,
      InboundConnector source,
      PullService pulls,
      InboundPipeline pipeline,
      InboxIdempotentRepository idempotent) {
    super(settings);
    this.source = source;
    this.pulls = pulls;
    this.pipeline = pipeline;
    this.idempotent = idempotent;
  }

  /** Mã route, cũng là tiền tố mã circuit breaker. */
  public static String routeId(String code) {
    return "integ-pull-" + code.toLowerCase(java.util.Locale.ROOT);
  }

  @Override
  public void configure() {
    String id = routeId(source.code());
    RouteDefinition route =
        from("timer:"
                + id
                + "?period="
                + source.period().toMillis()
                + "&delay="
                + source.period().toMillis())
            .routeId(id)
            .errorHandler(standardDeadLetter());
    ProcessorDefinition<?> perTarget =
        route
            .process(e -> e.getMessage().setBody(pulls.targets(source.code())))
            .split(body())
            .process(
                e ->
                    e.getMessage()
                        .setBody(
                            pulls.prepare(
                                source,
                                e.getMessage()
                                    .getBody(
                                        vn.viettel.okrkpi.integ.application.port.Connectors.Active
                                            .class))));
    // Breaker có timeout chạy trên luồng khác với bản sao exchange: chỉ thân thông điệp được
    // chép về, nên lượt kéo đi theo thân chứ không theo thuộc tính exchange.
    breaker(perTarget, id + "-cb", source.maxConcurrentCalls())
        .process(
            e -> {
              Pull p = e.getMessage().getBody(Pull.class);
              e.getMessage().setBody(new Fetched(p, pulls.fetch(source, p)));
            })
        .onFallback()
        .log(
            LoggingLevel.WARN,
            "Bộ kết nối "
                + source.code()
                + " không phản hồi, giữ con trỏ: ${exception.class.simpleName}")
        .stop()
        .end()
        .process(
            e -> {
              Fetched f = e.getMessage().getBody(Fetched.class);
              e.setProperty(PULL, f.pull());
              e.setProperty(PAGE, f.page());
            })
        .process(e -> e.getMessage().setBody(page(e).items()))
        .split(body())
        .process(this::key)
        .idempotentConsumer(header(KEY), idempotent)
        .eager(true)
        .skipDuplicate(true)
        .process(this::receive)
        .end()
        .end()
        .process(e -> pulls.advance(source, pull(e), page(e)))
        .end();
  }

  /** Kết quả gọi adapter trong breaker. */
  record Fetched(Pull pull, Page page) {}

  private void key(Exchange e) {
    Pull p = pull(e);
    InboundMessage m = e.getMessage().getBody(InboundMessage.class);
    e.getMessage()
        .setHeader(
            KEY, InboxIdempotentRepository.key(p.tenant(), source.code(), m.externalMsgId()));
  }

  private void receive(Exchange e) {
    Pull p = pull(e);
    pipeline.receive(p.tenant(), source.code(), e.getMessage().getBody(InboundMessage.class));
  }

  private static Pull pull(Exchange e) {
    return e.getProperty(PULL, Pull.class);
  }

  private static Page page(Exchange e) {
    Page p = e.getProperty(PAGE, Page.class);
    return p == null ? new Page(List.of(), null, null) : p;
  }
}
