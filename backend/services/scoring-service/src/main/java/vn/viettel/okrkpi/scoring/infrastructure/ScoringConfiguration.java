package vn.viettel.okrkpi.scoring.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.ScoreRecorder;
import vn.viettel.okrkpi.scoring.application.ScoreTraceService;
import vn.viettel.okrkpi.scoring.application.policy.ScoreCalculator;
import vn.viettel.okrkpi.scoring.application.port.ScoreEvents;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreResultStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreTraceStore;
import vn.viettel.okrkpi.scoring.infrastructure.events.OutboxScoreEvents;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.CopyScoreResultStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreJobStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreTraceStore;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/** Lắp ráp đường ghi kết quả (DV-07-01) và đường đọc vết tính (DV-07-03) của SVC-07. */
@Configuration(proxyBeanMethods = false)
public class ScoringConfiguration {

  @Bean
  ScoreJobStore scoreJobStore(JdbcClient jdbc) {
    return new JdbcScoreJobStore(jdbc);
  }

  @Bean
  ScoreResultStore scoreResultStore(JdbcTemplate jdbc) {
    return new CopyScoreResultStore(jdbc);
  }

  @Bean
  ScoreEvents scoreEvents(OutboxWriter outbox) {
    return new OutboxScoreEvents(outbox);
  }

  @Bean
  ScoreRecorder scoreRecorder(ScoreJobStore jobs, ScoreResultStore results, ScoreEvents events) {
    return new ScoreRecorder(jobs, results, events);
  }

  @Bean
  ScoreTraceStore scoreTraceStore(JdbcClient jdbc) {
    return new JdbcScoreTraceStore(jdbc);
  }

  @Bean
  ScoreTraceService.Guard scoreTraceGuard() {
    return new ScoreTraceService.Guard();
  }

  @Bean
  ScoreTraceService scoreTraceService(ScoreTraceStore store, ScoreTraceService.Guard guard) {
    return new ScoreTraceService(store, guard);
  }

  @Bean
  ScoreCalculator scoreCalculator() {
    return new ScoreCalculator();
  }
}
