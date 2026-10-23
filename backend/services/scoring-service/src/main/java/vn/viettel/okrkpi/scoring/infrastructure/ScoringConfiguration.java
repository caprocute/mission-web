package vn.viettel.okrkpi.scoring.infrastructure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.ScoreRecorder;
import vn.viettel.okrkpi.scoring.application.ScoreSimulationService;
import vn.viettel.okrkpi.scoring.application.ScoreTraceService;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.ProfileScorer;
import vn.viettel.okrkpi.scoring.application.policy.RatingClassifier;
import vn.viettel.okrkpi.scoring.application.policy.ScoreCalculator;
import vn.viettel.okrkpi.scoring.application.port.Designations;
import vn.viettel.okrkpi.scoring.application.port.ScoreEvents;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreResultStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreTraceStore;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.infrastructure.events.OutboxScoreEvents;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.CopyScoreResultStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreJobStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreTraceStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcStoredScores;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Lắp ráp đường ghi kết quả (DV-07-01), đường đọc vết tính (DV-07-03) và tính thử, xem trước điểm
 * (DV-07-08) của SVC-07.
 */
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

  @Bean
  RatingClassifier ratingClassifier() {
    return new RatingClassifier();
  }

  @Bean
  ProfileScorer profileScorer(ScoreCalculator calculator, RatingClassifier classifier) {
    return new ProfileScorer(calculator, classifier);
  }

  @Bean
  StoredScores storedScores(JdbcClient jdbc) {
    return new JdbcStoredScores(jdbc);
  }

  @Bean
  ScoreSimulationService.Guard scoreSimulationGuard() {
    return new ScoreSimulationService.Guard();
  }

  @Bean
  ScoreSimulationService scoreSimulationService(
      StoredScores store,
      PolicyPackCache policies,
      ProfileScorer scorer,
      ObjectProvider<Designations> designations,
      ScoreSimulationService.Guard guard) {
    return new ScoreSimulationService(
        store, policies, scorer, designations.getIfAvailable(() -> Designations.NONE), guard);
  }
}
