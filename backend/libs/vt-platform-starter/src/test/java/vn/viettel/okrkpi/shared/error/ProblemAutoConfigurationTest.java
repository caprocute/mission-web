package vn.viettel.okrkpi.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

class ProblemAutoConfigurationTest {

  private final WebApplicationContextRunner web =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ProblemAutoConfiguration.class,
                  DispatcherServletAutoConfiguration.class,
                  WebMvcAutoConfiguration.class,
                  ErrorMvcAutoConfiguration.class));

  @Test
  void dangKyDuBeanVaThayBoXuLyMacDinhCuaSpringBoot() {
    web.withPropertyValues("spring.mvc.problemdetails.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(ErrorCatalog.class);
              assertThat(ctx).hasSingleBean(ProblemFactory.class);
              assertThat(ctx).hasSingleBean(SecurityProblemHandler.class);
              assertThat(ctx.getBeansOfType(ResponseEntityExceptionHandler.class))
                  .hasSize(1)
                  .containsValue(ctx.getBean(ProblemExceptionHandler.class));
              assertThat(ctx.getBeansOfType(ErrorController.class))
                  .hasSize(1)
                  .containsValue(ctx.getBean(ProblemErrorController.class));
              assertThat(ctx.getBean(TraceIdResolver.class))
                  .isInstanceOf(vn.viettel.okrkpi.shared.observability.OtelTraceIdResolver.class);
            });
  }

  @Test
  void serviceTuKhaiBaoTraceIdResolverThiStarterLuiLai() {
    TraceIdResolver custom = request -> "cccccccccccccccccccccccccccccccc";
    web.withBean(TraceIdResolver.class, () -> custom)
        .run(ctx -> assertThat(ctx.getBean(TraceIdResolver.class)).isSameAs(custom));
  }

  @Test
  void khongCoSpringSecurityThiKhongNapBoXuLyQuyen() {
    web.withClassLoader(new FilteredClassLoader(AccessDeniedException.class))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(SecurityProblemHandler.class);
              assertThat(ctx).hasSingleBean(ProblemExceptionHandler.class);
            });
  }

  @Test
  void khongPhaiUngDungWebThiKhongNap() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProblemAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(ProblemExceptionHandler.class));
  }
}
