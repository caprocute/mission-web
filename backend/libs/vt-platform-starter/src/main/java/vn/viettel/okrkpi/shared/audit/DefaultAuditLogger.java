package vn.viettel.okrkpi.shared.audit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.observability.LoggingContext;
import vn.viettel.okrkpi.shared.observability.Redactor;

/**
 * Hiện thực mặc định: điền mã sự kiện UUIDv7, thời điểm UTC, mã vết hiện hành; che giá trị thuộc
 * tính khớp mẫu nhạy cảm; rồi chuyển cho {@link AuditSink}.
 */
public class DefaultAuditLogger implements AuditLogger {

  private final AuditSink sink;
  private final Clock clock;

  public DefaultAuditLogger(AuditSink sink, Clock clock) {
    this.sink = Objects.requireNonNull(sink, "sink");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void record(AuditEvent event) {
    Objects.requireNonNull(event, "event");
    Map<String, String> attrs = new LinkedHashMap<>();
    event.attributes().forEach((k, v) -> attrs.put(k, Redactor.redact(v)));
    sink.publish(event.complete(Ids.newUuidV7(), clock.instant(), currentTraceId(), attrs));
  }

  static String currentTraceId() {
    SpanContext ctx = Span.current().getSpanContext();
    if (ctx.isValid()) {
      return ctx.getTraceId();
    }
    return MDC.get(LoggingContext.TRACE_ID);
  }
}
