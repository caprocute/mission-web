package vn.viettel.okrkpi.shared.job;

import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * Tác vụ do một {@link JobSource} trả về, kèm người khởi tạo (chỉ người này xem và huỷ được, theo
 * {@code jobs.yaml}) và cờ cho phép huỷ.
 *
 * @param job trạng thái theo lược đồ hợp đồng
 * @param initiator người khởi tạo tác vụ
 * @param cancellable tác vụ khai báo huỷ được qua {@code cancelJob}
 */
public record TrackedJob(Job job, ActorId initiator, boolean cancellable) {

  public TrackedJob {
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(initiator, "initiator");
  }
}
