package vn.viettel.okrkpi.scoring.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.ProfileScorer;
import vn.viettel.okrkpi.scoring.application.port.ScoreChunkQueue;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;
import vn.viettel.okrkpi.scoring.domain.JobScope;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJobError;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.Jcs;

/**
 * Tính một lô của tác vụ (DV-07-04, DV-07-06; TL-02a QT-08 bước 2): mỗi hồ sơ trong phạm vi được
 * tính độc lập bằng phiên bản Policy Pack ghim của kỳ theo khối và đơn vị (BR-08-07; kiểm khớp với
 * phiên bản lõi ghi nhận ở đầu mỗi lô), riêng {@code TINH_LAI_HAU_KIEM} dùng phiên bản gốc ghi
 * trong phạm vi (BR-13-06). Gói đã biên dịch được dùng lại cho cả lô nhờ bộ đệm theo băm. Hồ sơ lỗi
 * không làm hỏng lô mà vào hàng đợi lỗi. {@code KIEM_TRA_TAI_LAP} tính lại từ {@code input_doc} đã
 * lưu bằng đúng phiên bản của kết quả gốc. Lớp này không ghi gì; {@link ScoreJobWorker} ghi trong
 * một giao dịch.
 */
public class ScoreJobRunner {

  /** Phiên bản bộ máy tính ghi vào {@code score_result.engine_version}. */
  public static final String ENGINE_VERSION = "SVC-07/1";

  /** Số kết quả gốc tính lại đối chiếu trong một lô. */
  public static final int RECHECK_CHUNK = 500;

  /** Kết quả tính một lô: các lô ghi (mỗi phiên bản gói một lô) và các hồ sơ lỗi. */
  public record Computed(List<ScoreBatch> batches, List<ScoreJobError> errors) {
    public Computed {
      batches = List.copyOf(batches);
      errors = List.copyOf(errors);
    }

    /** Số hồ sơ đã tính được. */
    public int scored() {
      return batches.stream().mapToInt(b -> b.items().size()).sum();
    }
  }

  private final PolicyPackCache policies;
  private final InputAssembler assembler;
  private final ProfileScorer scorer;
  private final StoredScores stored;
  private final Clock clock;

