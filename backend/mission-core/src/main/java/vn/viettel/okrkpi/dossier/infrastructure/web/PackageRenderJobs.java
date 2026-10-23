package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService.PackageView;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.RenderProgress;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code RENDER} của {@code regenerateDossierPackageForms}: mã tác vụ là mã gói, trạng thái
 * đọc từ tiến độ biểu mẫu của gói nên đúng trên mọi pod. Đang sinh là {@code DANG_CHAY}; đủ tệp là
 * {@code HOAN_TAT} với {@code resultLocation} là gói; hết tệp chờ mà còn tệp lỗi là {@code
 * THAT_BAI} (MSG-11-07).
 */
public class PackageRenderJobs implements JobSource {

  static final String TYPE = "RENDER";

  private final DossierPackageService service;

  public PackageRenderJobs(DossierPackageService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "dossier.dossier_package.render";
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
        .view(tenant, id)
        .filter(v -> v.progress().total() > 0)
        .map(v -> new TrackedJob(job(v), ActorId.of(v.row().updatedBy()), false));
  }

  static Job job(PackageView v) {
    RenderProgress p = v.progress();
    String id = v.row().id().toString();
    int done = p.rendered() + p.failed();
    int percent = p.total() == 0 ? 0 : done * 100 / p.total();
    PackageStatus status = v.row().status();
    if (status == PackageStatus.DANG_SINH || p.pending() > 0 && status == PackageStatus.DANG_LAP) {
      return new Job(
          id,
          TYPE,
          JobStatus.DANG_CHAY,
          percent,
          "Đã sinh " + p.rendered() + "/" + p.total() + " biểu mẫu.",
          null,
          null,
          now());
    }
    if (p.failed() > 0) {
      return new Job(
          id,
          TYPE,
          JobStatus.THAT_BAI,
          null,
          "Có " + p.failed() + "/" + p.total() + " biểu mẫu không sinh được.",
          null,
          null,
          now());
    }
    return new Job(
        id,
        TYPE,
        JobStatus.HOAN_TAT,
        100,
        "Đã sinh xong " + p.rendered() + " biểu mẫu.",
        "/api/v1/dossiers/packages/" + id,
        null,
        now());
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
