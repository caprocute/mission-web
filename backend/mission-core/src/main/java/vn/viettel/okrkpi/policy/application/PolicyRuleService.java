package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelIssueDetail;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyRuleRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyRuleRepository.StoredRule;
import vn.viettel.okrkpi.policy.domain.PolicyManifestRules;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyTransitionException;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.RuleParamRules;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Quy tắc CEL và tham số của phiên bản Policy Pack (CORE-05-04, QT-15, SCR-1501): kiểm kiểu bằng
 * {@code vt-cel} với khai báo biến của gói (BR-15-01), kiểm tham số (BR-15-02..04), tổng điểm nhóm
 * (BR-15-05), quyền sửa của gói kế thừa (BR-15-13); phiên bản {@code DAT_KIEM_THU} về {@code NHAP}
 * trong cùng giao dịch (MSG-15-03).
 */
public class PolicyRuleService {

  static final String TX = PolicyPackService.TX;
  static final String OBJECT_TYPE = "policy_rule";
  private static final String DEFAULT_ROUNDING = "{\"digits\":1,\"mode\":\"NUA_LEN\"}";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** Phiên bản kèm gói, dùng khi đọc danh sách quy tắc. */
  public record VersionRules(PolicyPack pack, PolicyPackVersion version, List<StoredRule> rules) {}

  /** Nội dung người soạn gửi lên ({@code PolicyRuleUpdate}); {@code null} giữ giá trị cũ. */
  public record RuleEdit(
      String expression,
      String resultType,
      String roundingJson,
      String legalRef,
      Integer evalOrder,
      Map<String, Object> params,
      Boolean overridable) {}

  /** Quy tắc sau khi lưu ({@code PolicyRuleSaved}). */
  public record SavedRule(
      StoredRule rule, PolicyVersionStatus versionStatus, boolean statusReverted) {}

  private final PolicyPackRepository packs;
  private final PolicyRuleRepository rules;
  private final PolicyAudit audit;
  private final ObjectMapper json;
  private final Clock clock;
  private final CelEngine cel = new CelEngine(StaticWorkingCalendar.weekendsOnly());

  public PolicyRuleService(
      PolicyPackRepository packs,
      PolicyRuleRepository rules,
      PolicyAudit audit,
      ObjectMapper json,
      Clock clock) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** {@code listPolicyRules}; gói hoặc phiên bản không có ném {@link NoSuchElementException}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public VersionRules listRules(TenantId tenant, UUID packId, String version) {
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion v =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    return new VersionRules(pack, v, rules.storedRules(tenant, v.id()));
  }

