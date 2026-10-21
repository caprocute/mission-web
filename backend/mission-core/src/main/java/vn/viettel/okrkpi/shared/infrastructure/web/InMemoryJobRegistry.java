package vn.viettel.okrkpi.shared.infrastructure.web;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho lưu trữ trạng thái tác vụ nền ({@link JobSource}) trong {@code mission-core} phục vụ {@code
 * GET /api/v1/jobs/{id}} và {@code DELETE /api/v1/jobs/{id}} ({@code x-mission.owner: SVC-05}).
 */
@Component
public final class InMemoryJobRegistry implements JobSource {

  public static final TenantId DEFAULT_TENANT_ID =
      TenantId.of(UUID.fromString("01928f3e-0000-7000-8000-000000000001"));
  public static final ActorId DEFAULT_ACTOR_ID =
      ActorId.of(UUID.fromString("01928f3e-0000-7000-8000-000000000002"));
  public static final String CONTRACT_EXAMPLE_JOB_ID = "01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77";

  private static final ThreadLocal<ActorId> ACTOR_OVERRIDE = new ThreadLocal<>();

  private final Map<JobKey, TrackedJob> jobs = new ConcurrentHashMap<>();

  public InMemoryJobRegistry() {
    seedDefaultExampleJob();
  }

  public static Optional<ActorId> currentActorOverride() {
    return Optional.ofNullable(ACTOR_OVERRIDE.get());
  }

  public static void setActorOverride(ActorId actorId) {
    if (actorId == null) {
      ACTOR_OVERRIDE.remove();
    } else {
      ACTOR_OVERRIDE.set(actorId);
    }
  }

  public static void clearActorOverride() {
    ACTOR_OVERRIDE.remove();
  }

  public final void seedDefaultExampleJob() {
    register(
        DEFAULT_TENANT_ID,
        new TrackedJob(
            new Job(
                CONTRACT_EXAMPLE_JOB_ID,
                "PERIOD_LOCK",
                JobStatus.DANG_CHAY,
                45,
                "Đang chốt điểm đơn vị 9/20",
                null,
                null,
                OffsetDateTime.parse("2026-12-31T17:05:00+07:00")),
            DEFAULT_ACTOR_ID,
            true));
  }

  public void register(TenantId tenantId, TrackedJob trackedJob) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(trackedJob, "trackedJob must not be null");
    jobs.put(new JobKey(tenantId, trackedJob.job().id()), trackedJob);
  }

  @Override
  public String name() {
    return "mission-core.in-memory-jobs";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    Objects.requireNonNull(tenant, "tenant must not be null");
    Objects.requireNonNull(jobId, "jobId must not be null");
    return Optional.ofNullable(jobs.get(new JobKey(tenant, jobId)));
  }

  @Override
  public boolean requestCancel(TenantId tenant, String jobId) {
    Objects.requireNonNull(tenant, "tenant must not be null");
    Objects.requireNonNull(jobId, "jobId must not be null");
    JobKey key = new JobKey(tenant, jobId);
    TrackedJob current = jobs.get(key);
    if (current == null || !current.cancellable() || current.job().status().isTerminal()) {
      return false;
    }
    Job cancelledJob =
        new Job(
            current.job().id(),
            current.job().type(),
            JobStatus.DA_HUY,
            current.job().progress(),
            current.job().message(),
            current.job().resultLocation(),
            current.job().problem(),
            OffsetDateTime.now());
    jobs.put(key, new TrackedJob(cancelledJob, current.initiator(), current.cancellable()));
    return true;
  }

  private record JobKey(TenantId tenantId, String jobId) {}
}
