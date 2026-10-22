package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.application.UiPackService;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.application.port.UiPackNotices;
import vn.viettel.okrkpi.config.infrastructure.job.InMemoryUiPackJobs;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-08-08: UI Pack và vòng duyệt phiên bản của MOD-08 trên {@code configDataSource}. Bộ kiểm tra
 * tự động ({@link UiPackChecker}) do CORE-08-09, CORE-08-10 đăng ký; chưa có thì chỉ kiểm lược đồ.
 */
@Configuration(proxyBeanMethods = false)
public class UiPackConfiguration {

  /**
   * Hàng đợi giới hạn của bước kiểm tra (TL-01 cấm executor không giới hạn); đầy thì chạy tại chỗ.
   */
  static final int QUEUE = 500;

  @Bean
  JdbcUiPackRepository uiPackRepository(@Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcUiPackRepository(JdbcClient.create(dataSource));
  }

  /** EVT-21 của vòng duyệt UI Pack qua {@code config.outbox_event}. */
  @Bean
  UiPackNotices uiPackNotices(
      @Qualifier("configDataSource") DataSource dataSource,
      @Value("${mission.config.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxUiPackNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  InMemoryUiPackJobs uiPackJobs() {
    return new InMemoryUiPackJobs();
  }

  /**
   * Luồng chạy kiểm tra tự động sau khi gửi duyệt. Bọc trong {@link CheckThreads} (không phải
   * {@code Executor}) để không làm cấu hình tự động {@code applicationTaskExecutor} của Spring lùi
   * bước.
   */
  @Bean(destroyMethod = "close")
  CheckThreads uiPackCheckThreads(@Value("${mission.config.ui-pack.check-threads:2}") int threads) {
    return new CheckThreads(
        new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE),
            Thread.ofVirtual().name("ui-pack-check-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy()));
  }

  /** Bộ luồng riêng của bước kiểm tra UI Pack. */
  public record CheckThreads(ExecutorService pool) implements AutoCloseable {
    @Override
    public void close() {
      pool.shutdown();
    }
  }

  @Bean
  UiPackService uiPackService(
      JdbcUiPackRepository uiPackRepository,
      UiPackNotices uiPackNotices,
      ObjectProvider<UiPackChecker> checker,
      InMemoryUiPackJobs uiPackJobs,
      ConfigAudit configAudit,
      CheckThreads uiPackCheckThreads,
      @Qualifier("configDataSource") DataSource dataSource,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new UiPackService(
        uiPackRepository,
        uiPackNotices,
        checker.getIfAvailable(() -> UiPackChecker.NONE),
        uiPackJobs,
        new JdbcRoleHolders(JdbcClient.create(dataSource)),
        configAudit,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc(),
        uiPackCheckThreads.pool(),
        new TransactionTemplate(tm));
  }
}
