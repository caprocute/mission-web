package vn.viettel.okrkpi.scoring.infrastructure.web;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore.JobState;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nguồn {@code getJob} cho {@code scoring.score_job} (DV-07-06): tiến độ theo số hồ sơ đã tính và
 * lỗi trên tổng, kèm thời gian dự kiến tính từ tốc độ từ lúc bắt đầu, để SCR-0801 và saga chốt kỳ
 * theo dõi. Chỉ người tạo tác vụ thấy (tác vụ do sự kiện tạo thuộc tài khoản hệ thống).
 */
public class ScoreJobSource implements JobSource {

  static final String TYPE = "SCORING";

  private final ScoreJobStore jobs;
  private final TransactionOperations tx;

  public ScoreJobSource(ScoreJobStore jobs, TransactionOperations tx) {
    this.jobs = jobs;
    this.tx = tx;
  }

  @Override
  public String name() {
    return "scoring.score_job";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    Optional<JobState> state = tx.execute(t -> jobs.find(id));
    return state == null
        ? Optional.empty()
        : state.map(s -> new TrackedJob(job(s), ActorId.of(s.createdBy()), false));
  }

  static Job job(JobState s) {
    JobStatus status =
        switch (s.status()) {
          case CHO -> JobStatus.CHO;
          case DANG_CHAY -> JobStatus.DANG_CHAY;
          case XONG -> JobStatus.HOAN_TAT;
          case LOI -> JobStatus.THAT_BAI;
        };
    int handled = s.doneItems() + s.failedItems();
    Integer progress =
        status == JobStatus.HOAN_TAT
            ? Integer.valueOf(100)
            : s.totalItems() == 0
                ? Integer.valueOf(0)
                : Integer.valueOf(Math.min(99, handled * 100 / s.totalItems()));
    Instant at = s.updatedAt();
    return new Job(
        s.id().toString(),
        TYPE,
        status,
        status == JobStatus.THAT_BAI ? null : progress,
        message(s, status),
        null,
        null,
        at == null ? null : at.atOffset(ZoneOffset.UTC));
  }

  static String message(JobState s, JobStatus status) {
    String counted =
        "Đã tính "
            + s.doneItems()
            + "/"
            + s.totalItems()
            + " hồ sơ"
            + (s.failedItems() > 0 ? ", " + s.failedItems() + " hồ sơ chưa tính được" : "");
    return switch (status) {
      case CHO -> "Đang chờ tính điểm.";
      case HOAN_TAT -> counted + ".";
      case THAT_BAI -> counted + "; chưa đọc được toàn bộ dữ liệu đầu vào.";
      default -> counted + eta(s) + ".";
    };
  }

  /** Thời gian còn lại theo tốc độ trung bình từ lúc bắt đầu. */
  static String eta(JobState s) {
    int handled = s.doneItems() + s.failedItems();
    if (s.startedAt() == null
        || s.updatedAt() == null
        || handled == 0
        || s.totalItems() <= handled) {
      return "";
    }
    double seconds = Duration.between(s.startedAt(), s.updatedAt()).toMillis() / 1000.0;
    if (seconds <= 0) {
      return "";
    }
    double remaining = (s.totalItems() - handled) / (handled / seconds);
    long minutes = Math.max(1, Math.round(remaining / 60));
    return ", dự kiến còn khoảng " + minutes + " phút";
  }
}
