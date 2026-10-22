package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code ESIGN} của {@code submitAppraisalSelfAssessment}: mã tác vụ là mã yêu cầu ký ở
 * SVC-10. Chưa nhận EVT-18 thì {@code DANG_CHAY}; nhận rồi thì {@code HOAN_TAT} với {@code
 * resultLocation} là hồ sơ. Trạng thái đọc từ {@code appraisal.self_assessment} nên đúng trên mọi
 * pod.
 */
public class SelfAssessmentJobs implements JobSource {

  static final String TYPE = "ESIGN";
  static final String WAITING = "Đang chờ đồng chí xác nhận ký trên ứng dụng ký số.";
  static final String DONE = "Đã ký số và nộp phiếu tự đánh giá.";

  private final SelfAssessmentService service;

  public SelfAssessmentJobs(SelfAssessmentService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "appraisal.self_assessment";
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
        .map(j -> new TrackedJob(job(jobId, j), ActorId.of(j.personId()), false));
  }

  /** Bản {@code DANG_CHAY} trả trong thân 202. */
  static Job pending(UUID signRequestId) {
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

  private static Job job(String jobId, SelfAssessmentService.SignJob j) {
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
