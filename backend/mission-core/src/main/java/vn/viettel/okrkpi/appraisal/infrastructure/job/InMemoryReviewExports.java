package vn.viettel.okrkpi.appraisal.infrastructure.job;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewExports;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code APPRAISAL_EXPORT} và tệp xuất giữ trong bộ nhớ của pod như xuất danh sách nhiệm vụ
 * (CORE-02-18): tác vụ 24 giờ, tệp một giờ, tổng dung lượng tệp tối đa 256 MB. Khi chạy nhiều pod,
 * tệp chuyển sang kho đối tượng của cell (suy luận, wiki trang 14).
 */
public class InMemoryReviewExports implements ReviewExports, JobSource {

  static final String TYPE = "APPRAISAL_EXPORT";

  private record Key(UUID tenant, String jobId) {}

  private record StoredFile(UUID owner, byte[] content) {}

  private final Cache<Key, TrackedJob> jobs =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

  private final Cache<Key, StoredFile> files =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofHours(1))
          .maximumWeight(256L * 1024 * 1024)
          .<Key, StoredFile>weigher((k, v) -> v.content().length)
          .build();

  @Override
  public String name() {
    return "appraisal.export";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    return Optional.ofNullable(jobs.getIfPresent(new Key(tenant.value(), jobId)));
  }

  @Override
  public void accepted(TenantId tenant, String jobId, UUID initiator) {
    jobs.put(
        new Key(tenant.value(), jobId),
        new TrackedJob(Job.accepted(jobId, TYPE, now()), ActorId.of(initiator), false));
  }

  @Override
  public int running(TenantId tenant, UUID initiator) {
    ActorId actor = ActorId.of(initiator);
    return (int)
        jobs.asMap().entrySet().stream()
            .filter(e -> e.getKey().tenant().equals(tenant.value()))
            .filter(e -> actor.equals(e.getValue().initiator()))
            .filter(e -> !e.getValue().job().status().isTerminal())
            .count();
  }

  @Override
  public void finished(TenantId tenant, String jobId, int rows, String location) {
    replace(
        tenant,
        jobId,
        new Job(
            jobId,
            TYPE,
            JobStatus.HOAN_TAT,
            100,
            "Đã xuất " + rows + " hồ sơ",
            location,
            null,
            now()));
  }

  @Override
  public void failed(TenantId tenant, String jobId, String message) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, message);
    problem.setType(URI.create("https://okrkpi.vn/problems/internal"));
    problem.setTitle("Xuất danh sách chưa thành công");
    replace(
        tenant,
        jobId,
        new Job(jobId, TYPE, JobStatus.THAT_BAI, 100, message, null, problem, now()));
  }

  @Override
  public void put(TenantId tenant, String jobId, UUID owner, byte[] content) {
    files.put(new Key(tenant.value(), jobId), new StoredFile(owner, content));
  }

  @Override
  public Optional<byte[]> file(TenantId tenant, String jobId, UUID owner) {
    return Optional.ofNullable(files.getIfPresent(new Key(tenant.value(), jobId)))
        .filter(f -> f.owner().equals(owner))
        .map(StoredFile::content);
  }

  private void replace(TenantId tenant, String jobId, Job job) {
    jobs.asMap()
        .computeIfPresent(
            new Key(tenant.value(), jobId),
            (k, old) -> new TrackedJob(job, old.initiator(), false));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
