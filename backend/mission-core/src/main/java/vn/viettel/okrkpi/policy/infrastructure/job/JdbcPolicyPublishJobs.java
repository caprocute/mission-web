package vn.viettel.okrkpi.policy.infrastructure.job;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishSagaRepository;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code POLICY_PUBLISH} đọc từ {@code policy.publish_saga} (CORE-05-10), nên tiến độ không
 * mất khi pod khởi động lại. Xong thì {@code resultLocation} trỏ phiên bản đã phát hành; bù trừ thì
 * tác vụ thất bại kèm lý do.
 */
public class JdbcPolicyPublishJobs implements JobSource {

  static final String POLICY_PUBLISH = "POLICY_PUBLISH";

  private final PolicyPublishSagaRepository sagas;
  private final PolicyPackRepository packs;
  private final TransactionOperations reads;

  public JdbcPolicyPublishJobs(
      PolicyPublishSagaRepository sagas, PolicyPackRepository packs, TransactionOperations reads) {
    this.sagas = Objects.requireNonNull(sagas, "sagas");
    this.packs = Objects.requireNonNull(packs, "packs");
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public String name() {
    return "policy.publish";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return reads.execute(
        tx ->
            sagas
                .find(tenant, id)
                .map(s -> new TrackedJob(job(tenant, s), ActorId.of(s.startedBy()), false)));
  }

  private Job job(TenantId tenant, PolicyPublishSaga s) {
    OffsetDateTime at = at(s.updatedAt());
    String id = s.id().toString();
    int progress = (s.step().no() - 1) * 20;
    return switch (s.status()) {
      case HOAN_TAT ->
          new Job(
              id,
              POLICY_PUBLISH,
              JobStatus.HOAN_TAT,
              100,
              "Đã phát hành phiên bản",
              packs
                  .findVersionById(tenant, s.versionId())
                  .map(v -> "/api/v1/policies/packs/" + v.packId() + "/versions/" + v.version())
                  .orElse(null),
              null,
              at);
      case DA_BU_TRU, LOI_CAN_XU_LY -> {
        ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, s.lastError());
        problem.setType(URI.create("https://okrkpi.vn/problems/policy-publish"));
        problem.setTitle(
            s.status() == PolicyPublishSaga.Status.DA_BU_TRU
                ? "Chưa phát hành được, đã hoàn tác"
                : "Chưa phát hành được, cần quản trị viên xử lý");
        yield new Job(
            id, POLICY_PUBLISH, JobStatus.THAT_BAI, progress, s.lastError(), null, problem, at);
      }
      default ->
          new Job(
              id,
              POLICY_PUBLISH,
              JobStatus.DANG_CHAY,
              progress,
              "Đang phát hành phiên bản",
              null,
              null,
              at);
    };
  }

  private static OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
