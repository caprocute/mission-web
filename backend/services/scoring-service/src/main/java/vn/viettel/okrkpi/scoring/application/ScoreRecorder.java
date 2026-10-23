package vn.viettel.okrkpi.scoring.application;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.scoring.application.port.ScoreEvents;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreResultStore;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreStatus;

/**
 * Ghi kết quả tính theo lô (DV-07-01): một giao dịch cho mỗi lô gồm tập đầu vào, kết quả, vết tính,
 * EVT-10 cho từng kết quả {@code OK} và tiến độ tác vụ, nên sự kiện chỉ tồn tại khi kết quả đã
 * commit (baseline mục 8.1). Ngữ cảnh tenant do bên gọi đặt ({@code TenantContext}).
 */
public class ScoreRecorder {

  private final ScoreJobStore jobs;
  private final ScoreResultStore results;
  private final ScoreEvents events;

  public ScoreRecorder(ScoreJobStore jobs, ScoreResultStore results, ScoreEvents events) {
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.results = Objects.requireNonNull(results, "results");
    this.events = Objects.requireNonNull(events, "events");
  }

  /** Tạo tác vụ tính; cùng sự kiện kích hoạt thì trả tác vụ đã có. */
  @Transactional
  public UUID open(ScoreJob job) {
    return jobs.open(job);
  }

  /**
   * Ghi một lô. Kết quả trùng kết quả mới nhất của hồ sơ ở băm đầu vào và băm gói thì không ghi và
   * không phát EVT-10 (tính lại luỹ đẳng), nhưng vẫn tính vào tiến độ tác vụ.
   *
   * @return số tập đầu vào mới ghi
   */
  @Transactional
  public int record(ScoreBatch batch) {
    Set<UUID> unchanged = results.unchanged(batch);
    List<ComputedScore> fresh =
        batch.items().stream().filter(s -> !unchanged.contains(s.resultId())).toList();
    int newInputs = 0;
    if (!fresh.isEmpty()) {
      newInputs = append(batch.withItems(fresh));
    }
    jobs.progress(
        batch.tenantId(),
        batch.periodBucket(),
        batch.jobId(),
        batch.count(ScoreStatus.OK),
        batch.count(ScoreStatus.LOI),
        batch.actorId());
    return newInputs;
  }

  private int append(ScoreBatch batch) {
    ScoreResultStore.Appended appended = results.append(batch);
    for (ComputedScore score : batch.items()) {
      if (score.status() == ScoreStatus.OK) {
        Long version = appended.versions().get(score.resultId());
        if (version == null) {
          throw new IllegalStateException("Thiếu thứ tự kết quả " + score.resultId());
        }
        events.computed(batch, score, version);
      }
    }
    return appended.newInputs();
  }
}
