package vn.viettel.okrkpi.shared.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.viettel.okrkpi.shared.error.TraceIdResolver;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đặt {@code trace_id}, {@code span_id}, {@code tenant_id} vào MDC trong suốt yêu cầu, để mọi dòng
 * nhật ký của yêu cầu có {@code trace.id} và {@code tenant.id}. Mã vết lấy cùng nguồn với {@code
 * traceId} của Problem Details ({@link TraceIdResolver}). Khoá đã có sẵn (do agent đặt) được giữ
 * nguyên; khoá do bộ lọc đặt được gỡ khi yêu cầu kết thúc.
 */
public class LoggingContextFilter extends OncePerRequestFilter {

  private final TraceIdResolver traceIds;
  private final TenantIdProvider tenants;

  public LoggingContextFilter(TraceIdResolver traceIds, TenantIdProvider tenants) {
    this.traceIds = traceIds;
    this.tenants = tenants;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    List<String> added = new ArrayList<>(3);
    try {
      putIfAbsent(LoggingContext.TRACE_ID, traceIds.resolve(request), added);
      SpanContext span = Span.current().getSpanContext();
      if (span.isValid()) {
        putIfAbsent(LoggingContext.SPAN_ID, span.getSpanId(), added);
        putIfAbsent(LoggingContext.TRACE_FLAGS, span.getTraceFlags().asHex(), added);
      }
      Optional<TenantId> tenant = tenants.currentTenant(request);
      tenant.ifPresent(t -> putIfAbsent(LoggingContext.TENANT_ID, t.toString(), added));
      chain.doFilter(request, response);
    } finally {
      added.forEach(MDC::remove);
    }
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  private static void putIfAbsent(String key, String value, List<String> added) {
    if (value != null && MDC.get(key) == null) {
      MDC.put(key, value);
      added.add(key);
    }
  }
}
