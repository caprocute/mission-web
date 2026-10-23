package vn.viettel.okrkpi.workflow.infrastructure.saga;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ProblemDetail;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;
import vn.viettel.okrkpi.shared.error.ErrorCode;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobIds;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStep;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Trạng thái saga dưới dạng tác vụ dài {@code /api/v1/jobs/{id}} (baseline mục 13): mã tác vụ là mã
 * saga, chỉ người khởi động thấy. Saga đang chờ sự kiện, đang bù trừ hay chờ xử lý vẫn là {@code
 * DANG_CHAY}; bù trừ xong là {@code THAT_BAI}.
 */
public class SagaJobSource implements JobSource {

  /** Lỗi của bước bị từ chối theo nghiệp vụ: "MÃ-LỖI: mô tả" ({@code StepResult.Rejected}). */
  private static final Pattern REJECTION = Pattern.compile("^([A-Z]+-\\d{3}-[A-Z0-9-]+): (.+)$");

  private static final ErrorCatalog ERRORS = ErrorCatalog.standard();

  private final SagaService sagas;

  public SagaJobSource(SagaService sagas) {
    this.sagas = sagas;
  }

  /**
   * Tiền tố mã tác vụ của SVC-06 (saga và lô duyệt): cổng cell định tuyến {@code /api/v1/jobs/wf-…}
   * về đây (wiki 14 mục DV-01-04).
   */
  public static final String JOB_PREFIX = "wf";

  @Override
  public String name() {
    return "saga";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    Optional<UUID> parsed = JobIds.parse(JOB_PREFIX, jobId);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    UUID id = parsed.get();
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
        JobIds.format(JOB_PREFIX, saga.id()),
        type(saga.type()),
        status,
        status == JobStatus.THAT_BAI ? null : progress,
        message(saga),
        null,
        status == JobStatus.THAT_BAI ? problem(saga) : null,
        at.atOffset(ZoneOffset.UTC));
  }

  /**
   * Problem của saga đã bù trừ xong: mã của bước bị từ chối theo nghiệp vụ (ví dụ {@code
   * PERIOD-422-PRECONDITION} của bước tiền điều kiện chốt kỳ, MSG-08-02); bù trừ vì lỗi kỹ thuật
   * không có Problem.
   */
  static ProblemDetail problem(SagaInstance saga) {
    for (SagaStep step : saga.steps()) {
      if (step.status() != SagaStepStatus.LOI || step.lastError() == null) {
        continue;
      }
      Matcher m = REJECTION.matcher(step.lastError());
      if (!m.matches()) {
        continue;
      }
      Optional<ErrorCode> code = ERRORS.find(m.group(1));
      if (code.isEmpty()) {
        continue;
      }
      ProblemDetail pd = ProblemDetail.forStatus(code.get().status());
      pd.setType(URI.create(code.get().type()));
      pd.setTitle(code.get().title());
      pd.setDetail(m.group(2));
      pd.setProperty("code", code.get().code());
      if (code.get().msg() != null) {
        pd.setProperty("msg", code.get().msg());
      }
      return pd;
    }
    return null;
  }

  static String type(SagaType type) {
    return switch (type) {
      case CHOT_KY -> "PERIOD_LOCK";
      case MO_LAI_HAU_KIEM -> "PERIOD_REOPEN";
      case TOT_NGHIEP_TENANT -> "TENANT_GRADUATION";
      case DONG_GOI_HO_SO -> "DOSSIER_PACKAGE";
      case PHAT_HANH_MAU_LUONG -> "WF_TEMPLATE_RELEASE";
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
