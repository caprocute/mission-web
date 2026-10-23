package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.core.RowCallbackHandler;
import vn.viettel.okrkpi.workflow.application.history.port.HistoryArchiveWatermark;

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
    when(provider.orderedStream()).thenAnswer(i -> Stream.of(customListener));

    ObjectProvider<MeterRegistry> registries = mock(ObjectProvider.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(registries.getIfAvailable()).thenReturn(registry);
    ObjectProvider<HistoryArchiveWatermark> watermarks = mock(ObjectProvider.class);
    when(watermarks.getIfAvailable()).thenReturn(Optional::empty);
    FlowableEngineTuning tuning =
        new FlowableEngineTuning(provider, registries, watermarks, Duration.ofHours(1));
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
    assertThat(config.getProcessDefinitionCache()).isInstanceOf(CountingDeploymentCache.class);
    assertThat(config.getCustomPreCommandInterceptors())
        .hasOnlyElementsOfType(CountingCommandInterceptor.class);
    assertThat(config.getHistoryCleaningManager())
        .isInstanceOf(ArchiveGuardedHistoryCleaningManager.class);

    // Không có registry (kiểm thử cắt lát) thì giữ cấu hình mặc định của engine.
    ObjectProvider<MeterRegistry> none = mock(ObjectProvider.class);
    SpringProcessEngineConfiguration plain = new SpringProcessEngineConfiguration();
    new FlowableEngineTuning(provider, none, watermarks, Duration.ofHours(1)).configure(plain);
    assertThat(plain.getHistoryCleaningManager()).isNull();

    SpringDmnEngineConfiguration dmnConfig = new SpringDmnEngineConfiguration();
    tuning.dmnEngineTuning().configure(dmnConfig);
    assertThat(dmnConfig.getIdGenerator()).isInstanceOf(StrongUuidGenerator.class);
  }

  @Test
  void chiSoLayMauGiuGiaTriCuKhiLoiVaDemLoi() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Number.class))).thenReturn(42L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FlowableEngineMetrics metrics = new FlowableEngineMetrics(jdbc, registry);

    metrics.sample();
    assertThat(metrics.dueUnlockedJobs()).isEqualTo(42.0);
    assertThat(registry.get(FlowableEngineMetrics.DUE_UNLOCKED).gauge().value()).isEqualTo(42.0);
    assertThat(registry.get(FlowableEngineMetrics.ACTIVE_DEFINITIONS).gauge().value())
        .isEqualTo(42.0);
    assertThat(registry.find(FlowableEngineMetrics.HI_ROWS).gauges()).hasSize(5);

    when(jdbc.queryForObject(anyString(), eq(Number.class))).thenReturn(null);
    metrics.sample();
    assertThat(metrics.dueUnlockedJobs()).isZero();

    when(jdbc.queryForObject(anyString(), eq(Number.class))).thenReturn(7L);
    metrics.sample();
    when(jdbc.queryForObject(anyString(), eq(Number.class)))
        .thenThrow(new RecoverableDataAccessException("db down"));
    org.mockito.Mockito.doThrow(new RecoverableDataAccessException("db down"))
        .when(jdbc)
        .query(anyString(), org.mockito.ArgumentMatchers.any(RowCallbackHandler.class));
    metrics.sample();
    assertThat(metrics.dueUnlockedJobs()).isEqualTo(7.0);
    assertThat(registry.get(FlowableEngineMetrics.SAMPLE_FAILURES).counter().count())
        .isEqualTo(5.0);
    // Truy vấn đếm luôn có giới hạn.
    assertThat(FlowableEngineMetrics.DUE_UNLOCKED_SQL).contains("LIMIT 100001");
    assertThat(FlowableEngineMetrics.RELTUPLES_SQL).contains("'act_hi_taskinst'");
  }
}
