package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierSigningService;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code ESIGN} của {@code signDossierPackage}: mã tác vụ là mã yêu cầu ký ở SVC-10. Gói
 * chưa nộp thì {@code DANG_CHAY} (chờ người ký xác nhận); đã nộp thì {@code HOAN_TAT} với {@code
 * resultLocation} là gói (MSG-11-05). Trạng thái đọc từ {@code dossier.dossier_package} nên đúng
 * trên mọi pod.
 */
public class PackageSignJobs implements JobSource {

  static final String TYPE = "ESIGN";
  static final String WAITING = "Đang chờ đồng chí xác nhận ký trên ứng dụng ký số.";
  static final String DONE = "Đã ký số và nộp gói hồ sơ.";

  private final DossierSigningService service;

  public PackageSignJobs(DossierSigningService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "dossier.dossier_package.sign";
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
        .map(j -> new TrackedJob(job(jobId, j), ActorId.of(j.requester()), false));
  }

  private static Job job(String jobId, DossierSigningService.SignJob j) {
    boolean done = j.status() == PackageStatus.DA_NOP || j.status() == PackageStatus.DA_LUU_TRU;
    if (!done) {
      return new Job(jobId, TYPE, JobStatus.DANG_CHAY, null, WAITING, null, null, null);
    }
    return new Job(
        jobId,
        TYPE,
        JobStatus.HOAN_TAT,
        100,
        DONE,
        "/api/v1/dossiers/packages/" + j.packageId(),
        null,
        j.submittedAt() == null
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : j.submittedAt().atOffset(ZoneOffset.UTC));
  }
}
