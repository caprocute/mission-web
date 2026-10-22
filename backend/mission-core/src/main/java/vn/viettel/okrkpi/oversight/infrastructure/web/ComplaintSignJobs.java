package vn.viettel.okrkpi.oversight.infrastructure.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code ESIGN} của {@code submitComplaint}: mã tác vụ là mã yêu cầu ký ở SVC-10. Chưa nhận
 * EVT-18 thì {@code DANG_CHAY}; nhận rồi thì {@code HOAN_TAT} với {@code resultLocation} là khiếu
 * nại. Trạng thái đọc từ {@code oversight.complaint} nên đúng trên mọi pod.
 */
public class ComplaintSignJobs implements JobSource {

  static final String TYPE = "ESIGN";
  static final String WAITING = "Đang chờ đồng chí xác nhận ký trên ứng dụng ký số.";
  static final String DONE = "Đã ký số và gửi khiếu nại.";

  private final ComplaintService service;

  public ComplaintSignJobs(ComplaintService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "oversight.complaint";
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
        .map(j -> new TrackedJob(job(jobId, j), ActorId.of(j.complainantId()), false));
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

  private static Job job(String jobId, ComplaintService.SignJob j) {
    if (!j.filed()) {
      return new Job(jobId, TYPE, JobStatus.DANG_CHAY, null, WAITING, null, null, null);
    }
    return new Job(
        jobId,
        TYPE,
        JobStatus.HOAN_TAT,
        100,
        DONE,
        ComplaintController.BASE + "/" + j.complaintId(),
        null,
        j.filedAt() == null ? null : j.filedAt().atOffset(ZoneOffset.UTC));
  }
}