  /** {@code getPolicyRule}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<StoredRule> findRule(TenantId tenant, UUID packId, String version, String key) {
    return packs
        .findVersion(tenant, packId, version)
        .flatMap(v -> rules.storedRule(tenant, v.id(), key));
  }

  /**
   * {@code savePolicyRule}: quy tắc chưa có thì thêm mới, đã có thì ghi đè khi {@code
   * expectedRowVersion} khớp (409 {@code POLICY-409-VERSION-CONFLICT}).
   */
  @Transactional(TX)
  public SavedRule storeRule(
      TenantId tenant,
      UUID actor,
      UUID packId,
      String version,
      String key,
      long expectedRowVersion,
      RuleEdit edit) {
    Objects.requireNonNull(edit, "edit");
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion current =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    if (current.status() != PolicyVersionStatus.NHAP
        && current.status() != PolicyVersionStatus.DAT_KIEM_THU) {
      throw new BusinessException(
          "POLICY-409-VERSION-FROZEN",
          "Phiên bản "
              + version
              + " đang kiểm thử, chờ phê duyệt hoặc đã phát hành nên không sửa"
              + " được quy tắc");
    }
    List<StoredRule> all = rules.storedRules(tenant, current.id());
    Optional<StoredRule> existing =
        all.stream().filter(r -> r.rule().ruleKey().equals(key)).findFirst();
    boolean inherited = current.inheritsFromParent();
    if (inherited) {
      packs
          .findRule(tenant, current.sourceVersionId(), key)
          .filter(parent -> !parent.overridable())
          .ifPresent(
              parent -> {
                throw new BusinessException(
                    "POLICY-403-NOT-OVERRIDABLE",
                    "Quy tắc này thuộc gói gốc và không cho phép đơn vị điều chỉnh",
                    List.of(
                        new ProblemFieldError(
                            "ruleKey", "Quy tắc gói gốc không cho phép điều chỉnh", "BR-15-13")));
              });
    }
    if (existing.isPresent() && existing.get().rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("POLICY", expectedRowVersion, existing.get().rowVersion());
    }

    PolicyRule rule = merged(current.id(), key, existing.map(StoredRule::rule), edit, inherited);
    Map<String, Object> manifest = read(current.manifestJson());
    checkExpression(rule, manifest, all);
    Map<String, Object> params = read(rule.paramsJson());
    checkParams(params, manifest);
    checkGroupSum(pack.flow(), rule, params, manifest, all);

    boolean reverted = false;
    PolicyPackVersion after = current;
    if (current.status() == PolicyVersionStatus.DAT_KIEM_THU) {
      after = revertToDraft(tenant, actor, pack, current);
      reverted = true;
    }
    StoredRule stored;
    if (existing.isPresent()) {
      if (!rules.replaceRule(tenant, rule, expectedRowVersion, actor)) {
        throw new VersionConflictException("POLICY", expectedRowVersion, null);
      }
      stored = new StoredRule(rule, expectedRowVersion + 1);
    } else {
      rules.appendRule(tenant, rule, actor);
      stored = new StoredRule(rule, 0);
    }
    audit.changed(
        tenant,
        actor,
        "LUU_QUY_TAC_POLICY_PACK",
        OBJECT_TYPE,
        pack.code() + "@" + current.version() + "/" + key,
        rule.id(),
        stored.rowVersion());
    return new SavedRule(stored, after.status(), reverted);
  }

  private PolicyRule merged(
      UUID versionId, String key, Optional<PolicyRule> old, RuleEdit edit, boolean inherited) {
    String expression = edit.expression() == null ? null : edit.expression().strip();
    if (expression == null || expression.isEmpty()) {
      throw invalid("expression", "Cần nhập biểu thức", null);
    }
    if (edit.resultType() == null
        || !List.of("DECIMAL", "BOOLEAN", "STRING").contains(edit.resultType())) {
      throw invalid("resultType", "Kiểu kết quả chưa hợp lệ", null);
    }
    String legalRef =
        edit.legalRef() != null ? edit.legalRef() : old.map(PolicyRule::legalRef).orElse("");
    if (legalRef.length() > 200) {
      throw invalid("legalRef", "Căn cứ pháp lý quá dài", null);
    }
    String rounding =
        edit.roundingJson() != null
            ? edit.roundingJson()
            : old.map(PolicyRule::roundingJson).orElse(DEFAULT_ROUNDING);
    int evalOrder =
        edit.evalOrder() != null ? edit.evalOrder() : old.map(PolicyRule::evalOrder).orElse(0);
    String params;
    try {
      params =
          edit.params() != null
              ? json.writeValueAsString(edit.params())
              : old.map(PolicyRule::paramsJson).orElse("{}");
    } catch (JsonProcessingException e) {
      throw invalid("params", "Tham số chưa hợp lệ", null);
    }
    boolean overridable =
        inherited || edit.overridable() == null
            ? old.map(PolicyRule::overridable).orElse(false)
            : edit.overridable();
    return new PolicyRule(
        old.map(PolicyRule::id).orElseGet(Ids::newUuidV7),
        versionId,
        key,
        expression,
        edit.resultType(),
        rounding,
        legalRef,
        evalOrder,
        params,
        overridable);
  }

