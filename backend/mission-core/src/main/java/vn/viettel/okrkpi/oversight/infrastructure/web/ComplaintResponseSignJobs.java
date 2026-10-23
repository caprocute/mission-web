package vn.viettel.okrkpi.oversight.infrastructure.web;

import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintResolutionService;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code ESIGN} của {@code respondComplaint} (CORE-07-04): mã tác vụ là mã yêu cầu ký. Chưa
 * nhận EVT-18 thì {@code DANG_CHAY}; nhận rồi thì {@code HOAN_TAT}, {@code resultLocation} là khiếu
 * nại. Người theo dõi là người ký.
 */
public class ComplaintResponseSignJobs implements JobSource {

  static final String WAITING =
      "Đang chờ đồng chí xác nhận ký văn bản trả lời trên ứng dụng ký số.";
  static final String DONE = "Đã ký số văn bản trả lời khiếu nại.";

  private final ComplaintResolutionService service;

  public ComplaintResponseSignJobs(ComplaintResolutionService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "oversight.complaint-response";
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
        .responseJob(tenant, id)
        .map(j -> new TrackedJob(job(jobId, j), ActorId.of(j.responderId()), false));
  }

  private static Job job(String jobId, ComplaintResolutionService.ResponseJob j) {
    if (!j.responded()) {
      return new Job(
          jobId, ComplaintSignJobs.TYPE, JobStatus.DANG_CHAY, null, WAITING, null, null, null);
    }
    return new Job(
        jobId,
        ComplaintSignJobs.TYPE,
        JobStatus.HOAN_TAT,
        100,
        DONE,
        ComplaintController.BASE + "/" + j.complaintId(),
        null,
        j.respondedAt().atOffset(ZoneOffset.UTC));
  }
}
