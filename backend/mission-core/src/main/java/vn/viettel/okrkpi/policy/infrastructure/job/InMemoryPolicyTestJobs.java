package vn.viettel.okrkpi.policy.infrastructure.job;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import vn.viettel.okrkpi.policy.application.port.PolicyTestJobs;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code TEST_RUN} của Policy Pack, giữ trong bộ nhớ pod 24 giờ và trả qua {@code GET
 * /api/v1/jobs/{id}}. Kết quả bền vững là {@code policy.test_run} và trạng thái phiên bản; mất tác
 * vụ khi pod khởi động lại chỉ mất tiến độ, lịch sử chạy vẫn đọc được (suy luận).
 */
public class InMemoryPolicyTestJobs implements PolicyTestJobs, JobSource {

  private record Key(UUID tenant, String jobId) {}

  private final Cache<Key, TrackedJob> jobs =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

  @Override
  public String name() {
    return "policy.test-run";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    return Optional.ofNullable(jobs.getIfPresent(new Key(tenant.value(), jobId)));
  }

  @Override
  public void accepted(TenantId tenant, String jobId, UUID initiator) {
    jobs.put(
        new Key(tenant.value(), jobId),
        new TrackedJob(Job.accepted(jobId, TEST_RUN, now()), ActorId.of(initiator), false));
  }

  @Override
  public void running(TenantId tenant, String jobId, int progress) {
    replace(
        tenant,
        jobId,
        type ->
            new Job(
                jobId,
                type,
                JobStatus.DANG_CHAY,
                progress,
                "Đang chạy bộ ca kiểm thử",
                null,
                null,
                now()));
  }

  @Override
  public void finished(TenantId tenant, String jobId, String location, String message) {
    replace(
        tenant,
        jobId,
        type -> new Job(jobId, type, JobStatus.HOAN_TAT, 100, message, location, null, now()));
  }

  @Override
  public void failed(TenantId tenant, String jobId, String message) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, message);
    problem.setType(URI.create("https://okrkpi.vn/problems/test-run"));
    problem.setTitle("Chưa chạy được kiểm thử");
    replace(
        tenant,
        jobId,
        type -> new Job(jobId, type, JobStatus.THAT_BAI, 100, message, null, problem, now()));
  }

  private void replace(TenantId tenant, String jobId, Function<String, Job> job) {
    jobs.asMap()
        .computeIfPresent(
            new Key(tenant.value(), jobId),
            (k, old) -> new TrackedJob(job.apply(old.job().type()), old.initiator(), false));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