  /** BR-15-01: biên dịch với môi trường khai báo của gói, sai trả MSG-15-01. */
  private void checkExpression(
      PolicyRule rule, Map<String, Object> manifest, List<StoredRule> all) {
    Map<String, CelValueType> env = environment(manifest);
    for (StoredRule other : all) {
      env.put(other.rule().ruleKey(), resultType(other.rule().resultType()));
    }
    env.remove(rule.ruleKey());
    try {
      cel.compile(rule.expression(), env, resultType(rule.resultType()));
    } catch (CelCompilationException e) {
      List<ProblemFieldError> errors = new ArrayList<>();
      String first = null;
      for (CelIssueDetail issue : e.issues()) {
        String message = celMessage(rule.ruleKey(), issue);
        first = first == null ? message : first;
        errors.add(new ProblemFieldError("expression", message, "BR-15-01"));
      }
      if (first == null) {
        first = celMessage(rule.ruleKey(), new CelIssueDetail(1, 0, "VALIDATION_ERROR", ""));
        errors.add(new ProblemFieldError("expression", first, "BR-15-01"));
      }
      throw new BusinessException(CelCompilationException.ERROR_CODE, first, errors, "MSG-15-01");
    }
  }

  private void checkParams(Map<String, Object> params, Map<String, Object> manifest) {
    @SuppressWarnings("unchecked")
    Map<String, Object> declared =
        manifest.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    List<PolicyManifestRules.Problem> problems = RuleParamRules.check(params, declared);
    if (!problems.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Tham số của quy tắc chưa hợp lệ",
          problems.stream().map(p -> new ProblemFieldError(p.field(), p.message(), null)).toList());
    }
  }

  /** BR-15-05: tổng các nhóm (cộng tiêu chí chung luồng Nhà nước) phải bằng 100, MSG-15-02. */
  private void checkGroupSum(
      String flow,
      PolicyRule rule,
      Map<String, Object> params,
      Map<String, Object> manifest,
      List<StoredRule> all) {
    List<Map<String, Object>> paramsOfRules = new ArrayList<>();
    for (StoredRule other : all) {
      if (!other.rule().ruleKey().equals(rule.ruleKey())) {
        paramsOfRules.add(read(other.rule().paramsJson()));
      }
    }
    paramsOfRules.add(params);
    OptionalInt groups = RuleParamRules.groupTotal(paramsOfRules);
    if (groups.isEmpty()) {
      return;
    }
    Integer general = generalCriteria(paramsOfRules, manifest);
    OptionalInt total = RuleParamRules.totalFor(flow, groups.getAsInt(), general);
    if (total.isPresent() && total.getAsInt() != RuleParamRules.TONG_DIEM) {
      String message = RuleParamRules.groupSumMessage(total.getAsInt());
      throw new BusinessException(
          "POLICY-422-GROUP-SUM",
          message,
          List.of(new ProblemFieldError("params.diem_toi_da", message, "BR-15-05")),
          "MSG-15-02");
    }
  }

  private static Integer generalCriteria(
      List<Map<String, Object>> paramsOfRules, Map<String, Object> manifest) {
    for (Map<String, Object> p : paramsOfRules) {
      if (p.get("diem_toi_da_tieu_chi_chung") instanceof Number n) {
        return n.intValue();
      }
    }
    if (manifest.get("params") instanceof Map<?, ?> declared
        && declared.get("diem_toi_da_tieu_chi_chung") instanceof Map<?, ?> d
        && d.get("mac_dinh") instanceof Number n) {
      return n.intValue();
    }
    return null;
  }

  private PolicyPackVersion revertToDraft(
      TenantId tenant, UUID actor, PolicyPack pack, PolicyPackVersion current) {
    PolicyPackVersion moved;
    try {
      moved = current.moveTo(PolicyVersionStatus.NHAP, actor, clock.instant());
    } catch (PolicyTransitionException e) {
      throw new BusinessException(e.code(), e.getMessage());
    }
    if (!packs.storeStatus(tenant, moved, current.rowVersion())) {
      throw new VersionConflictException("POLICY", current.rowVersion(), null);
    }
    audit.changed(
        tenant,
        actor,
        "CHUYEN_TRANG_THAI_POLICY_PACK_NHAP",
        PolicyPackService.OBJECT_TYPE,
        pack.code() + "@" + moved.version(),
        moved.id(),
        moved.rowVersion());
    return moved;
  }

  /**
   * Biến được phép của gói: {@code tham_so} và các khối {@code inputs}, {@code inputs_nam}, {@code
   * ngu_canh}, cùng kết quả các định nghĩa của manifest (TL-01 "Cấu trúc gói").
   */
  static Map<String, CelValueType> environment(Map<String, Object> manifest) {
    Map<String, CelValueType> env = new HashMap<>();
    env.put("tham_so", CelValueType.MAP_STRING_DYN);
    for (String block : List.of("inputs", "inputs_nam", "ngu_canh")) {
      if (manifest.get(block) instanceof Map<?, ?> vars) {
        vars.forEach(
            (name, decl) ->
                env.put(
                    String.valueOf(name),
                    declaredType(decl instanceof Map<?, ?> d ? d.get("type") : decl)));
      }
    }
    for (String block : List.of("definitions", "definitions_nam")) {
      if (manifest.get(block) instanceof Map<?, ?> scopes) {
        for (Object scope : scopes.values()) {
          if (scope instanceof Map<?, ?> defs) {
            defs.forEach(
                (name, d) -> {
                  if (d instanceof Map<?, ?> def) {
                    env.put(
                        String.valueOf(name), resultType(String.valueOf(def.get("result_type"))));
                  }
                });
          }
        }
      }
    }
    return env;
  }

  static CelValueType declaredType(Object raw) {
    String t = String.valueOf(raw).toLowerCase(Locale.ROOT);
    if (t.startsWith("list")) {
      return CelValueType.LIST_DYN;
    }
    return switch (t) {
      case "double" -> CelValueType.DOUBLE;
      case "int" -> CelValueType.INT;
      case "bool" -> CelValueType.BOOL;
      case "string" -> CelValueType.STRING;
      default -> CelValueType.MAP_STRING_DYN;
    };
  }

  static CelValueType resultType(String resultType) {
    return switch (resultType) {
      case "BOOLEAN" -> CelValueType.BOOL;
      case "STRING" -> CelValueType.STRING;
      default -> CelValueType.DECIMAL;
    };
  }

  /** MSG-15-01: "Biểu thức không hợp lệ: {loi_cel} tại khối {khoi}." */
  static String celMessage(String ruleKey, CelIssueDetail issue) {
    String reason =
        switch (issue.errorKind()) {
          case "UNDECLARED_IDENT" -> "dùng dữ liệu không có trong ngữ cảnh tính điểm";
          case "TYPE_MISMATCH" -> "các giá trị không cùng kiểu";
          case "RETURN_TYPE_MISMATCH" -> "kết quả không đúng kiểu đã chọn";
          case "SYNTAX" -> "sai cú pháp";
          case "EXPRESSION_TOO_LONG", "AST_DEPTH_EXCEEDED", "AST_NODE_COUNT_EXCEEDED" ->
              "biểu thức quá dài hoặc quá phức tạp";
          default -> "không kiểm tra được kiểu";
        };
    return "Biểu thức không hợp lệ: "
        + reason
        + " tại khối "
        + ruleKey
        + " (dòng "
        + issue.line()
        + ", cột "
        + issue.column()
        + ").";
  }

  private Map<String, Object> read(String value) {
    if (value == null || value.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Nội dung JSON của Policy Pack không đọc được", e);
    }
  }

  private static BusinessException invalid(String field, String message, String rule) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, rule)));
  }
}
