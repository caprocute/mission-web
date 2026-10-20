package vn.viettel.okrkpi.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.async.EventRoute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import vn.viettel.okrkpi.shared.error.W3cTraceIdResolver;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class SqlCommenterAndLoggingTest {

  private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String SPAN = "00f067aa0ba902b7";
  private static final String TENANT = "0192f5a0-7b1c-7cde-8f00-0123456789ab";

  @AfterEach
  void clear() {
    MDC.clear();
  }

  private static Scope span() {
    return Span.wrap(
            SpanContext.create(TRACE, SPAN, TraceFlags.getSampled(), TraceState.getDefault()))
        .makeCurrent();
  }

  @Test
  void commentTheoSpanHienHanhVaTenant() {
    MDC.put(LoggingContext.TENANT_ID, TENANT);
    try (Scope ignored = span()) {
      assertThat(new SqlCommenterStatementInspector().inspect("select 1"))
          .isEqualTo(
              "select 1 /*traceparent='00-"
                  + TRACE
                  + "-"
                  + SPAN
                  + "-01',tenant='"
                  + TENANT
                  + "'*/");
    }
  }

  @Test
  void commentTuMdcKhiKhongCoAgent() {
    MDC.put(LoggingContext.TRACE_ID, TRACE);
    MDC.put(LoggingContext.SPAN_ID, SPAN);
    assertThat(SqlCommenter.comment("select 1"))
        .isEqualTo("select 1 /*traceparent='00-" + TRACE + "-" + SPAN + "-00'*/");
    MDC.put(LoggingContext.TRACE_FLAGS, "01");
    assertThat(SqlCommenter.comment("select 1")).endsWith("-01'*/");
  }

  @Test
  void khongChenGiaTriSaiDangVaKhongCommentLai() {
    MDC.put(LoggingContext.TENANT_ID, "x'*/; drop table t; --");
    MDC.put(LoggingContext.TRACE_ID, "not-hex");
    assertThat(SqlCommenter.comment("select 1")).isEqualTo("select 1");
    MDC.put(LoggingContext.TENANT_ID, TENANT);
    String once = SqlCommenter.comment("select 1");
    assertThat(once).isEqualTo("select 1 /*tenant='" + TENANT + "'*/");
    assertThat(SqlCommenter.comment(once)).isEqualTo(once);
    assertThat(SqlCommenter.comment(null)).isNull();
    assertThat(SqlCommenter.comment(" ")).isEqualTo(" ");
  }

  @Test
  void boLocDatVaGoNguCanhNhatKy() throws Exception {
    TenantId tenant = TenantId.of(UUID.fromString(TENANT));
    LoggingContextFilter filter =
        new LoggingContextFilter(
            new OtelTraceIdResolver(new W3cTraceIdResolver()), r -> Optional.of(tenant));
    Map<String, String> seen = new HashMap<>();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            seen.putAll(MDC.getCopyOfContextMap());
          }
        };
    try (Scope ignored = span()) {
      filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    }
    assertThat(seen)
        .containsEntry(LoggingContext.TRACE_ID, TRACE)
        .containsEntry(LoggingContext.SPAN_ID, SPAN)
        .containsEntry(LoggingContext.TRACE_FLAGS, "01")
        .containsEntry(LoggingContext.TENANT_ID, TENANT);
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  void boLocGiuKhoaDoAgentDat() throws Exception {
    MDC.put(LoggingContext.TRACE_ID, "agent");
    LoggingContextFilter filter = new LoggingContextFilter(r -> "khac", TenantIdProvider.none());
    Map<String, String> seen = new HashMap<>();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            seen.putAll(MDC.getCopyOfContextMap());
          }
        };
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    assertThat(seen).containsEntry(LoggingContext.TRACE_ID, "agent");
    assertThat(seen).doesNotContainKey(LoggingContext.TENANT_ID);
    assertThat(MDC.get(LoggingContext.TRACE_ID)).isEqualTo("agent");
  }

  @Test
  void traceIdLayTuSpanTruocRoiLuiVe() {
    OtelTraceIdResolver resolver = new OtelTraceIdResolver(r -> "fallback");
    assertThat(resolver.resolve(new MockHttpServletRequest())).isEqualTo("fallback");
    try (Scope ignored = span()) {
      assertThat(resolver.resolve(new MockHttpServletRequest())).isEqualTo(TRACE);
    }
  }

  @Test
  void hangDoiDayBoDongInfoVaDem() {
    CountingDiscardPolicy policy = new CountingDiscardPolicy(Level.INFO);
    long before = CountingDiscardPolicy.discardedCount();
    assertThat(policy.getRoute(-1, Level.INFO)).isEqualTo(EventRoute.DISCARD);
    assertThat(policy.getRoute(-1, Level.DEBUG)).isEqualTo(EventRoute.DISCARD);
    assertThat(policy.getRoute(-1, Level.WARN)).isNotEqualTo(EventRoute.DISCARD);
    assertThat(policy.getRoute(-1, Level.ERROR)).isNotEqualTo(EventRoute.DISCARD);
    assertThat(CountingDiscardPolicy.discardedCount() - before).isEqualTo(2);
    assertThat(new CountingDiscardPolicy().getRoute(-1, Level.ERROR))
        .isNotEqualTo(EventRoute.DISCARD);
  }
}
