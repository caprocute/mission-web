package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.config.api.HolidayCalendarChangedEvent;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.task.application.catalog.CatalogPolicy;
import vn.viettel.okrkpi.task.application.fromplan.ConfirmedLinePlanChecker;
import vn.viettel.okrkpi.task.application.fromplan.LeadConfirmationService;
import vn.viettel.okrkpi.task.application.plan.PlanChecker;
import vn.viettel.okrkpi.task.application.plan.PlanDeadlineReminders;
import vn.viettel.okrkpi.task.application.plan.PlanDeadlines;
import vn.viettel.okrkpi.task.application.plan.PlanReviewService;
import vn.viettel.okrkpi.task.application.plan.PlanService;
import vn.viettel.okrkpi.task.infrastructure.scheduling.PlanDeadlineJob;

/**
 * CORE-02-05: kế hoạch công tác quý trên {@code taskDataSource}; CORE-02-07: hạn duyệt và tác vụ
 * nhắc hạn.
 */
@Configuration(proxyBeanMethods = false)
public class PlanConfiguration {

  @Bean
  JdbcPlanRepository planRepository(
      @Qualifier("taskDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcPlanRepository(dataSource, json.getIfAvailable(ObjectMapper::new));
  }

  /**
   * CORE-02-06: BR-04-02..07; BR-04-03, BR-04-04 biên dịch CEL một lần khi khởi động. CORE-02-11
   * thêm kiểm tra xác nhận dòng liên đơn vị.
   */
  @Bean
  PlanChecker planChecker(LeadConfirmationService leadConfirmationService) {
    return new ConfirmedLinePlanChecker(
        new CelEngine(),
        leadConfirmationService,
        () -> vn.viettel.okrkpi.shared.tenant.TenantContext.current().orElse(null));
  }

  @Bean
  PlanService planService(
      JdbcPlanRepository planRepository,
      PeriodApi periods,
      OkrApi okr,
      ObjectProvider<OrgDirectoryApi> org,
      PlanChecker planChecker,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<CatalogPolicy> catalogPolicy) {
    return new PlanService(
        planRepository,
        periods,
        okr,
        org.getIfAvailable(),
        planChecker,
        policies.getIfAvailable(),
        catalogPolicy.getIfAvailable(),
        Clock.systemDefaultZone());
  }

  /** CORE-02-07: hạn duyệt BR-04-01 theo Policy Pack và lịch ngày làm việc của tenant. */
  @Bean
  PlanDeadlines planDeadlines(
      WorkingCalendarApi calendar, ObjectProvider<PolicyQueryApi> policies) {
    return new PlanDeadlines(calendar, policies.getIfAvailable(), Clock.systemUTC());
  }

  @Bean
  PlanDeadlinesEvictor planDeadlinesEvictor(PlanDeadlines planDeadlines) {
    return new PlanDeadlinesEvictor(planDeadlines);
  }

  /** Nghe {@link HolidayCalendarChangedEvent} của MOD-08 để xoá đệm hạn của tenant. */
  static class PlanDeadlinesEvictor {
    private final PlanDeadlines deadlines;

    PlanDeadlinesEvictor(PlanDeadlines deadlines) {
      this.deadlines = deadlines;
    }

    @EventListener
    public void on(HolidayCalendarChangedEvent e) {
      deadlines.evict(e.tenantId());
    }
  }

  @Bean
  OutboxPlanNotices planNotices(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxPlanNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, java.util.Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc()));
  }

  /** CORE-02-08: gửi duyệt, trả lại, danh sách chờ duyệt. */
  @Bean
  PlanReviewService planReviewService(
      JdbcPlanRepository planRepository,
      PlanService planService,
      PeriodApi periods,
      ObjectProvider<OrgDirectoryApi> org,
      OutboxPlanNotices planNotices,
      ApplicationEventPublisher publisher) {
    return new PlanReviewService(
        planRepository,
        planService,
        periods,
        org.getIfAvailable(),
        planNotices,
        publisher,
        Clock.systemUTC());
  }

  @Bean
  PlanDeadlineReminders planDeadlineReminders(
      JdbcPlanRepository planRepository,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      PlanDeadlines planDeadlines,
      OutboxPlanNotices planNotices,
      ObjectProvider<OrgDirectoryApi> org) {
    return new PlanDeadlineReminders(
        planRepository, periods, calendar, planDeadlines, planNotices, org.getIfAvailable());
  }

  /** Tác vụ nhắc hạn; tắt bằng {@code mission.task.plan-deadline.enabled=false}. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.task.plan-deadline",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class DeadlineJob {

    @Bean
    PlanDeadlineJob planDeadlineJob(
        PlanDeadlineReminders planDeadlineReminders,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new PlanDeadlineJob(planDeadlineReminders, directory, cell);
    }
  }
}
