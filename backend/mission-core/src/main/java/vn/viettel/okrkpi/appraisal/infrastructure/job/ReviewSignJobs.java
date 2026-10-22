package vn.viettel.okrkpi.appraisal.infrastructure.job;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code ESIGN} của {@code appraiseAppraisalProfile}: mã tác vụ là mã yêu cầu ký ở SVC-10,
 * trạng thái đọc từ dòng mức {@code THAM_DINH} nên đúng trên mọi pod.
 */
public class ReviewSignJobs implements JobSource {

  static final String TYPE = "ESIGN";
  static final String WAITING = "Đang chờ đồng chí xác nhận ký trên ứng dụng ký số.";
  static final String DONE = "Đã ký số kết quả thẩm định.";

  private final ReviewService service;

  public ReviewSignJobs(ReviewService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "appraisal.review";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return service
        .signJob(tenant, id)
        .map(j -> new TrackedJob(job(jobId, j), ActorId.of(j.actorId()), false));
  }

  /** Bản {@code DANG_CHAY} trả trong thân 202. */
  public static Job pending(UUID signRequestId) {
    return new Job(
        signRequestId.toString(),
        TYPE,
        JobStatus.DANG_CHAY,
        null,
        WAITING,
        null,
        null,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  static Job job(String jobId, ReviewService.SignJob j) {
    if (!j.signed()) {
      return new Job(jobId, TYPE, JobStatus.DANG_CHAY, null, WAITING, null, null, null);
    }
    return new Job(
        jobId,
        TYPE,
        JobStatus.HOAN_TAT,
        100,
        DONE,
        "/api/v1/appraisals/profiles/" + j.profileId(),
        null,
        j.signedAt().atOffset(ZoneOffset.UTC));
  }
}
