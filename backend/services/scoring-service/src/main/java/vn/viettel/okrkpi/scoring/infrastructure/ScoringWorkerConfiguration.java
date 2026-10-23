package vn.viettel.okrkpi.scoring.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.scoring.application.IncrementalScoring;
import vn.viettel.okrkpi.scoring.application.InputAssembler;
import vn.viettel.okrkpi.scoring.application.ProfileRecheck;
import vn.viettel.okrkpi.scoring.application.ScoreJobRunner;
import vn.viettel.okrkpi.scoring.application.ScoreJobWorker;
import vn.viettel.okrkpi.scoring.application.ScoreRecorder;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.ProfileScorer;
import vn.viettel.okrkpi.scoring.application.port.OpenPeriods;
import vn.viettel.okrkpi.scoring.application.port.ScoreChunkQueue;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoringInputs;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.infrastructure.events.IncrementalScoringConsumers;
import vn.viettel.okrkpi.scoring.infrastructure.inputs.HttpScoringInputs;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreChunkQueue;
import vn.viettel.okrkpi.scoring.infrastructure.web.ScoreJobSource;
import vn.viettel.okrkpi.scoring.infrastructure.worker.ScoreJobPoller;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * Lắp ráp tính dần theo sự kiện (DV-07-04), hàng đợi lô và worker tính lại hàng loạt, tính lại đối
 * chiếu và theo dõi tác vụ (DV-07-06) của SVC-07.
 */
@Configuration(proxyBeanMethods = false)
public class ScoringWorkerConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(ScoringWorkerConfiguration.class);

  @Bean
  ScoringInputs scoringInputs(
      ResilientClientFactory clients,
      ObjectProvider<RestClient.Builder> builders,
      @Value("${mission.scoring.inputs.base-url:http://127.0.0.1:8080}") String baseUrl,
      @Value("${mission.scoring.inputs.read-timeout:PT30S}") Duration readTimeout) {
    // Một trang 200 hồ sơ kèm việc lá lớn hơn nhiều lời gọi thường, nên thời hạn đọc riêng (TL-01
    // "Timeout mặc định": tầng ngoài là time limiter cùng tên instance, khai ở application.yaml).
    SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
    http.setConnectTimeout(Duration.ofMillis(500));
    http.setReadTimeout(readTimeout);
    RestClient client =
        builders.getIfAvailable(RestClient::builder).baseUrl(baseUrl).requestFactory(http).build();
    return new HttpScoringInputs(clients.create("appraisal-inputs", client));
  }

  @Bean
  InputAssembler inputAssembler() {
    return new InputAssembler();
  }

  @Bean
  ScoreJobRunner scoreJobRunner(
      PolicyPackCache policies,
      InputAssembler assembler,
      ProfileScorer scorer,
      StoredScores stored) {
    return new ScoreJobRunner(policies, assembler, scorer, stored, Clock.systemUTC());
  }

  @Bean
  ScoreChunkQueue scoreChunkQueue(JdbcClient jdbc, ObjectProvider<MeterRegistry> meters) {
    JdbcScoreChunkQueue queue = new JdbcScoreChunkQueue(jdbc);
    meters.ifAvailable(
        registry ->
            Gauge.builder("scoring.chunk.pending", queue, ScoringWorkerConfiguration::pending)
                .description("Số lô tính điểm còn chờ hoặc đang chạy của mọi tenant")
                .register(registry));
    return queue;
  }

  private static double pending(ScoreChunkQueue queue) {
    try {
      return queue.pending();
    } catch (RuntimeException e) {
      LOG.debug("Không đọc được số lô tính điểm đang chờ", e);
      return Double.NaN;
    }
  }

  @Bean
  ScoreJobWorker scoreJobWorker(
      ScoreChunkQueue queue,
      ScoreJobStore jobs,
      ScoringInputs inputs,
      ScoreJobRunner runner,
      ScoreRecorder recorder,
      PlatformTransactionManager tm,
      @Value("${mission.scoring.worker.lease:PT10M}") Duration lease,
      @Value("${mission.scoring.worker.retry-backoff:PT10S}") Duration backoff) {
    return new ScoreJobWorker(
        queue, jobs, inputs, runner, recorder, new TransactionTemplate(tm), lease, backoff);
  }

  @Bean
  IncrementalScoring incrementalScoring(
      StoredScores stored, OpenPeriods periods, ScoreJobStore jobs, PolicyPackCache policies) {
    return new IncrementalScoring(stored, periods, jobs, policies);
  }

  @Bean
  IncrementalScoringConsumers incrementalScoringConsumers(IncrementalScoring scoring) {
    return new IncrementalScoringConsumers(scoring);
  }

  @Bean
  ProfileRecheck.Guard profileRecheckGuard() {
    return new ProfileRecheck.Guard();
  }

  @Bean
  ProfileRecheck profileRecheck(
      StoredScores stored, ScoreJobStore jobs, ProfileRecheck.Guard guard) {
    return new ProfileRecheck(stored, jobs, guard);
  }

  @Bean
  ScoreJobSource scoreJobSource(ScoreJobStore jobs, PlatformTransactionManager tm) {
    TransactionTemplate readOnly = new TransactionTemplate(tm);
    readOnly.setReadOnly(true);
    return new ScoreJobSource(jobs, readOnly);
  }

  /** Worker chỉ bật ở pod tính điểm; pod API và kiểm thử ngữ cảnh đặt {@code enabled=false}. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "mission.scoring.worker.enabled", havingValue = "true")
  @EnableScheduling
  static class Polling {

    @Bean
    ScoreJobPoller scoreJobPoller(
        ScoreJobWorker worker, @Value("${mission.scoring.worker.parallelism:4}") int parallelism) {
      return new ScoreJobPoller(worker, parallelism);
    }
  }
}
