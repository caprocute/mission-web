package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.application.PeriodLockControlService;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.PeriodLockSagaControl;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.infrastructure.client.PeriodLockCommandRelay;
import vn.viettel.okrkpi.config.infrastructure.job.PeriodLockCommandJobs;
import vn.viettel.okrkpi.config.infrastructure.scheduling.PeriodLockCommandRelayJob;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowSagaClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * CORE-08-05 (phần điều khiển): bản chiếu lô, lỗi, nhật ký và hộp lệnh của saga chốt kỳ, cổng lệnh
 * xuống SVC-06 và dịch vụ điều khiển, trên {@code configDataSource} cùng giao dịch {@code
 * configTransactionManager}. Cổng ghi hộp lệnh trong giao dịch, rồi bộ chuyển tiếp gửi dòng tới
 * {@code controlPeriodCloseSaga} của SVC-06 sau khi commit.
 */
@Configuration(proxyBeanMethods = false)
public class PeriodLockControlConfiguration {

  @Bean
  JdbcLockProjectionRepository lockProjectionRepository(
      @Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcLockProjectionRepository(JdbcClient.create(dataSource));
  }

  /**
   * Bộ chuyển tiếp hộp lệnh tới {@code controlPeriodCloseSaga}; chưa khai {@code
   * mission.config.workflow.base-url} thì không gửi, dòng giữ trạng thái chờ. Giao dịch {@code
   * REQUIRES_NEW} vì có thể chạy trong {@code afterCommit} của giao dịch ghi dòng.
   */
  @Bean
  PeriodLockCommandRelay periodLockCommandRelay(
      @Qualifier("configDataSource") DataSource dataSource,
      @Qualifier("configTransactionManager") PlatformTransactionManager transactions,
      ObjectProvider<WorkflowSagaClient> workflow,
      ObjectProvider<ObjectMapper> json) {
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new PeriodLockCommandRelay(
        JdbcClient.create(dataSource),
        tx,
        new PeriodLockCommandRelay.Sender() {
          @Override
          public boolean available() {
            return workflow.getIfAvailable() != null;
          }

          @Override
          public WorkflowSagaClient.ControlOutcome send(
              TenantId tenant, UUID sagaId, UUID commandId, Map<String, Object> body) {
            return workflow.getObject().controlPeriodClose(tenant, sagaId, commandId, body);
          }
        },
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  @Bean
  PeriodLockSagaControl periodLockSagaControl(
      @Qualifier("configDataSource") DataSource dataSource,
      ObjectProvider<ObjectMapper> json,
      PeriodLockCommandRelay periodLockCommandRelay) {
    return new QueuedPeriodLockSagaControl(
        JdbcClient.create(dataSource),
        json.getIfAvailable(ObjectMapper::new),
        periodLockCommandRelay);
  }

  /**
   * Chuyển lại hộp lệnh định kỳ; tắt bằng {@code mission.config.period-lock.relay.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.config.period-lock.relay",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Relay {

    @Bean
    PeriodLockCommandRelayJob periodLockCommandRelayJob(
        PeriodLockCommandRelay periodLockCommandRelay,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new PeriodLockCommandRelayJob(periodLockCommandRelay, directory, cell);
    }
  }

  @Bean
  PeriodLockControlService periodLockControlService(
      JdbcLockOrderRepository lockOrderRepository,
      JdbcLockProjectionRepository lockProjectionRepository,
      JdbcPeriodRepository periodRepository,
      PeriodLockSagaControl periodLockSagaControl,
      LockOrderNotices lockOrderNotices,
      ConfigAudit configAudit,
      PeriodConfigService periodConfigService,
      @Qualifier("configDataSource") DataSource dataSource) {
    return new PeriodLockControlService(
        lockOrderRepository,
        lockProjectionRepository,
        periodRepository,
        periodLockSagaControl,
        lockOrderNotices,
        new JdbcRoleHolders(JdbcClient.create(dataSource)),
        configAudit,
        (tenant, period, saga, actor, order, reason) ->
            periodConfigService.transition(
                tenant, period, PeriodState.MO, saga, actor, order, reason),
        Clock.systemUtc());
  }

  /** Nguồn {@code /api/v1/jobs/{id}} cho tác vụ chạy lại hồ sơ lỗi và bù trừ. */
  @Bean
  PeriodLockCommandJobs periodLockCommandJobs(PeriodLockControlService periodLockControlService) {
    return new PeriodLockCommandJobs(periodLockControlService);
  }
}
