package vn.viettel.okrkpi.task.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import vn.viettel.okrkpi.shared.idempotency.IdempotencyCallerResolver;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.testdb.TenantDatabase;

/**
 * Ứng dụng thử của NEN-013 đặt trong gói module {@code task} (schema {@code task}, mã lỗi {@code
 * TASK-...}), kết nối CSDL bằng {@code svc_task}.
 */
@SpringBootApplication
public class ApiConventionsTestApplication {

  /** Header chỉ có trong kiểm thử, thay cho ngữ cảnh tenant và JWT của NEN-010. */
  public static final String TENANT_HEADER = "X-Test-Tenant";

  public static final String ACTOR_HEADER = "X-Test-Actor";

  @Bean
  DataSource dataSource() {
    return TenantDatabase.dataSource("svc_task");
  }

  @Bean
  IdempotencyCallerResolver testCallers() {
    return request -> {
      String t = request.getHeader(TENANT_HEADER);
      String a = request.getHeader(ACTOR_HEADER);
      if (t == null || a == null) {
        return Optional.empty();
      }
      return Optional.of(new IdempotencyCallerResolver.Caller(TenantId.parse(t), ActorId.parse(a)));
    };
  }

  @Bean
  MemoryJobs memoryJobs() {
    return new MemoryJobs();
  }

  /** Nguồn tác vụ trong bộ nhớ, giả lập một module có tác vụ xuất báo cáo huỷ được. */
  public static class MemoryJobs implements JobSource {

    private final Map<String, TrackedJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, TenantId> owners = new ConcurrentHashMap<>();

    public void put(TenantId tenant, TrackedJob job) {
      jobs.put(job.job().id(), job);
      owners.put(job.job().id(), tenant);
    }

    @Override
    public String name() {
      return "task.export";
    }

    @Override
    public Optional<TrackedJob> find(TenantId tenant, String jobId) {
      return Optional.ofNullable(jobs.get(jobId)).filter(j -> tenant.equals(owners.get(jobId)));
    }

    @Override
    public boolean requestCancel(TenantId tenant, String jobId) {
      TrackedJob current = jobs.get(jobId);
      if (current == null || current.job().status().isTerminal()) {
        return false;
      }
      Job j = current.job();
      jobs.put(
          jobId,
          new TrackedJob(
              new Job(
                  j.id(),
                  j.type(),
                  JobStatus.DA_HUY,
                  j.progress(),
                  "Đã huỷ theo yêu cầu",
                  null,
                  null,
                  OffsetDateTime.now(ZoneOffset.UTC)),
              current.initiator(),
              current.cancellable()));
      return true;
    }
  }
}
