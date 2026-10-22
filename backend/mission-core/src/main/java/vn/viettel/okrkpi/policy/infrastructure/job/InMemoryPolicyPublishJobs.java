package vn.viettel.okrkpi.policy.infrastructure.job;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishLauncher;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code POLICY_PUBLISH} giữ trong bộ nhớ pod 24 giờ (CORE-05-09). Saga phát hành đa cell
 * thuộc CORE-05-10; tới khi có saga, tác vụ đứng ở trạng thái chờ và phiên bản giữ {@code
 * CHO_PHE_DUYET} đã ký (suy luận, wiki trang 14).
 */
public class InMemoryPolicyPublishJobs implements PolicyPublishLauncher, JobSource {

  static final String POLICY_PUBLISH = "POLICY_PUBLISH";

  private record Key(UUID tenant, String jobId) {}

  private final Cache<Key, TrackedJob> jobs =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

  @Override
  public String name() {
    return "policy.publish";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    return Optional.ofNullable(jobs.getIfPresent(new Key(tenant.value(), jobId)));
  }

  @Override
  public String launch(
      TenantId tenant, UUID actor, PolicyPackVersion version, PolicyPublishRequest request) {
    String jobId = Ids.newUuidV7().toString();
    jobs.put(
        new Key(tenant.value(), jobId),
        new TrackedJob(
            Job.accepted(jobId, POLICY_PUBLISH, OffsetDateTime.now(ZoneOffset.UTC)),
            ActorId.of(actor),
            false));
    return jobId;
  }
}
