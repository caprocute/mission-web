package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.impl.interceptor.CommandInterceptor;
import org.flowable.common.engine.impl.persistence.StrongUuidGenerator;
import org.flowable.dmn.spring.SpringDmnEngineConfiguration;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.workflow.application.history.port.HistoryArchiveWatermark;

/**
 * Tinh chỉnh động cơ Flowable 7 theo TL-01 chương 8 mục 8.7 và TL-03 mục 6.13.1 (DV-06-01):
 *
 * <ul>
 *   <li>Dùng {@link StrongUuidGenerator} tránh tranh chấp khoá hàng {@code next.dbid} trong {@code
 *       ACT_GE_PROPERTY}.
 *   <li>Bật chèn hàng loạt (bulk insert) tối đa 100 câu lệnh mỗi lô.
 *   <li>Giới hạn bộ đệm định nghĩa quy trình ở 200 bản ghi và bật bộ đệm thông tin định nghĩa.
 *   <li>Bật dọn lịch sử tự động lúc 02:00 sáng hằng ngày với thời hạn giữ 400 ngày (theo TL-03 mục
 *       6.13.1 để thoả NFR-07 giữ tối thiểu 13 tháng dữ liệu nóng), chỉ xoá tiến trình đã chép sang
 *       kho phân tích (DV-06-11).
 *   <li>Đếm giao dịch, xung đột khoá lạc quan và trúng/trượt bộ đệm định nghĩa cho chỉ số G2.
 * </ul>
 */
@Configuration
public class FlowableEngineTuning
    implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {

  public static final int MAX_BULK_INSERT_STATEMENTS = 100;
  public static final int PROCESS_DEFINITION_CACHE_LIMIT = 200;
  public static final Duration HISTORY_RETENTION = Duration.ofDays(400);
  public static final String HISTORY_CLEANING_CRON = "0 0 2 * * ?";

  private final ObjectProvider<FlowableEventListener> eventListenersProvider;
  private final ObjectProvider<MeterRegistry> registryProvider;
  private final ObjectProvider<HistoryArchiveWatermark> watermarkProvider;
  private final Duration archiveSafetyMargin;

  public FlowableEngineTuning(
      ObjectProvider<FlowableEventListener> eventListenersProvider,
      ObjectProvider<MeterRegistry> registryProvider,
      ObjectProvider<HistoryArchiveWatermark> watermarkProvider,
      @Value("${okrkpi.workflow.history-archive.safety-margin:1h}") Duration archiveSafetyMargin) {
    this.eventListenersProvider = eventListenersProvider;
    this.registryProvider = registryProvider;
    this.watermarkProvider = watermarkProvider;
    this.archiveSafetyMargin = archiveSafetyMargin;
  }

  @Override
  public void configure(SpringProcessEngineConfiguration cfg) {
    cfg.setIdGenerator(new StrongUuidGenerator());
    cfg.setBulkInsertEnabled(true);
    cfg.setMaxNrOfStatementsInBulkInsert(MAX_BULK_INSERT_STATEMENTS);
    cfg.setProcessDefinitionCacheLimit(PROCESS_DEFINITION_CACHE_LIMIT);
    cfg.setEnableProcessDefinitionInfoCache(true);
    cfg.setEnableHistoryCleaning(true);
    cfg.setCleanInstancesEndedAfter(HISTORY_RETENTION);
    cfg.setHistoryCleaningTimeCycleConfig(HISTORY_CLEANING_CRON);
    MeterRegistry registry = registryProvider.getIfAvailable();
    if (registry != null) {
      cfg.setProcessDefinitionCache(
          new CountingDeploymentCache<>(PROCESS_DEFINITION_CACHE_LIMIT, registry));
      List<CommandInterceptor> pre = new ArrayList<>();
      if (cfg.getCustomPreCommandInterceptors() != null) {
        pre.addAll(cfg.getCustomPreCommandInterceptors());
      }
      pre.add(new CountingCommandInterceptor(registry));
      cfg.setCustomPreCommandInterceptors(pre);
      HistoryArchiveWatermark watermark = watermarkProvider.getIfAvailable();
      if (watermark != null) {
        cfg.setHistoryCleaningManager(
            new ArchiveGuardedHistoryCleaningManager(
                cfg, watermark, archiveSafetyMargin, registry));
      }
    }

    List<FlowableEventListener> listeners =
        new ArrayList<>(eventListenersProvider.orderedStream().toList());
    if (!listeners.isEmpty()) {
      List<FlowableEventListener> existing = cfg.getEventListeners();
      if (existing != null) {
        listeners.addAll(0, existing);
      }
      cfg.setEventListeners(listeners);
    }
  }

  @Bean
  EngineConfigurationConfigurer<SpringDmnEngineConfiguration> dmnEngineTuning() {
    return dmnCfg -> dmnCfg.setIdGenerator(new StrongUuidGenerator());
  }
}
