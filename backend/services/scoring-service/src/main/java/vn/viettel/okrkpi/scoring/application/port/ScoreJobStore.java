package vn.viettel.okrkpi.scoring.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;

/** Cổng tác vụ tính ({@code scoring.score_job}). */
public interface ScoreJobStore {

  /** Tạo tác vụ trạng thái {@code CHO}; cùng sự kiện kích hoạt thì trả tác vụ đã có. */
  UUID open(ScoreJob job);

  /**
   * Cộng tiến độ sau một lô; chuyển {@code DANG_CHAY}, rồi {@code XONG} hoặc {@code LOI} khi đã đủ
   * {@code total_items}.
   */
  void progress(UUID tenantId, int periodBucket, UUID jobId, int done, int failed, UUID actorId);
}
