package vn.viettel.okrkpi.task.infrastructure.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService;

/**
 * Tác vụ {@code PLAN_APPROVAL} của {@code approvePlanVersion}: mã tác vụ là mã yêu cầu ký đã gắn
 * vào {@code task.plan_version.sign_request_id}, nên mọi bản sao của mission-core trả cùng kết quả
 * mà không cần bảng tác vụ riêng. Tác vụ {@code HOAN_TAT} khi yêu cầu ký đã tạo, {@code
 * resultLocation} trỏ {@code /esign/requests/{id}} như hợp đồng.
 */
public class PlanApprovalJobs implements JobSource {

  static final String TYPE = "PLAN_APPROVAL";

  private final PlanApprovalService approvals;

  public PlanApprovalJobs(PlanApprovalService approvals) {
    this.approvals = Objects.requireNonNull(approvals, "approvals");
  }

  @Override
  public String name() {
    return "task.plan-approval";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return approvals
        .signRequestInitiator(tenant, id)
        .map(initiator -> new TrackedJob(done(id), ActorId.of(initiator), false));
  }

  static Job done(UUID signRequestId) {
    return new Job(
        signRequestId.toString(),
        TYPE,
        JobStatus.HOAN_TAT,
        100,
        "Đã tạo yêu cầu ký số, vui lòng xác nhận trên ứng dụng ký số",
        "/api/v1/esign/requests/" + signRequestId,
        null,
        OffsetDateTime.now(ZoneOffset.UTC));
  }
}
