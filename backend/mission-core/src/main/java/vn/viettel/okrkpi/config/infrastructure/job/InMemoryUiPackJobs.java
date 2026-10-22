package vn.viettel.okrkpi.config.infrastructure.job;

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
import vn.viettel.okrkpi.config.application.port.UiPackJobs;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code TEST_RUN} (gửi duyệt) và {@code RENDER} (phát hành, CORE-08-11) của UI Pack, giữ
 * trong bộ nhớ của pod 24 giờ và trả qua {@code GET /api/v1/jobs/{id}}. Kết quả bền vững là trạng
 * thái phiên bản ({@code CHO_DUYET} hoặc {@code NHAP}); mất tác vụ khi pod khởi động lại chỉ mất
 * danh sách lỗi, gửi duyệt lại là có (suy luận).
 */
public class InMemoryUiPackJobs implements UiPackJobs, JobSource {

  private record Key(UUID tenant, String jobId) {}

  private final Cache<Key, TrackedJob> jobs =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

  @Override
  public String name() {
    return "config.ui-pack-test-run";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    return Optional.ofNullable(jobs.getIfPresent(new Key(tenant.value(), jobId)));
  }

  @Override
  public void accepted(TenantId tenantId, String jobId, UUID initiator, String type) {
    jobs.put(
        new Key(tenantId.value(), jobId),
        new TrackedJob(Job.accepted(jobId, type, now()), ActorId.of(initiator), false));
  }

  @Override
  public void running(TenantId tenantId, String jobId) {
    replace(
        tenantId,
        jobId,
        type ->
            new Job(
                jobId,
                type,
                JobStatus.DANG_CHAY,
                10,
                RENDER.equals(type) ? "Đang biên dịch và phát hành" : "Đang kiểm tra tự động",
                null,
                null,
                now()));
  }

  @Override
  public void passed(TenantId tenantId, String jobId, String location, String message) {
    replace(
        tenantId,
        jobId,
        type -> new Job(jobId, type, JobStatus.HOAN_TAT, 100, message, location, null, now()));
  }

  @Override
  public void failed(
      TenantId tenantId, String jobId, String message, List<ProblemFieldError> errors) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, message);
    problem.setType(URI.create("https://okrkpi.vn/problems/validation"));
    problem.setTitle("Kiểm tra tự động chưa đạt");
    problem.setProperty("code", "COMMON-422-VALIDATION");
    problem.setProperty(
        "errors",
        errors.stream()
            .map(
                e ->
                    e.rule() == null
                        ? Map.of("field", e.field(), "message", e.message())
                        : Map.of("field", e.field(), "message", e.message(), "rule", e.rule()))
            .toList());
    replace(
        tenantId,
        jobId,
        type -> {
          if (RENDER.equals(type)) {
            problem.setTitle("Phát hành chưa thành công");
          }
          return new Job(jobId, type, JobStatus.THAT_BAI, 100, message, null, problem, now());
        });
  }

  private void replace(TenantId tenantId, String jobId, Function<String, Job> job) {
    jobs.asMap()
        .computeIfPresent(
            new Key(tenantId.value(), jobId),
            (k, old) -> new TrackedJob(job.apply(old.job().type()), old.initiator(), false));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
