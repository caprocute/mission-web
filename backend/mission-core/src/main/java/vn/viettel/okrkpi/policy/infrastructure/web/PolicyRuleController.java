package vn.viettel.okrkpi.policy.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.policies.model.ListPolicyRules200Response;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackVersionStatus;
import vn.viettel.okrkpi.contract.policies.model.PolicyRounding;
import vn.viettel.okrkpi.contract.policies.model.PolicyRoundingMode;
import vn.viettel.okrkpi.contract.policies.model.PolicyRule;
import vn.viettel.okrkpi.contract.policies.model.PolicyRuleResultType;
import vn.viettel.okrkpi.contract.policies.model.PolicyRuleSaved;
import vn.viettel.okrkpi.contract.policies.model.PolicyRuleUpdate;
import vn.viettel.okrkpi.policy.application.PolicyRuleService;
import vn.viettel.okrkpi.policy.application.PolicyRuleService.RuleEdit;
import vn.viettel.okrkpi.policy.application.PolicyRuleService.SavedRule;
import vn.viettel.okrkpi.policy.application.port.PolicyRuleRepository.StoredRule;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Quy tắc CEL của phiên bản Policy Pack trong {@code policies.yaml} (CORE-05-04, QT-15, SCR-1501):
 * {@code listPolicyRules}, {@code getPolicyRule}, {@code savePolicyRule}.
 */
@RestController
@RequestMapping("/api/v1/policies/packs/{id}/versions/{v}/rules")
public class PolicyRuleController {

  private static final String BASE = "/api/v1/policies/packs/";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final PolicyRuleService rules;
  private final PolicyIdempotency idempotency;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PolicyCaller> callers;
  private final ObjectMapper json;

  public PolicyRuleController(
      PolicyRuleService rules,
      PolicyIdempotency idempotency,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PolicyCaller> callers,
      ObjectProvider<ObjectMapper> json) {
    this.rules = Objects.requireNonNull(rules, "rules");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.json = json.getIfAvailable(ObjectMapper::new);
  }

  @GetMapping
  @RequiresPermission(operationId = "listPolicyRules", module = "policies")
  public ListPolicyRules200Response listPolicyRules(
      @PathVariable("id") UUID id, @PathVariable("v") String version) {
    PolicyRuleService.VersionRules found =
        notFound(() -> rules.listRules(TenantContext.require(), id, version));
    return new ListPolicyRules200Response(found.rules().stream().map(this::rule).toList());
  }

  @GetMapping("/{key}")
  @RequiresPermission(operationId = "getPolicyRule", module = "policies")
  public ResponseEntity<PolicyRule> getPolicyRule(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @PathVariable("key") String key) {
    StoredRule stored =
        rules
            .findRule(TenantContext.require(), id, version, key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(rule(stored), stored.rowVersion());
  }

  @PutMapping("/{key}")
  @RequiresPermission(action = "SCR-1501.soan", operationId = "savePolicyRule", module = "policies")
  public ResponseEntity<PolicyRuleSaved> savePolicyRule(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @PathVariable("key") String key,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody PolicyRuleUpdate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        BASE + id + "/versions/" + version + "/rules/" + key,
        body,
        PolicyRuleSaved.class,
        PolicyRuleSaved::getRowVersion,
        () -> {
          SavedRule saved =
              notFound(
                  () -> rules.storeRule(tenant, actor, id, version, key, expected, edit(body)));
          return ETags.ok(saved(saved), saved.rule().rowVersion());
        });
  }

  private RuleEdit edit(PolicyRuleUpdate body) {
    String rounding = null;
    if (body.getRounding() != null) {
      try {
        rounding = json.writeValueAsString(body.getRounding());
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }
    }
    return new RuleEdit(
        body.getExpression(),
        body.getResultType() == null ? null : body.getResultType().getValue(),
        rounding,
        body.getLegalRef(),
        body.getEvalOrder(),
        body.getParams(),
        body.getOverridable());
  }

  private PolicyRule rule(StoredRule stored) {
    vn.viettel.okrkpi.policy.domain.PolicyRule r = stored.rule();
    return new PolicyRule(
            r.id(),
            r.ruleKey(),
            r.expression(),
            PolicyRuleResultType.fromValue(r.resultType()),
            r.evalOrder(),
            r.overridable(),
            (int) stored.rowVersion())
        .rounding(rounding(r.roundingJson()))
        .legalRef(r.legalRef())
        .params(read(r.paramsJson()));
  }

  private PolicyRuleSaved saved(SavedRule saved) {
    vn.viettel.okrkpi.policy.domain.PolicyRule r = saved.rule().rule();
    return new PolicyRuleSaved(
            r.id(),
            r.ruleKey(),
            r.expression(),
            PolicyRuleResultType.fromValue(r.resultType()),
            r.evalOrder(),
            r.overridable(),
            (int) saved.rule().rowVersion(),
            PolicyPackVersionStatus.fromValue(saved.versionStatus().name()),
            saved.statusReverted())
        .rounding(rounding(r.roundingJson()))
        .legalRef(r.legalRef())
        .params(read(r.paramsJson()));
  }

  /** {@code rounding} lưu dạng {@code {digits, mode}}; giá trị khác hợp đồng thì bỏ qua. */
  private PolicyRounding rounding(String value) {
    Map<String, Object> m = read(value);
    if (!(m.get("digits") instanceof Number digits) || !(m.get("mode") instanceof String mode)) {
      return null;
    }
    try {
      return new PolicyRounding(digits.intValue(), PolicyRoundingMode.fromValue(mode));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Map<String, Object> read(String value) {
    try {
      return value == null ? Map.of() : json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Nội dung JSON của quy tắc không đọc được", e);
    }
  }

  private UUID currentUser() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId().orElseThrow(PolicyRuleController::unauthorized);
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw unauthorized();
    }
    try {
      return UUID.fromString(subject.userId());
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  private static <T> T notFound(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
