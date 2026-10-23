package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.application.PeriodLockControlService;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.PeriodLockSagaControl;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.infrastructure.job.PeriodLockCommandJobs;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * CORE-08-05 (phần điều khiển): bản chiếu lô, lỗi, nhật ký và hộp lệnh của saga chốt kỳ, cổng lệnh
 * xuống SVC-06 và dịch vụ điều khiển, trên {@code configDataSource} cùng giao dịch {@code
 * configTransactionManager}. Cổng mặc định ghi hộp lệnh vì {@code platform.yaml} chưa có thao tác
 * điều khiển saga.
 */
@Configuration(proxyBeanMethods = false)
public class PeriodLockControlConfiguration {

  @Bean
  JdbcLockProjectionRepository lockProjectionRepository(
      @Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcLockProjectionRepository(JdbcClient.create(dataSource));
  }

  @Bean
  PeriodLockSagaControl periodLockSagaControl(
      @Qualifier("configDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new QueuedPeriodLockSagaControl(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
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
