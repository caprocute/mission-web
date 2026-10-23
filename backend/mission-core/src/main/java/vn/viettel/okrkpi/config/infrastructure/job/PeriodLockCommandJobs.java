package vn.viettel.okrkpi.config.infrastructure.job;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.PeriodLockControlService;
import vn.viettel.okrkpi.config.application.port.LockProjectionRepository;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code PERIOD_LOCK_RETRY} ({@code retryFailedPeriodLock}) và {@code PERIOD_UNLOCK} ({@code
 * approvePeriodLockCancel}) đọc từ hộp lệnh {@code config.period_lock_command}: mã tác vụ là mã
 * lệnh, trạng thái do saga chốt kỳ cập nhật khi nhận và xử lý lệnh.
 */
public class PeriodLockCommandJobs implements JobSource {

  private final PeriodLockControlService control;

  public PeriodLockCommandJobs(PeriodLockControlService control) {
    this.control = Objects.requireNonNull(control, "control");
  }

  @Override
  public String name() {
    return "config.period_lock_command";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return control
        .command(tenant, id)
        .filter(c -> c.command().jobType() != null)
        .map(c -> new TrackedJob(job(c), ActorId.of(c.requestedBy()), false));
  }

  static Job job(LockProjectionRepository.CommandRow c) {
    JobStatus status = JobStatus.valueOf(c.status());
    Instant at = c.updatedAt() == null ? c.createdAt() : c.updatedAt();
    return new Job(
        c.id().toString(),
        c.command().jobType(),
        status,
        status == JobStatus.HOAN_TAT ? Integer.valueOf(100) : Integer.valueOf(0),
        message(status),
        null,
        null,
        at == null ? null : at.atOffset(ZoneOffset.UTC));
  }

  static String message(JobStatus status) {
    return switch (status) {
      case CHO -> "Đã ghi nhận yêu cầu, đang chờ tiến trình khoá kỳ xử lý";
      case DANG_CHAY -> "Đang xử lý";
      case HOAN_TAT -> "Đã hoàn tất";
      case THAT_BAI -> "Không thực hiện được, vui lòng xem nhật ký khoá kỳ";
      case DA_HUY -> "Đã huỷ";
    };
  }
}
