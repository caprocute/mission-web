package vn.viettel.okrkpi.shared.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Comment SQL theo chuẩn sqlcommenter (TL-01 "OpenTelemetry và lan truyền ngữ cảnh trace"): {@code
 * /*traceparent='00-...',tenant='...'*&#47;} nối vào cuối câu lệnh, để truy vấn chậm trong {@code
 * pg_stat_statements} và log PostgreSQL liên kết được về trace.
 *
 * <p>Chỉ nhận giá trị đúng dạng (hex của W3C Trace Context, UUID của tenant), nên không thể chèn
 * {@code *&#47;} hay ký tự lạ vào câu SQL.
 */
public final class SqlCommenter {

  private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
  private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");
  private static final Pattern HEX2 = Pattern.compile("[0-9a-f]{2}");
  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final String MARKER = "/*traceparent=";

  private SqlCommenter() {}

  /** Câu SQL đã thêm comment; giữ nguyên khi không có ngữ cảnh hoặc đã có comment. */
  public static String comment(String sql) {
    if (sql == null || sql.isBlank() || sql.contains(MARKER) || sql.contains("/*tenant=")) {
      return sql;
    }
    String traceparent = currentTraceparent();
    String tenant = valid(MDC.get(LoggingContext.TENANT_ID), UUID);
    if (traceparent == null && tenant == null) {
      return sql;
    }
    StringBuilder c = new StringBuilder(sql.length() + 100).append(sql).append(" /*");
    if (traceparent != null) {
      c.append("traceparent='").append(traceparent).append('\'');
    }
    if (tenant != null) {
      if (traceparent != null) {
        c.append(',');
      }
      c.append("tenant='").append(tenant).append('\'');
    }
    return c.append("*/").toString();
  }

  /** {@code traceparent} của span hiện hành, hoặc dựng từ MDC khi chạy không có agent. */
  static String currentTraceparent() {
    SpanContext ctx = Span.current().getSpanContext();
    if (ctx.isValid()) {
      return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + ctx.getTraceFlags().asHex();
    }
    String traceId = valid(MDC.get(LoggingContext.TRACE_ID), HEX32);
    String spanId = valid(MDC.get(LoggingContext.SPAN_ID), HEX16);
    if (traceId == null || spanId == null) {
      return null;
    }
    String flags = valid(MDC.get(LoggingContext.TRACE_FLAGS), HEX2);
    return "00-" + traceId + "-" + spanId + "-" + (flags == null ? "00" : flags);
  }

  private static String valid(String value, Pattern pattern) {
    return value != null && pattern.matcher(value).matches() ? value : null;
  }
}
