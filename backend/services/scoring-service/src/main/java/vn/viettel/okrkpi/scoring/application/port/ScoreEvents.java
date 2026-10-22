package vn.viettel.okrkpi.scoring.application.port;

import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;

/** Cổng phát EVT-10 {@code scoring.score.computed.v1} qua outbox, trong giao dịch ghi kết quả. */
public interface ScoreEvents {

  /**
   * @param aggregateVersion thứ tự kết quả trong các kết quả của cùng hồ sơ
   */
  void computed(ScoreBatch batch, ComputedScore score, long aggregateVersion);
}
