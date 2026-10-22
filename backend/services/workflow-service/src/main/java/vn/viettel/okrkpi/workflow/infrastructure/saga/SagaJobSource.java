package vn.viettel.okrkpi.workflow.infrastructure.saga;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Trạng thái saga dưới dạng tác vụ dài {@code /api/v1/jobs/{id}} (baseline mục 13): mã tác vụ là mã
 * saga, chỉ người khởi động thấy. Saga đang chờ sự kiện, đang bù trừ hay chờ xử lý vẫn là {@code
 * DANG_CHAY}; bù trừ xong là {@code THAT_BAI}.
 */
public class SagaJobSource implements JobSource {

  private final SagaService sagas;

  public SagaJobSource(SagaService sagas) {
    this.sagas = sagas;
  }

  @Override
  public String name() {
    return "saga";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return sagas
        .find(id)
        .filter(s -> s.tenantId().equals(tenant.value()))
        .map(s -> new TrackedJob(job(s), ActorId.of(s.initiator()), false));
  }

  public static Job job(SagaInstance saga) {
    JobStatus status =
        switch (saga.status()) {
          case HOAN_TAT -> JobStatus.HOAN_TAT;
          case DA_BU_TRU -> JobStatus.THAT_BAI;
          default -> JobStatus.DANG_CHAY;
        };
    int total = saga.steps().size();
    int progress =
        status == JobStatus.HOAN_TAT || total == 0 ? 100 : saga.completedSteps() * 100 / total;
    Instant at = saga.finishedAt() != null ? saga.finishedAt() : saga.startedAt();
    return new Job(
        saga.id().toString(),
        type(saga.type()),
        status,
        status == JobStatus.THAT_BAI ? null : progress,
        message(saga),
        null,
        null,
        at.atOffset(ZoneOffset.UTC));
  }

  static String type(SagaType type) {
    return switch (type) {
      case CHOT_KY -> "PERIOD_LOCK";
      case MO_LAI_HAU_KIEM -> "PERIOD_REOPEN";
      case TOT_NGHIEP_TENANT -> "TENANT_GRADUATION";
      case DONG_GOI_HO_SO -> "DOSSIER_PACKAGE";
    };
  }

  private static String message(SagaInstance saga) {
    return switch (saga.status()) {
      case CHO_SU_KIEN -> "Đang chờ các hệ thống liên quan xác nhận.";
      case DANG_BU_TRU -> "Đang hoàn tác các bước đã thực hiện.";
      case LOI_CAN_XU_LY -> "Quy trình tạm dừng, chờ cán bộ vận hành xử lý.";
      case DA_BU_TRU -> "Quy trình không hoàn thành và đã được hoàn tác.";
      case HOAN_TAT -> "Đã hoàn thành.";
      case DANG_CHAY -> null;
    };
  }
}
