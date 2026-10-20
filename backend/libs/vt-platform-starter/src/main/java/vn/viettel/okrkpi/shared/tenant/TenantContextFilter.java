package vn.viettel.okrkpi.shared.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.observability.LoggingContext;

/**
 * Chặng ba của TL-01 "Lan truyền ngữ cảnh tenant từ JWT tới RLS": đọc tenant từ JWT đã được Spring
 * Security xác thực (không bao giờ đọc header do client gửi), đặt vào {@link TenantContext} và MDC
 * {@code tenant_id} trong suốt yêu cầu. Yêu cầu không xác thực bằng JWT (endpoint công khai) đi qua
 * không có tenant; yêu cầu có JWT mà thiếu hoặc sai claim nhận 403 Problem Details.
 *
 * <p>Đăng ký sau chuỗi bộ lọc Spring Security ({@code SecurityProperties.DEFAULT_FILTER_ORDER +
 * 1}).
 */
public class TenantContextFilter extends OncePerRequestFilter {

  private final String claim;
  private final ProblemFactory problems;
  private final ObjectMapper objectMapper;

  public TenantContextFilter(
      TenantProperties properties, ProblemFactory problems, ObjectMapper objectMapper) {
    this.claim = properties.claim();
    this.problems = problems;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwt)) {
      chain.doFilter(request, response);
      return;
    }
    TenantId tenant = parse(jwt.getToken().getClaimAsString(claim));
    if (tenant == null) {
      forbid(request, response);
      return;
    }
    TenantId previous = TenantContext.enter(tenant);
    String previousMdc = MDC.get(LoggingContext.TENANT_ID);
    MDC.put(LoggingContext.TENANT_ID, tenant.toString());
    try {
      chain.doFilter(request, response);
    } finally {
      TenantContext.restore(previous);
      if (previousMdc == null) {
        MDC.remove(LoggingContext.TENANT_ID);
      } else {
        MDC.put(LoggingContext.TENANT_ID, previousMdc);
      }
    }
  }

  private static TenantId parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return TenantId.parse(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private void forbid(HttpServletRequest request, HttpServletResponse response) throws IOException {
    ProblemDetail pd =
        problems.standard(
            HttpServletResponse.SC_FORBIDDEN,
            "Phiên đăng nhập không gắn với đơn vị triển khai nào",
            List.of(),
            request);
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getOutputStream(), pd);
  }
}
