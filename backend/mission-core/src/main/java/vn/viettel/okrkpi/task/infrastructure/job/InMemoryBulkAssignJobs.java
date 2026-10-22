package vn.viettel.okrkpi.task.infrastructure.job;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.BulkAssignJobs;

/**
 * Tác vụ {@code BULK_ASSIGN} (giao đồng loạt, CORE-02-14) giữ trong bộ nhớ của pod 24 giờ và trả
 * qua {@code GET /api/v1/jobs/{id}}. Kết quả bền vững là các nhiệm vụ đã giao; mất tác vụ khi pod
 * khởi động lại chỉ mất bản tổng hợp, danh sách việc của đơn vị vẫn đủ (suy luận).
 */
public class InMemoryBulkAssignJobs implements BulkAssignJobs, JobSource {

  static final String TYPE = "BULK_ASSIGN";

  private record Key(UUID tenant, String jobId) {}

  private final Cache<Key, TrackedJob> jobs =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

  @Override
  public String name() {
    return "task.bulk-assign";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    return Optional.ofNullable(jobs.getIfPresent(new Key(tenant.value(), jobId)));
  }

  @Override
  public void accepted(TenantId tenant, String jobId, UUID initiator, int total) {
    jobs.put(
        new Key(tenant.value(), jobId),
        new TrackedJob(Job.accepted(jobId, TYPE, now()), ActorId.of(initiator), false));
  }

  @Override
  public void progress(TenantId tenant, String jobId, int done, int total) {
    replace(
        tenant,
        jobId,
        type ->
            new Job(
                jobId,
                type,
                JobStatus.DANG_CHAY,
                total == 0 ? 0 : Math.min(99, done * 100 / total),
                "Đã xử lý " + done + "/" + total + " đơn vị",
                null,
                null,
                now()));
  }

  @Override
  public void finished(TenantId tenant, String jobId, int assigned, List<Failure> failures) {
    String message =
        "Đã giao "
            + assigned
            + " nhiệm vụ"
            + (failures.isEmpty() ? "" : ", " + failures.size() + " đơn vị chưa giao được");
    if (assigned == 0 && !failures.isEmpty()) {
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, message);
      problem.setType(URI.create("https://okrkpi.vn/problems/validation"));
      problem.setTitle("Giao đồng loạt chưa thành công");
      problem.setProperty("code", "COMMON-422-VALIDATION");
      problem.setProperty("errors", errors(failures));
      replace(
          tenant,
          jobId,
          type -> new Job(jobId, type, JobStatus.THAT_BAI, 100, message, null, problem, now()));
      return;
    }
    replace(
        tenant,
        jobId,
        type ->
            new Job(jobId, type, JobStatus.HOAN_TAT, 100, message, "/api/v1/tasks", null, now()));
  }

  private static List<Map<String, String>> errors(List<Failure> failures) {
    return failures.stream()
        .map(f -> Map.of("field", "unitIds", "message", f.message() == null ? "" : f.message()))
        .toList();
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
