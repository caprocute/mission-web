package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.infrastructure.search.SearchClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.assign.TaskAssignService;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.TaskQueryService;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.application.catalog.CatalogPolicy;
import vn.viettel.okrkpi.task.application.lifecycle.PolicyBackedTransitionTableProvider;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.mine.LeadWorkloads;
import vn.viettel.okrkpi.task.application.similar.SimilarTaskService;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskCandidates;
import vn.viettel.okrkpi.task.infrastructure.client.HttpSimilarTaskCandidates;
import vn.viettel.okrkpi.task.infrastructure.job.InMemoryBulkAssignJobs;

/**
 * Soạn, giao và đọc nhiệm vụ (CORE-02-14) trên {@code taskDataSource}: outbox EVT-04, EVT-21 và tác
 * vụ nền giao đồng loạt chạy trên luồng ảo.
 */
@Configuration(proxyBeanMethods = false)
public class TaskAssignConfiguration {

  @Bean
  JdbcTaskDraftRepository taskDraftRepository(
      @Qualifier("taskDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcTaskDraftRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  TaskAssignedEvents taskAssignedEvents(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    return new OutboxTaskAssignedEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, Map.of()),
            mapper,
            Clock.systemUtc()),
        mapper);
  }

  @Bean
  InMemoryBulkAssignJobs bulkAssignJobs() {
    return new InMemoryBulkAssignJobs();
  }

  @Bean
  TaskDraftService taskDraftService(
      JdbcTaskDraftRepository taskDraftRepository,
      PeriodApi periods,
      OrgDirectoryApi org,
      PolicyBackedTransitionTableProvider taskTransitionTableProvider) {
    return new TaskDraftService(
        taskDraftRepository,
        periods,
        org,
        taskTransitionTableProvider,
        java.time.Clock.systemUTC());
  }

  @Bean
  TaskAssignService taskAssignService(
      TaskDraftService taskDraftService,
      JdbcTaskDraftRepository taskDraftRepository,
      TaskTransitionService taskTransitionService,
      TaskAssignedEvents taskAssignedEvents,
      InMemoryBulkAssignJobs bulkAssignJobs,
      PeriodApi periods,
      ObjectProvider<OkrApi> okr,
      OrgDirectoryApi org,
      ObjectProvider<WorkingCalendarApi> calendar,
      ObjectProvider<CatalogPolicy> catalogPolicy,
      ApplicationEventPublisher publisher,
      @Qualifier("taskTransactionManager") PlatformTransactionManager taskTransactionManager,
      LeadWorkloads leadWorkloads,
      SimilarTaskService similarTaskService) {
    CatalogPolicy policy = catalogPolicy.getIfAvailable(CatalogPolicy::defaults);
    Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    return new TaskAssignService(
        taskDraftService,
        taskDraftRepository,
        taskTransitionService,
        taskAssignedEvents,
        bulkAssignJobs,
        periods,
        okr.getIfAvailable(),
        org,
        calendar.getIfAvailable(),
        policy::vagueWords,
        publisher,
        taskTransactionManager,
        executor,
        java.time.Clock.systemUTC(),
        leadWorkloads,
        similarTaskService);
  }

  /**
   * CORE-02-15: ứng viên gần giống từ SVC-16 khi có {@code mission.task.similar.search-url}, nếu
   * không thì từ nhiệm vụ cùng đơn vị, kỳ trong CSDL.
   */
  @Bean
  SimilarTaskService similarTaskService(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.similar.search-url:}") String searchUrl,
      @Value("${mission.task.similar.timeout:PT1S}") java.time.Duration timeout,
      ObjectProvider<SimilarTaskCandidates> override) {
    SimilarTaskCandidates remote = override.getIfAvailable();
    if (remote == null && !searchUrl.isBlank()) {
      remote = new HttpSimilarTaskCandidates(new SearchClient(searchUrl, timeout));
    }
    return new SimilarTaskService(new JdbcSimilarTaskRows(JdbcClient.create(dataSource)), remote);
  }

  @Bean
  TaskQueryService taskQueryService(
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      JdbcTaskDraftRepository taskDraftRepository,
      PeriodApi periods,
      OrgDirectoryApi org) {
    return new TaskQueryService(
        taskLifecycleRepository, taskDraftRepository, periods, org, java.time.Clock.systemUTC());
  }
}
