package vn.viettel.okrkpi.okr.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.okr.application.OkrReadService.KeyResultView;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Finding;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.KrInput;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.ObjectiveInput;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Policy;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Rule;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.KeyResultRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveRow;
import vn.viettel.okrkpi.okr.application.port.OkrSimilarityPort;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ghép dữ liệu máy chủ cho {@link OkrRuleChecker} (CORE-01-04): căn cứ, mục tiêu cấp trên, số mục
 * tiêu của chủ thể trong kỳ, đơn vị cao nhất, và tham số soát của Policy Pack qua {@link
 * PolicyQueryApi} (khoá {@code okr_recommended_max}, {@code okr_vague_words}, {@code
 * okr_task_verbs}, {@code okr_units}, {@code okr_rules}; suy luận tên khoá). Thiếu tham số thì dùng
 * {@link OkrRuleChecker#DEFAULT_POLICY}.
 */
public class OkrCheckService {

  static final String P_RECOMMENDED_MAX = "okr_recommended_max";
  static final String P_VAGUE_WORDS = "okr_vague_words";
  static final String P_TASK_VERBS = "okr_task_verbs";
  static final String P_UNITS = "okr_units";
  static final String P_RULES = "okr_rules";

  private final OkrRuleChecker checker;
  private final OkrReadRepository reads;
  private final OkrWriteRepository writes;
  private final Supplier<PolicyQueryApi> policies;
  private final Supplier<OrgDirectoryApi> org;
  private final Supplier<OkrSimilarityPort> similarity;

  public OkrCheckService(
      OkrRuleChecker checker,
      OkrReadRepository reads,
      OkrWriteRepository writes,
      Supplier<PolicyQueryApi> policies,
      Supplier<OrgDirectoryApi> org,
      Supplier<OkrSimilarityPort> similarity) {
    this.checker = Objects.requireNonNull(checker, "checker");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.policies = Objects.requireNonNull(policies, "policies");
    this.org = Objects.requireNonNull(org, "org");
    this.similarity = Objects.requireNonNull(similarity, "similarity");
  }

  /** Tham số soát áp cho đơn vị trong kỳ. */
  public Policy policy(TenantId tenant, UUID periodId, UUID unitId) {
    PolicyQueryApi api = policies.get();
    if (api == null || periodId == null) {
      return OkrRuleChecker.DEFAULT_POLICY;
    }
    Optional<PolicyQueryApi.ResolvedPack> pack =
        api.resolve(tenant, PeriodId.of(periodId), "DANG", null, unitId);
    if (pack.isEmpty()) {
      return OkrRuleChecker.DEFAULT_POLICY;
    }
    UUID v = pack.get().versionId();
    Policy d = OkrRuleChecker.DEFAULT_POLICY;
    int max =
        api.parameter(tenant, v, P_RECOMMENDED_MAX)
            .filter(Number.class::isInstance)
            .map(n -> ((Number) n).intValue())
            .filter(n -> n >= 1)
            .orElse(d.recommendedMax());
    List<Rule> rules = api.parameter(tenant, v, P_RULES).map(OkrCheckService::rules).orElse(null);
    return new Policy(
        pack.get().version(),
        max,
        strings(api.parameter(tenant, v, P_VAGUE_WORDS)).orElse(d.vagueWords()),
        strings(api.parameter(tenant, v, P_TASK_VERBS)).orElse(d.taskVerbs()),
        strings(api.parameter(tenant, v, P_UNITS)).orElse(d.units()),
        rules != null && OkrRuleChecker.valid(rules) ? rules : d.rules());
  }

  /**
   * Cờ bật, tắt của Policy Pack áp cho đơn vị trong kỳ; thiếu gói hoặc tham số thì {@code false}.
   */
  public boolean flag(TenantId tenant, UUID periodId, UUID unitId, String key) {
    PolicyQueryApi api = policies.get();
    if (api == null || periodId == null) {
      return false;
    }
    return api.resolve(tenant, PeriodId.of(periodId), "DANG", null, unitId)
        .flatMap(pack -> api.parameter(tenant, pack.versionId(), key))
        .map(Boolean.TRUE::equals)
        .orElse(false);
  }

  /** Kết quả kiểm tra của mục tiêu, tính lại mỗi lần gọi. */
  @Transactional(transactionManager = OkrReadService.TX, readOnly = true)
  public List<Finding> check(TenantId tenant, ObjectiveView view) {
    ObjectiveRow o = view.row();
    Policy policy = policy(tenant, o.periodId(), o.ownerUnitId());
    Optional<ObjectiveRow> parent =
        o.parentObjectiveId() == null
            ? Optional.empty()
            : reads.objective(tenant, o.parentObjectiveId());
    OrgDirectoryApi directory = org.get();
    boolean topLevel =
        directory != null
            && directory.parentOf(tenant, OrgTree.CHUYEN_MON, o.ownerUnitId()).isEmpty();
    long total =
        writes.countOwnerObjectives(
            tenant,
            o.periodId(),
            o.ownerUnitId(),
            "CA_NHAN".equals(o.ownerType()) ? o.ownerPersonId() : null,
            null);
    List<KrInput> krs = new ArrayList<>();
    for (KeyResultView k : view.keyResults()) {
      KeyResultRow r = k.row();
      krs.add(
          new KrInput(
              r.id(),
              r.code(),
              r.title(),
              r.unit(),
              r.baselineValue(),
              r.targetValue(),
              r.weight(),
              r.dueDate(),
              r.verifierPersonId()));
    }
    UUID krOwner = o.ownerPersonId() != null ? o.ownerPersonId() : o.createdBy();
    ObjectiveInput input =
        new ObjectiveInput(
            o.title(),
            view.bases().size(),
            parent.isPresent(),
            topLevel,
            parent.map(p -> "DA_DUYET".equals(p.status())).orElse(false),
            parent.map(ObjectiveRow::code).orElse(""),
            total,
            o.overLimitReason(),
            krOwner,
            krs);
    List<Finding> findings = new ArrayList<>(checker.check(input, policy));
    findings.addAll(similar(tenant, o, krs));
    return findings;
  }

  /** BR-02-06 qua SVC-16 khi có cổng; lỗi thì cảnh báo "chưa kiểm được", không chặn. */
  private List<Finding> similar(TenantId tenant, ObjectiveRow o, List<KrInput> krs) {
    OkrSimilarityPort port = similarity.get();
    if (port == null) {
      return List.of();
    }
    List<Finding> result = new ArrayList<>();
    for (KrInput k : krs) {
      try {
        port.similarTask(tenant, o.periodId(), o.ownerUnitId(), k.title())
            .ifPresent(
                code ->
                    result.add(
                        new Finding(
                            "BR-02-06-TT",
                            "BR-02-06",
                            OkrRuleChecker.CANH_BAO,
                            k.code() + " gần giống nhiệm vụ " + code + ".",
                            k.id(),
                            code)));
      } catch (RuntimeException e) {
        result.add(
            new Finding(
                "BR-02-06-TT",
                "BR-02-06",
                OkrRuleChecker.CANH_BAO,
                "Chưa kiểm được " + k.code() + " có trùng nhiệm vụ đã giao hay không.",
                k.id(),
                null));
      }
    }
    return result;
  }

  private static Optional<List<String>> strings(Optional<Object> value) {
    return value
        .filter(List.class::isInstance)
        .map(v -> ((List<?>) v).stream().map(String::valueOf).toList())
        .filter(l -> !l.isEmpty());
  }

  private static List<Rule> rules(Object value) {
    if (!(value instanceof List<?> list)) {
      return null;
    }
    List<Rule> rules = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)) {
        return null;
      }
      rules.add(
          new Rule(
              str(m.get("id")),
              str(m.get("br")),
              str(m.get("scope")),
              str(m.get("severity")),
              m.get("when") == null ? "true" : str(m.get("when")),
              str(m.get("assert")),
              str(m.get("message")),
              str(m.get("passLabel")),
              Boolean.TRUE.equals(m.get("serverOnly"))));
    }
    return rules;
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