  public ScoreJobRunner(
      PolicyPackCache policies,
      InputAssembler assembler,
      ProfileScorer scorer,
      StoredScores stored,
      Clock clock) {
    this.policies = Objects.requireNonNull(policies, "policies");
    this.assembler = Objects.requireNonNull(assembler, "assembler");
    this.scorer = Objects.requireNonNull(scorer, "scorer");
    this.stored = Objects.requireNonNull(stored, "stored");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Tính các hồ sơ trong phạm vi của một trang đầu vào. */
  public Computed scorePage(ScoreChunkQueue.Job job, List<CoreScoringInput> page) {
    JobScope scope = JobScope.parse(job.scope());
    Groups groups = new Groups();
    List<ScoreJobError> errors = new ArrayList<>();
    for (CoreScoringInput in : page) {
      if (!scope.includes(in)) {
        continue;
      }
      CompiledPolicy policy;
      try {
        policy = policyFor(job, scope, in);
      } catch (RuntimeException e) {
        errors.add(error(in, ScoreJobError.POLICY_MISSING, e));
        continue;
      }
      if (job.kind() != ScoreJobKind.TINH_LAI_HAU_KIEM
          && in.policyPackVersionId() != null
          && !in.policyPackVersionId().equals(policy.pinned().versionId())) {
        errors.add(
            new ScoreJobError(
                in.profileId(),
                in.personId(),
                in.flow(),
                ScoreJobError.POLICY_MISMATCH,
                "Lõi ghi phiên bản "
                    + in.policyPackVersionId()
                    + ", kỳ ghim "
                    + policy.pinned().versionId()));
        continue;
      }
      ScoringInput input;
      try {
        input = assembler.assemble(policy, in).input();
      } catch (RuntimeException e) {
        errors.add(error(in, ScoreJobError.INPUT_INVALID, e));
        continue;
      }
      try {
        groups.add(policy, scorer.score(Ids.newUuidV7(), policy, input));
      } catch (RuntimeException e) {
        errors.add(error(in, ScoreJobError.SCORING_FAILED, e));
      }
    }
    return new Computed(groups.batches(job), errors);
  }

  /**
   * Tính lại đối chiếu một đoạn danh sách kết quả của tác vụ {@code KIEM_TRA_TAI_LAP}, từ vị trí
   * {@code offset} (DL-12 R4, {@code recomputeAppraisalProfile}).
   */
  public Computed recheck(ScoreChunkQueue.Job job, int offset) {
    JobScope scope = JobScope.parse(job.scope());
    List<UUID> slice =
        scope
            .results()
            .subList(
                Math.min(offset, scope.results().size()),
                Math.min(offset + RECHECK_CHUNK, scope.results().size()));
    Groups groups = new Groups();
    List<ScoreJobError> errors = new ArrayList<>();
    UUID tenant = job.ref().tenantId();
    for (UUID resultId : slice) {
      var original = stored.byId(job.ref().periodBucket(), resultId);
      if (original.isEmpty()) {
        errors.add(
            new ScoreJobError(
                null, null, null, ScoreJobError.SOURCE_MISSING, "Kết quả gốc " + resultId));
        continue;
      }
      StoredScores.Stored s = original.get();
      try {
        CompiledPolicy policy =
            policies.original(
                PolicyBindingKey.of(tenant, s.periodId(), s.flow()),
                s.policyPackVersionId(),
                s.policyContentHash());
        Map<String, Object> vars = ProfileScorer.variablesOf(policy, document(s.inputDoc()));
        groups.add(
            policy,
            scorer.rescore(
                Ids.newUuidV7(), policy, s.profileId(), s.personId(), s.flow(), vars, Map.of()));
      } catch (RuntimeException e) {
        errors.add(
            new ScoreJobError(
                s.profileId(), s.personId(), s.flow(), ScoreJobError.SCORING_FAILED, summary(e)));
      }
    }
    return new Computed(groups.batches(job), errors);
  }

  private CompiledPolicy policyFor(ScoreChunkQueue.Job job, JobScope scope, CoreScoringInput in) {
    UUID tenant = job.ref().tenantId();
    if (job.kind() == ScoreJobKind.TINH_LAI_HAU_KIEM) {
      String hash = scope.originalHash(in.flow());
      if (hash == null) {
        throw new IllegalStateException("Kỳ chưa có kết quả gốc của luồng " + in.flow());
      }
      return policies.original(
          PolicyBindingKey.of(tenant, job.periodId(), in.flow()),
          scope.originalVersion(in.flow()),
          hash);
    }
    return policies.forBinding(in.bindingKey(tenant, job.periodId()));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> document(String json) {
    if (!(Jcs.parse(json) instanceof Map<?, ?> doc)) {
      throw new IllegalStateException("input_doc không phải đối tượng JSON");
    }
    return (Map<String, Object>) doc;
  }

  private static ScoreJobError error(CoreScoringInput in, String reason, RuntimeException e) {
    return new ScoreJobError(in.profileId(), in.personId(), in.flow(), reason, summary(e));
  }

  /** Lớp và thông điệp lỗi kỹ thuật; thông điệp của bộ máy tính không chứa dữ liệu C2. */
  static String summary(Throwable e) {
    String message = e.getMessage();
    return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  /** Gom kết quả theo băm gói để mỗi lô ghi một phiên bản ({@link ScoreBatch}). */
  private final class Groups {
    private final Map<String, CompiledPolicy> policyByHash = new LinkedHashMap<>();
    private final Map<String, List<ComputedScore>> scores = new LinkedHashMap<>();

    void add(CompiledPolicy policy, ComputedScore score) {
      policyByHash.putIfAbsent(policy.contentHash(), policy);
      scores.computeIfAbsent(policy.contentHash(), k -> new ArrayList<>()).add(score);
    }

    List<ScoreBatch> batches(ScoreChunkQueue.Job job) {
      Instant now = clock.instant();
      List<ScoreBatch> out = new ArrayList<>();
      scores.forEach(
          (hash, items) -> {
            CompiledPolicy p = policyByHash.get(hash);
            for (int i = 0; i < items.size(); i += ScoreBatch.MAX_SIZE) {
              out.add(
                  new ScoreBatch(
                      job.ref().tenantId(),
                      job.ref().id(),
                      job.kind(),
                      job.periodId(),
                      job.ref().periodBucket(),
                      p.pinned().versionId(),
                      p.pinned().contentHashBytes(),
                      ENGINE_VERSION,
                      job.actorId(),
                      now,
                      items.subList(i, Math.min(i + ScoreBatch.MAX_SIZE, items.size()))));
            }
          });
      return out;
    }
  }
}
