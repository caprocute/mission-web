package vn.viettel.okrkpi.shared.job;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.shared.error.ProblemFactory;

/**
 * Auto-configuration tác vụ dài: {@link JobQueryService} gộp mọi bean {@link JobSource} (theo thứ
 * tự {@code @Order}) và ánh xạ {@link JobNotFoundException} → 404.
 */
@AutoConfiguration(afterName = "vn.viettel.okrkpi.shared.error.ProblemAutoConfiguration")
public class JobAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public JobQueryService jobQueryService(ObjectProvider<JobSource> sources) {
    List<JobSource> ordered = sources.orderedStream().toList();
    return new JobQueryService(ordered);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnBean(ProblemFactory.class)
  public JobProblemHandler jobProblemHandler(ProblemFactory problemFactory) {
    return new JobProblemHandler(problemFactory);
  }

  /** {@link JobNotFoundException} → 404 chuẩn ({@code common.yaml} response NotFound). */
  @RestControllerAdvice
  @Order(Ordered.LOWEST_PRECEDENCE - 20)
  public static class JobProblemHandler {

    private final ProblemFactory problems;

    public JobProblemHandler(ProblemFactory problems) {
      this.problems = problems;
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
        JobNotFoundException ex, HttpServletRequest request) {
      return ProblemFactory.toResponse(problems.standard(404, null, List.of(), request), null);
    }
  }
}
