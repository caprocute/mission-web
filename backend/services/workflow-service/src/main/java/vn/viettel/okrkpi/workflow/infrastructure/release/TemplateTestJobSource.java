package vn.viettel.okrkpi.workflow.infrastructure.release;

import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobIds;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.workflow.application.template.TemplateTestService;
import vn.viettel.okrkpi.workflow.domain.template.TestRun;
import vn.viettel.okrkpi.workflow.infrastructure.saga.SagaJobSource;

/**
 * Lần chạy bộ test của mẫu luồng dưới dạng tác vụ dài {@code /api/v1/jobs/{id}} (baseline mục 13):
 * mã tác vụ là mã lần chạy mang tiền tố {@code wf-} của SVC-06, chỉ người yêu cầu thấy.
 */
public class TemplateTestJobSource implements JobSource {

  private final TemplateTestService tests;

  public TemplateTestJobSource(TemplateTestService tests) {
    this.tests = tests;
  }

  @Override
  public String name() {
    return "wf-template-test";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    Optional<UUID> id = JobIds.parse(SagaJobSource.JOB_PREFIX, jobId);
    if (id.isEmpty()) {
      return Optional.empty();
    }
    return tests
        .find(id.get())
        .filter(r -> r.tenantId().equals(tenant.value()))
        .map(r -> new TrackedJob(job(r), ActorId.of(r.requestedBy()), false));
  }

  public static Job job(TestRun run) {
    JobStatus status =
        switch (run.status()) {
          case CHO -> JobStatus.CHO;
          case DANG_CHAY -> JobStatus.DANG_CHAY;
          case HOAN_TAT -> JobStatus.HOAN_TAT;
          case THAT_BAI -> JobStatus.THAT_BAI;
        };
    return new Job(
        JobIds.format(SagaJobSource.JOB_PREFIX, run.id()),
        TestRun.JOB_TYPE,
        status,
        run.progress(),
        run.message(),
        null,
        null,
        run.updatedAt() == null ? null : run.updatedAt().atOffset(ZoneOffset.UTC));
  }
}
