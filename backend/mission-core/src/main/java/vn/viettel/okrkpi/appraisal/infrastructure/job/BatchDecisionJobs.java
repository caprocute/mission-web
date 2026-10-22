package vn.viettel.okrkpi.appraisal.infrastructure.job;

import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.View;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code ESIGN_BATCH} của {@code createAppraisalBatchDecision}: mã tác vụ là mã lô, trạng
 * thái đọc từ {@code appraisal.batch_decision} và số dòng mức đã ký nên đúng trên mọi pod. {@code
 * resultLocation} trỏ tới lô ngay từ khi nhận (hợp đồng {@code AppraisalBatchDecisionJob}).
 */
public class BatchDecisionJobs implements JobSource {

  public static final String TYPE = "ESIGN_BATCH";

  private final BatchDecisionService service;

  public BatchDecisionJobs(BatchDecisionService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "appraisal.batch-decision";
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
        .job(tenant, id)
        .map(v -> new TrackedJob(job(v), ActorId.of(v.batch().deciderId()), false));
  }

  /** Đường dẫn lô. */
  public static String location(UUID batchId) {
    return "/api/v1/appraisals/batch-decisions/" + batchId;
  }

  /** Tác vụ theo trạng thái lô (MSG-09-15, MSG-09-16). */
  public static Job job(View v) {
    var b = v.batch();
    int total = b.profileCount();
    int signed = v.signedCount();
    JobStatus status =
        switch (b.status()) {
          case BatchDecisionService.NHAP -> JobStatus.CHO;
          case BatchDecisionService.CHO_KY -> JobStatus.DANG_CHAY;
          case BatchDecisionService.HUY -> JobStatus.DA_HUY;
          default -> JobStatus.HOAN_TAT;
        };
    String message =
        switch (b.status()) {
          case BatchDecisionService.DA_KY ->
              "Đã hoàn tất quyết định "
                  + b.decisionNo()
                  + ": "
                  + total
                  + " hồ sơ đã ký số và thông báo tới cán bộ.";
          case BatchDecisionService.HUY -> "Đã huỷ lô quyết định.";
          case BatchDecisionService.LOI_MOT_PHAN ->
              "Đã ký " + signed + "/" + total + " hồ sơ; các hồ sơ còn lại chưa được ký.";
          default -> "Đã ký " + signed + "/" + total + " hồ sơ.";
        };
    int progress =
        BatchDecisionService.DA_KY.equals(b.status()) ? 100 : total == 0 ? 0 : signed * 100 / total;
    return new Job(
        b.id().toString(),
        TYPE,
        status,
        progress,
        message,
        location(b.id()),
        null,
        b.updatedAt().atOffset(ZoneOffset.UTC));
  }
}
