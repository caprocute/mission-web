package vn.viettel.okrkpi.policy.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.policies.model.PolicyEngine;
import vn.viettel.okrkpi.contract.policies.model.PolicyEvaluateOutputs;
import vn.viettel.okrkpi.contract.policies.model.PolicyEvaluateRequest;
import vn.viettel.okrkpi.contract.policies.model.PolicyEvaluateResult;
import vn.viettel.okrkpi.contract.policies.model.PolicyEvaluateResultTraceInner;
import vn.viettel.okrkpi.contract.policies.model.PolicyVersionDiff;
import vn.viettel.okrkpi.contract.policies.model.PolicyVersionDiffChangesInner;
import vn.viettel.okrkpi.contract.policies.model.RatingLevel;
import vn.viettel.okrkpi.policy.application.PolicyPreviewService;
import vn.viettel.okrkpi.policy.application.PolicyPreviewService.Evaluation;
import vn.viettel.okrkpi.policy.application.PolicyPreviewService.VersionDiff;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * So sánh phiên bản và tính thử một ca trong {@code policies.yaml} (CORE-05-08, QT-15, SCR-1501,
 * SCR-1503): {@code diffPolicyPackVersions}, {@code evaluatePolicyVersion}.
 */
@RestController
@Validated
@RequestMapping("/api/v1/policies/packs/{id}/versions/{v}")
public class PolicyPreviewController {

  private final PolicyPreviewService preview;
  private final PolicyIdempotency idempotency;
  private final ObjectMapper json;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PolicyCaller> callers;

  public PolicyPreviewController(
      PolicyPreviewService preview,
      PolicyIdempotency idempotency,
      ObjectMapper json,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PolicyCaller> callers) {
    this.preview = Objects.requireNonNull(preview, "preview");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.json = Objects.requireNonNull(json, "json");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @GetMapping("/diff")
  @RequiresPermission(operationId = "diffPolicyPackVersions", module = "policies")
  public PolicyVersionDiff diffPolicyPackVersions(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestParam("base")
          @jakarta.validation.constraints.Pattern(regexp = "^[0-9]+\\.[0-9]+\\.[0-9]+$") String base) {
    VersionDiff d = notFound(() -> preview.diff(TenantContext.require(), id, version, base));
    return new PolicyVersionDiff(
        d.base(),
        d.target(),
        d.changes().stream()
            .map(
                c -> {
                  PolicyVersionDiffChangesInner dto =
                      new PolicyVersionDiffChangesInner(
                              PolicyVersionDiffChangesInner.KindEnum.fromValue(c.kind().name()),
                              c.key())
                          .change(
                              PolicyVersionDiffChangesInner.ChangeEnum.fromValue(c.change().name()))
                          .before(c.before())
                          .after(c.after());
                  if (c.path() != null) {
                    dto.path(c.path());
                  }
                  return dto;
                })
            .toList());
  }

  @PostMapping("/evaluate")
  @RequiresPermission(
      action = "SCR-1501.soan",
      operationId = "evaluatePolicyVersion",
      module = "policies")
  public ResponseEntity<PolicyEvaluateResult> evaluatePolicyVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody PolicyEvaluateRequest body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        "/api/v1/policies/packs/" + id + "/versions/" + version + "/evaluate",
        body,
        PolicyEvaluateResult.class,
        r -> 0,
        () -> {
          Evaluation e =
              notFound(() -> preview.evaluateCase(tenant, id, version, body.getCaseCode()));
          PolicyEvaluateResult dto =
              new PolicyEvaluateResult(
                      e.caseCode(),
                      PolicyEngine.CEL_JAVA,
                      json.convertValue(e.outputs(), PolicyEvaluateOutputs.class))
                  .trace(
                      e.trace().stream()
                          .map(
                              t -> new PolicyEvaluateResultTraceInner(t.ruleKey()).value(t.value()))
                          .toList());
          if (e.ratingLevel() != null) {
            dto.ratingLevel(RatingLevel.fromValue(e.ratingLevel()));
          }
          if (e.explanation() != null) {
            dto.explanation(e.explanation());
          }
          return ResponseEntity.ok(dto);
        });
  }

  private UUID currentUser() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId().orElseThrow(PolicyPreviewController::unauthorized);
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
