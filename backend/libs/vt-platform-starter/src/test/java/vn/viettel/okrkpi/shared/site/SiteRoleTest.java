package vn.viettel.okrkpi.shared.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** INF-28 · QA-15 · Chế độ chờ của ứng dụng ở TTDL không giữ primary. */
class SiteRoleTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SiteAutoConfiguration.class));

  private static StandardEnvironment env(Map<String, Object> props) {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("thu", props));
    new SiteRoleEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    return env;
  }

  @Test
  void parse_mac_dinh_primary_va_tu_choi_gia_tri_la() {
    assertThat(SiteRole.parse(null)).isEqualTo(SiteRole.PRIMARY);
    assertThat(SiteRole.parse(" Standby ")).isEqualTo(SiteRole.STANDBY);
    assertThatThrownBy(() -> SiteRole.parse("hl")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void standby_ep_tat_flyway_va_consumer_kafka_du_service_bat() {
    StandardEnvironment e =
        env(
            Map.of(
                "VT_SITE_ROLE", "standby",
                "VT_SITE", "sn",
                "spring.flyway.enabled", "true",
                "okrkpi.inbox.listener-auto-startup", "true"));
    assertThat(e.getProperty("okrkpi.site.role")).isEqualTo("standby");
    assertThat(e.getProperty("okrkpi.site.name")).isEqualTo("sn");
    assertThat(e.getProperty("spring.flyway.enabled")).isEqualTo("false");
    assertThat(e.getProperty("spring.kafka.listener.auto-startup")).isEqualTo("false");
    assertThat(e.getProperty("okrkpi.inbox.listener-auto-startup")).isEqualTo("false");
  }

  @Test
  void primary_khong_dong_toi_cau_hinh_service() {
    StandardEnvironment e = env(Map.of("VT_SITE_ROLE", "primary", "spring.flyway.enabled", "true"));
    assertThat(e.getProperty("okrkpi.site.role")).isEqualTo("primary");
    assertThat(e.getProperty("spring.flyway.enabled")).isEqualTo("true");
    assertThat(e.getProperty("spring.kafka.listener.auto-startup")).isNull();
  }

  @Test
  void health_bao_vai_tro_va_phan_tam_dung() {
    runner
        .withPropertyValues("okrkpi.site.role=standby", "okrkpi.site.name=sn")
        .run(
            ctx -> {
              var h = ctx.getBean(SiteHealthIndicator.class).health();
              assertThat(h.getStatus()).isEqualTo(Status.UP);
              assertThat(h.getDetails())
                  .containsEntry("role", "standby")
                  .containsEntry("site", "sn")
                  .containsEntry("paused", List.of("kafka-consumer", "scheduler", "flyway"));
              assertThat(ctx).hasSingleBean(StandbySchedulingConfigurer.class);
            });
    runner.run(
        ctx -> {
          var h = ctx.getBean(SiteHealthIndicator.class).health();
          assertThat(h.getDetails())
              .containsEntry("role", "primary")
              .containsEntry("paused", List.of());
          assertThat(ctx).doesNotHaveBean(StandbySchedulingConfigurer.class);
        });
  }

  @Test
  void job_dinh_ky_khong_chay_o_ttdl_cho_va_chay_lai_khi_doi_vai_tro() throws Exception {
    AtomicBoolean standby = new AtomicBoolean(true);
    AtomicInteger chay = new AtomicInteger();
    var s = new StandbyAwareTaskScheduler(new ConcurrentTaskScheduler(), standby::get);
    s.gate(chay::incrementAndGet).run();
    assertThat(chay).hasValue(0);
    standby.set(false);
    s.gate(chay::incrementAndGet).run();
    assertThat(chay).hasValue(1);
    var f = s.scheduleWithFixedDelay(chay::incrementAndGet, Duration.ofMillis(5));
    Thread.sleep(50);
    f.cancel(true);
    assertThat(chay.get()).isGreaterThan(1);
  }

  @Test
  void configurer_boc_bo_lap_lich_hien_co_mot_lan() {
    TaskScheduler goc = new ConcurrentTaskScheduler();
    SchedulingConfigurer c =
        new StandbySchedulingConfigurer(
            new SiteProperties("standby", "sn"),
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(TaskScheduler.class));
    ScheduledTaskRegistrar r = new ScheduledTaskRegistrar();
    r.setTaskScheduler(goc);
    c.configureTasks(r);
    assertThat(r.getScheduler()).isInstanceOf(StandbyAwareTaskScheduler.class);
    TaskScheduler daBoc = r.getScheduler();
    c.configureTasks(r);
    assertThat(r.getScheduler()).isSameAs(daBoc);
  }
}
