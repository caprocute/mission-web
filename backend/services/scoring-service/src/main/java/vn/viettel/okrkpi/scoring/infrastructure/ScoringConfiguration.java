package vn.viettel.okrkpi.scoring.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.ScoreRecorder;
import vn.viettel.okrkpi.scoring.application.port.ScoreEvents;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreResultStore;
import vn.viettel.okrkpi.scoring.infrastructure.events.OutboxScoreEvents;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.CopyScoreResultStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreJobStore;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/** Lắp ráp đường ghi kết quả của SVC-07 (DV-07-01). */
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
}
