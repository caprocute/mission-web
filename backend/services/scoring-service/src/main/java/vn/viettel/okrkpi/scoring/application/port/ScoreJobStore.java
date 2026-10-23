package vn.viettel.okrkpi.scoring.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoreJobStatus;

/** Cổng tác vụ tính ({@code scoring.score_job}). */
public interface ScoreJobStore {

  /** Tạo tác vụ trạng thái {@code CHO}; cùng sự kiện kích hoạt thì trả tác vụ đã có. */
  UUID open(ScoreJob job);

  /**
   * Cộng tiến độ sau một lô; chuyển {@code DANG_CHAY}, rồi {@code XONG} hoặc {@code LOI} khi đã đủ
   * {@code total_items}.
   */
  void progress(UUID tenantId, int periodBucket, UUID jobId, int done, int failed, UUID actorId);

  /** Cộng số hồ sơ phải tính khi tác vụ đọc đầu vào theo trang (tổng chỉ biết dần). */
  void addTotal(UUID tenantId, int periodBucket, UUID jobId, int items);

  /** Đánh dấu tác vụ lỗi vì không đọc hết đầu vào; tiến độ sau đó không đổi trạng thái nữa. */
  void markFailed(UUID tenantId, int periodBucket, UUID jobId, UUID actorId);

  /** Tác vụ tính dần {@code TAM_TINH} đang chờ của kỳ, đã khoá dòng để gộp phạm vi. */
  record Pending(UUID id, String scope) {}

  /**
   * Tác vụ {@code TAM_TINH} đang chờ bắt đầu của kỳ có lý do {@code reason} (khoá {@code FOR UPDATE
   * SKIP LOCKED}).
   */
  Optional<Pending> pendingIncremental(
      UUID tenantId, int periodBucket, UUID periodId, String reason);

  /** Thay phạm vi của tác vụ còn chờ. */
  void updateScope(UUID tenantId, int periodBucket, UUID jobId, CanonicalDoc scope, UUID actorId);

  /** Trạng thái một tác vụ để theo dõi ({@code getJob}). */
  record JobState(
      UUID id,
      int periodBucket,
      ScoreJobKind kind,
      ScoreJobStatus status,
      int totalItems,
      int doneItems,
      int failedItems,
      UUID createdBy,
      Instant startedAt,
      Instant updatedAt) {}

  /** Tác vụ theo mã trong tenant hiện hành. */
  Optional<JobState> find(UUID jobId);
}
