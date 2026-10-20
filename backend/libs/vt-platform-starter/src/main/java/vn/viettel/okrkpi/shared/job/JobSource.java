package vn.viettel.okrkpi.shared.job;

import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nguồn trạng thái tác vụ dài của một module (ví dụ {@code score_job}, {@code render_job}, saga
 * khoá kỳ). Mỗi module khai một bean; {@link JobQueryService} gộp các nguồn cho {@code getJob} và
 * {@code cancelJob} của {@code jobs.yaml} (CORE-00-02 gắn đường dẫn).
 */
public interface JobSource {

  /** Tên nguồn để ghi nhật ký, ví dụ {@code scoring.score_job}. */
  String name();

  /**
   * Tác vụ có mã {@code jobId} của tenant, rỗng khi nguồn không quản lý mã này. Nguồn đọc trong ngữ
   * cảnh tenant (RLS) nên không trả tác vụ của tenant khác.
   */
  Optional<TrackedJob> find(TenantId tenant, String jobId);

  /**
   * Yêu cầu huỷ một tác vụ chưa kết thúc mà nguồn đã khai {@link TrackedJob#cancellable()}.
   *
   * @return {@code false} khi tác vụ vừa kết thúc hoặc không dừng được nữa
   */
  default boolean requestCancel(TenantId tenant, String jobId) {
    return false;
  }
}
