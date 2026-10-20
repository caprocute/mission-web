package vn.viettel.okrkpi.shared.observability;

/**
 * Khoá MDC mà cấu hình Log4j2 đọc ra các trường ECS {@code trace.id}, {@code span.id}, {@code
 * tenant.id} (TL-01 "Log có cấu trúc"). OpenTelemetry agent tự đặt {@code trace_id}, {@code
 * span_id}; thư viện nền đặt thêm khi chạy không có agent và đặt {@code tenant_id}.
 */
public final class LoggingContext {

  public static final String TRACE_ID = "trace_id";
  public static final String SPAN_ID = "span_id";
  public static final String TRACE_FLAGS = "trace_flags";
  public static final String TENANT_ID = "tenant_id";

  private LoggingContext() {}
}
