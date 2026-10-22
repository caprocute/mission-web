package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService;
import vn.viettel.okrkpi.policy.application.port.CelJsRunner;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.infrastructure.client.HttpCelJsRunner;
import vn.viettel.okrkpi.policy.infrastructure.job.InMemoryPolicyTestJobs;
import vn.viettel.okrkpi.shared.infrastructure.cel.CelJsClient;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * CORE-05-07: chạy kiểm thử Policy Pack. Bộ chạy cel-js cấu hình bằng {@code
 * mission.policy.cel-js.url}; bỏ trống thì không đối chiếu được và lần chạy không đạt khi {@code
 * mission.policy.cel-js.required} bật (mặc định, BR-15-07).
 */
@Configuration(proxyBeanMethods = false)
public class PolicyTestRunConfiguration {

  private static final int QUEUE = 100;

  @Bean
  JdbcPolicyTestRunRepository policyTestRunRepository(
      @Qualifier("policyDataSource") DataSource dataSource) {
    return new JdbcPolicyTestRunRepository(JdbcClient.create(dataSource));
  }

  @Bean
  InMemoryPolicyTestJobs policyTestJobs() {
    return new InMemoryPolicyTestJobs();
  }

  @Bean
  CelJsRunner policyCelJsRunner(
      @Value("${mission.policy.cel-js.url:}") String url,
      @Value("${mission.policy.cel-js.timeout:30s}") Duration timeout) {
    if (url == null || url.isBlank()) {
      return CelJsRunner.NONE;
    }
    return new HttpCelJsRunner(new CelJsClient(URI.create(url), timeout));
  }

  /**
   * Luồng chạy tác vụ {@code TEST_RUN}. Bọc trong {@link TestRunThreads} (không phải {@code
   * Executor}) để không làm cấu hình tự động {@code applicationTaskExecutor} của Spring lùi bước.
   */
  @Bean(destroyMethod = "close")
  TestRunThreads policyTestRunThreads(@Value("${mission.policy.test-run.threads:2}") int threads) {
    return new TestRunThreads(
        new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE),
            Thread.ofVirtual().name("policy-test-run-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy()));
  }

  /** Bộ luồng riêng của tác vụ chạy kiểm thử Policy Pack. */
  public record TestRunThreads(ExecutorService pool) implements AutoCloseable {
    @Override
    public void close() {
      pool.shutdown();
    }
  }

  @Bean
  @SuppressWarnings("java:S107")
  PolicyTestRunService policyTestRunService(
      JdbcPolicyPackRepository policyPackRepository,
      JdbcGoldenTestCaseRepository goldenTestCaseRepository,
      JdbcPolicyTestRunRepository policyTestRunRepository,
      PolicyDecisionTableService policyDecisionTableService,
      InMemoryPolicyTestJobs policyTestJobs,
      CelJsRunner policyCelJsRunner,
      @Value("${mission.policy.cel-js.required:true}") boolean celJsRequired,
      PolicyAudit policyAudit,
      TestRunThreads policyTestRunThreads,
      @Qualifier("policyTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyTestRunService(
        policyPackRepository,
        goldenTestCaseRepository,
        policyTestRunRepository,
        policyDecisionTableService,
        policyTestJobs,
        policyCelJsRunner,
        celJsRequired,
        policyAudit,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc(),
        policyTestRunThreads.pool(),
        new TransactionTemplate(tm));
  }
}
