package vn.viettel.okrkpi.shared.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import vn.viettel.okrkpi.shared.error.TraceIdResolver;
import vn.viettel.okrkpi.shared.error.W3cTraceIdResolver;

/**
 * Mã vết lấy từ span OpenTelemetry hiện hành (agent Java 2.x, ADR-12); khi không có span hợp lệ
 * (chạy không có agent, kiểm thử) thì lùi về {@link W3cTraceIdResolver}. Nhờ vậy {@code traceId}
 * của Problem Details, {@code trace.id} của nhật ký và trace trong Tempo là một.
 */
public class OtelTraceIdResolver implements TraceIdResolver {

  private final TraceIdResolver fallback;

  public OtelTraceIdResolver(TraceIdResolver fallback) {
    this.fallback = Objects.requireNonNull(fallback, "fallback");
  }

  @Override
  public String resolve(HttpServletRequest request) {
    SpanContext ctx = Span.current().getSpanContext();
    if (ctx.isValid()) {
      return ctx.getTraceId();
    }
    return fallback.resolve(request);
  }
}
