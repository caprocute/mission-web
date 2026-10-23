package vn.viettel.okrkpi.workflow.infrastructure.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.workflow.application.saga.SagaBlueprint;
import vn.viettel.okrkpi.workflow.application.saga.SagaCatalog;
import vn.viettel.okrkpi.workflow.application.saga.SagaJournal;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandler;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandlerSet;
import vn.viettel.okrkpi.workflow.domain.saga.SagaRepository;

/**
 * Lắp khung saga (DV-06-05, ADR-04 tầng 3): kho JDBC, engine Flowable, sổ ghi qua outbox, dịch vụ
 * ứng dụng và worker. Bản thiết kế saga cụ thể ({@link SagaBlueprint}) và bộ xử lý bước ({@link
 * SagaStepHandler}) do task của từng loại saga đăng ký (CHOT_KY ở DV-06-06).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "okrkpi.saga", name = "enabled", matchIfMissing = true)
public class SagaConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Clock sagaClock() {
    return Clock.systemUTC();
  }

  @Bean
  SagaCatalog sagaCatalog(
      ObjectProvider<SagaBlueprint> blueprints,
      ObjectProvider<SagaStepHandler> handlers,
      ObjectProvider<SagaStepHandlerSet> handlerSets) {
    List<SagaStepHandler> all = new ArrayList<>(handlers.orderedStream().toList());
    handlerSets.orderedStream().forEach(set -> all.addAll(set.handlers()));
    return new SagaCatalog(blueprints.orderedStream().toList(), all);
  }

  @Bean
  SagaRepository sagaRepository(JdbcClient jdbc, ObjectMapper json) {
    return new JdbcSagaRepository(jdbc, json);
  }

  @Bean
  FlowableSagaEngine flowableSagaEngine(
      RuntimeService runtime,
      ManagementService management,
      RepositoryService repository,
      @Value("${okrkpi.saga.worker.id:${spring.application.name}-${random.uuid}}")
          String workerId) {
    return new FlowableSagaEngine(runtime, management, repository, workerId);
  }

  @Bean
  ApplicationRunner sagaProcessDeployer(FlowableSagaEngine engine) {
    return args -> engine.deploy();
  }

  @Bean
  SagaJournal sagaJournal(OutboxWriter outbox) {
    return new OutboxSagaJournal(outbox);
  }

  @Bean
  SagaService sagaService(
      SagaRepository sagas,
      SagaCatalog catalog,
      FlowableSagaEngine engine,
      SagaJournal journal,
      Clock clock) {
    return new SagaService(sagas, catalog, engine, journal, clock);
  }

  @Bean
  SagaJobSource sagaJobSource(SagaService service) {
    return new SagaJobSource(service);
  }

  /** Worker chỉ chạy khi bật thực thi nền ({@code WF_ASYNC_ENABLED}), như bộ thực thi Flowable. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(prefix = "okrkpi.saga.worker", name = "enabled")
  static class WorkerConfiguration {

    @Bean
    SagaWorker sagaWorker(
        FlowableSagaEngine engine,
        SagaService service,
        @Value("${okrkpi.saga.worker.batch-size:16}") int batchSize,
        @Value("${okrkpi.saga.worker.lock-duration:5m}") Duration lock) {
      return new SagaWorker(engine, service, batchSize, lock);
    }
  }
}
