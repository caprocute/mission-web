package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierTransferService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierTransferService.TransferJob;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code EDXML_TRANSFER} của {@code createDossierPackageTransfer} (CORE-06-08): mã tác vụ là
 * mã lần yêu cầu gửi ({@code batch_id}); đang gửi hoặc chờ thử lại là {@code DANG_CHAY} (thông điệp
 * MSG-11-06 khi có gói con lỗi), mọi gói con có biên nhận là {@code HOAN_TAT} với {@code
 * resultLocation} là gói, hết lượt thử là {@code THAT_BAI}. Loại tác vụ là suy luận (hợp đồng chỉ
 * nêu ví dụ).
 */
public class PackageTransferJobs implements JobSource {

  static final String TYPE = "EDXML_TRANSFER";

  private final DossierTransferService service;

  public PackageTransferJobs(DossierTransferService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "dossier.package_transfer";
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
        .map(j -> new TrackedJob(job(j), ActorId.of(j.requester()), false));
  }

  static Job job(TransferJob j) {
    String id = j.batchId().toString();
    var at = (j.updatedAt() == null ? Instant.now() : j.updatedAt()).atOffset(ZoneOffset.UTC);
    int total = j.rows().size();
    int percent = total == 0 ? 0 : j.acknowledged() * 100 / total;
    return switch (j.status()) {
      case HOAN_TAT ->
          new Job(
              id,
              TYPE,
              JobStatus.HOAN_TAT,
              100,
              j.message(),
              "/api/v1/dossiers/packages/" + j.packageId(),
              null,
              at);
      case THAT_BAI -> new Job(id, TYPE, JobStatus.THAT_BAI, percent, j.message(), null, null, at);
      case DANG_CHAY ->
          new Job(id, TYPE, JobStatus.DANG_CHAY, percent, j.message(), null, null, at);
    };
  }
}
