package vn.viettel.okrkpi.org.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ADR-34 · Công tắc đồng bộ người dùng và tổ chức từ hệ thống ngoài ({@code okrkpi.org.external-sync.enabled}, mặc
 * định tắt). Khi tắt, mọi lối ghi của đồng bộ ngoài trả 404 {@code feature-disabled}: giao diện SCIM của HRM, IdP
 * ({@code /scim/v2/**}), "Đồng bộ ngay" và chạy lại ({@code POST /org/sync-runs...}), lịch đồng bộ
 * ({@code PUT /org/sync-schedules}) và cổng nhận lô nhân sự của integration-gateway. Mã đồng bộ được giữ nguyên,
 * bật lại chỉ cần đổi cấu hình. Thao tác đọc (lịch sử lần chạy, bảng lệch) không bị chặn, nên dữ liệu cũ vẫn tra
 * được; tài khoản và cơ cấu tổ chức do quản trị tạo trong ứng dụng (SCR-0103, SCR-0108) không phụ thuộc công tắc này.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class ExternalSyncGate implements WebMvcConfigurer {

  /** Phương thức (null là mọi phương thức) và mẫu đường dẫn bị chặn khi công tắc tắt. */
  record Rule(HttpMethod method, String pattern) {
  }

  static final List<Rule> RULES = List.of(
      new Rule(null, "/scim/v2/**"),
      new Rule(HttpMethod.POST, "/api/v1/org/sync-runs"),
      new Rule(HttpMethod.POST, "/api/v1/org/sync-runs/*/retry"),
      new Rule(HttpMethod.PUT, "/api/v1/org/sync-schedules"),
      new Rule(HttpMethod.POST, "/internal/v1/org/sync-runs/*/batches"));

  private static final AntPathMatcher PATHS = new AntPathMatcher();

  private final boolean enabled;

  public ExternalSyncGate(@Value("${okrkpi.org.external-sync.enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  /** Yêu cầu có thuộc phần đồng bộ ngoài đang tắt không. */
  static boolean blocked(boolean enabled, String method, String path) {
    if (enabled) {
      return false;
    }
    for (Rule r : RULES) {
      if ((r.method() == null || r.method().matches(method)) && PATHS.match(r.pattern(), path)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override
      public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (blocked(enabled, req.getMethod(), path)) {
          throw new FeatureDisabledException();
        }
        return true;
      }
    });
  }

  /** Tính năng đang tắt theo cấu hình triển khai; ProblemAdvice trả 404 {@code feature-disabled}. */
  public static final class FeatureDisabledException extends RuntimeException {
    public FeatureDisabledException() {
      super("Tính năng đồng bộ từ hệ thống ngoài chưa được bật.");
    }
  }
}
