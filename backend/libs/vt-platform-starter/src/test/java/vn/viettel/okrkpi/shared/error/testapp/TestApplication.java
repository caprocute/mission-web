package vn.viettel.okrkpi.shared.error.testapp;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import vn.viettel.okrkpi.okr.infrastructure.web.OkrProbeController;

/** Ứng dụng thử: controller ném lần lượt các lỗi chuẩn, bộ lọc gây lỗi ngoài controller. */
@SpringBootApplication
@Import(OkrProbeController.class)
public class TestApplication {

  /** Bộ lọc trả 401 bằng sendError (như bộ lọc xác thực), để kiểm ProblemErrorController. */
  @Bean
  FilterRegistrationBean<Filter> unauthorizedFilter() {
    FilterRegistrationBean<Filter> reg =
        new FilterRegistrationBean<>(
            (req, res, chain) ->
                ((HttpServletResponse) res).sendError(401, "Bearer token expired"));
    reg.addUrlPatterns("/filtered/*");
    return reg;
  }

  /** Bộ lọc ném ngoại lệ có câu SQL trong thông điệp. */
  @Bean
  FilterRegistrationBean<Filter> explodingFilter() {
    FilterRegistrationBean<Filter> reg =
        new FilterRegistrationBean<>(
            (req, res, chain) -> {
              throw new IllegalStateException("SELECT * FROM appraisal.score WHERE id = 1");
            });
    reg.addUrlPatterns("/filter-boom/*");
    return reg;
  }
}
