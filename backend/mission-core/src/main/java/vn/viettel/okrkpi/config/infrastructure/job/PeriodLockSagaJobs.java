package vn.viettel.okrkpi.config.infrastructure.job;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.PeriodLockService;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code PERIOD_LOCK} của {@code startPeriodLock}: mã tác vụ là mã saga chốt kỳ, trạng thái
 * suy ra từ lệnh khoá và bản chiếu sáu bước (QA-06 HT-23). Thiếu nguồn này thì màn hình theo dõi
 * nhận 404 khi hỏi {@code /api/v1/jobs/{id}}; chỉ người khởi chạy thấy tác vụ.
 */
public class PeriodLockSagaJobs implements JobSource {

  static final String JOB_TYPE = "PERIOD_LOCK";

  private final PeriodLockService locks;

  public PeriodLockSagaJobs(PeriodLockService locks) {
    this.locks = Objects.requireNonNull(locks, "locks");
  }

  @Override
  public String name() {
    return "config.period_lock_order";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return locks
        .progressOfSaga(tenant, id)
        .filter(p -> p.order().startedBy() != null)
        .map(p -> new TrackedJob(job(id, p), ActorId.of(p.order().startedBy()), false));
  }

  static Job job(UUID sagaId, PeriodLockService.Progress p) {
    JobStatus status =
        switch (p.order().status()) {
          case HOAN_TAT -> JobStatus.HOAN_TAT;
          case DA_HUY -> JobStatus.DA_HUY;
          default -> JobStatus.DANG_CHAY;
        };
    long done = p.steps().stream().filter(s -> s.done()).count();
    int progress =
        status == JobStatus.HOAN_TAT ? 100 : (int) (done * 100 / Math.max(1, p.steps().size()));
    Instant at = p.order().startedAt();
    return new Job(
        sagaId.toString(),
        JOB_TYPE,
        status,
        status == JobStatus.DA_HUY ? null : progress,
        switch (status) {
          case HOAN_TAT -> "Đã khoá kỳ";
          case DA_HUY -> "Đã huỷ khoá kỳ";
          default -> "Đang chạy khoá kỳ";
        },
        null,
        null,
        at == null ? null : at.atOffset(ZoneOffset.UTC));
  }
}
