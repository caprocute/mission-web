package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.impl.persistence.StrongUuidGenerator;
import org.flowable.dmn.spring.SpringDmnEngineConfiguration;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kiểm thử đơn vị cấu hình tinh chỉnh động cơ Flowable 7 và chỉ số backlog tác vụ bất đồng bộ
 * (DV-06-01 · TL-01 mục 8.7).
 */
class FlowableEngineTuningTest {

  @Test
  @SuppressWarnings("unchecked")
  void configureApDungStrongUuidGeneratorBulkInsertCacheVaDonLichSu() {
    FlowableEventListener customListener = mock(FlowableEventListener.class);
    FlowableEventListener existingListener = mock(FlowableEventListener.class);
    ObjectProvider<FlowableEventListener> provider = mock(ObjectProvider.class);
    when(provider.orderedStream()).thenReturn(Stream.of(customListener));

    FlowableEngineTuning tuning = new FlowableEngineTuning(provider);
    SpringProcessEngineConfiguration config = new SpringProcessEngineConfiguration();
    config.setEventListeners(List.of(existingListener));

    tuning.configure(config);

    assertThat(config.getIdGenerator()).isInstanceOf(StrongUuidGenerator.class);
    String generatedId = config.getIdGenerator().getNextId();
    assertThat(UUID.fromString(generatedId)).isNotNull();
    assertThat(config.isBulkInsertEnabled()).isTrue();
    assertThat(config.getMaxNrOfStatementsInBulkInsert())
        .isEqualTo(FlowableEngineTuning.MAX_BULK_INSERT_STATEMENTS);
    assertThat(config.getProcessDefinitionCacheLimit())
        .isEqualTo(FlowableEngineTuning.PROCESS_DEFINITION_CACHE_LIMIT);
    assertThat(config.isEnableProcessDefinitionInfoCache()).isTrue();
    assertThat(config.isEnableHistoryCleaning()).isTrue();
    assertThat(config.getCleanInstancesEndedAfter())
        .isEqualTo(FlowableEngineTuning.HISTORY_RETENTION);
    assertThat(config.getHistoryCleaningTimeCycleConfig())
        .isEqualTo(FlowableEngineTuning.HISTORY_CLEANING_CRON);
    assertThat(config.getEventListeners()).containsExactly(existingListener, customListener);

    SpringDmnEngineConfiguration dmnConfig = new SpringDmnEngineConfiguration();
    tuning.dmnEngineTuning().configure(dmnConfig);
    assertThat(dmnConfig.getIdGenerator()).isInstanceOf(StrongUuidGenerator.class);
  }

  @Test
  void asyncBacklogMetricsDemSoJobQuaHanChuaKhoaVaAnToanKhiLoiCsdl() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FlowableAsyncBacklogMetrics metrics = new FlowableAsyncBacklogMetrics(jdbcTemplate, registry);

    assertThat(metrics.countDueUnlockedJobs()).isEqualTo(42.0);
    assertThat(registry.get(FlowableAsyncBacklogMetrics.METRIC_NAME).gauge().value())
        .isEqualTo(42.0);

    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(metrics.countDueUnlockedJobs()).isZero();

    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
        .thenThrow(new RecoverableDataAccessException("db down"));
    assertThat(metrics.countDueUnlockedJobs()).isZero();
  }
}
