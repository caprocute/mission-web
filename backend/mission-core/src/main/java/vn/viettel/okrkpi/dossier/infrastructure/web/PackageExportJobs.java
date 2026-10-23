package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierExportService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierExportService.ExportJob;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code EXPORT} của {@code exportDossierPackage} (CORE-06-08): mã tác vụ là mã dòng {@code
 * dossier.package_export}, trạng thái đọc từ CSDL nên đúng trên mọi pod; khi {@code HOAN_TAT},
 * {@code resultLocation} là URL tải tệp ZIP ký có hạn, cấp mới mỗi lần đọc.
 */
public class PackageExportJobs implements JobSource {

  static final String TYPE = "EXPORT";
  static final String WAITING = "Đang chờ tạo tệp gói hồ sơ.";
  static final String RUNNING = "Đang tạo tệp gói hồ sơ.";
  static final String FAILED = "Chưa tạo được tệp gói hồ sơ, vui lòng thử lại.";
  static final String FAILED_HASH =
      "Chưa tạo được tệp gói hồ sơ: có tệp biểu mẫu không khớp mã băm đã ký.";

  private final DossierExportService service;

  public PackageExportJobs(DossierExportService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "dossier.package_export";
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
        .map(j -> new TrackedJob(job(j), ActorId.of(j.row().requestedBy()), false));
  }

  static Job job(ExportJob j) {
    String id = j.row().id().toString();
    OffsetDateTime at = time(j.row().updatedAt());
    return switch (j.row().status()) {
      case "HOAN_TAT" ->
          new Job(
              id,
              TYPE,
              JobStatus.HOAN_TAT,
              100,
              "Đã tạo tệp gói hồ sơ gồm " + j.row().fileCount() + " biểu mẫu.",
              j.downloadUrl(),
              null,
              at);
      case "THAT_BAI" ->
          new Job(
              id,
              TYPE,
              JobStatus.THAT_BAI,
              null,
              "HASH_MISMATCH".equals(j.row().errorCode()) ? FAILED_HASH : FAILED,
              null,
              null,
              at);
      case "DANG_CHAY" -> new Job(id, TYPE, JobStatus.DANG_CHAY, null, RUNNING, null, null, at);
      default -> new Job(id, TYPE, JobStatus.CHO, 0, WAITING, null, null, at);
    };
  }

  private static OffsetDateTime time(Instant t) {
    return (t == null ? Instant.now() : t).atOffset(ZoneOffset.UTC);
  }
}
