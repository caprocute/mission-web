package vn.viettel.okrkpi.scoring.application.port;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.ScoreJobError;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;

/**
 * Hàng đợi lô của tác vụ tính ({@code scoring.score_job_chunk}) và hàng đợi lỗi ({@code
 * scoring.score_job_error}) (DV-07-06). Hai thao tác {@link #due} và {@link #waitingJobs} thấy việc
 * của mọi tenant (chỉ mã, tenant, phân vùng); các thao tác còn lại chạy trong ngữ cảnh tenant của
 * dòng (RLS).
 */
public interface ScoreChunkQueue {

  /** Một dòng của tenant: lô hoặc tác vụ. */
  record Ref(UUID tenantId, int periodBucket, UUID id) {}

  /** Tác vụ của lô. */
  record Job(
      Ref ref,
      UUID periodId,
      ScoreJobKind kind,
      String scope,
      UUID policyPackVersionId,
      UUID actorId,
      int totalItems) {}

  /** Lô đã nhận để xử lý. */
  record Claimed(Ref chunk, int seq, String cursor, int attempts, Job job) {}

  /** Lô đến hạn của mọi tenant (chờ, hoặc đang chạy mà hết hạn thuê). */
  List<Ref> due(int limit);

  /** Tác vụ chờ bắt đầu của mọi tenant. */
  List<Ref> waitingJobs(int limit);

  /** Số lô còn phải tính của mọi tenant (chỉ số co giãn). */
  long pending();

  /** Chuyển tác vụ {@code CHO} sang {@code DANG_CHAY}; trống khi worker khác đã nhận. */
  Optional<Job> startJob(Ref job);

  /** Thêm lô; lô cùng thứ tự đã có thì bỏ qua. */
  void enqueue(Ref job, int seq, String cursor);

  /** Nhận lô với hạn thuê; trống khi worker khác đã nhận hoặc lô chưa đến hạn. */
  Optional<Claimed> claim(Ref chunk, Duration lease);

  /** Khoá dòng tác vụ để các lô kết thúc tuần tự (tính trạng thái cuối đúng). */
  void lockJob(Ref job);

  /** Lô xong với số hồ sơ đã tính. */
  void complete(Ref chunk, int items);

  /** Trả lô về hàng đợi, nhận lại sau {@code delay}. */
  void retry(Ref chunk, Duration delay, String error);

  /** Lô lỗi hẳn sau khi đã thử lại. */
  void fail(Ref chunk, String error);

  /** Ghi hàng đợi lỗi của một lô. */
  void errors(Ref job, int seq, int attempts, List<ScoreJobError> errors);
}
