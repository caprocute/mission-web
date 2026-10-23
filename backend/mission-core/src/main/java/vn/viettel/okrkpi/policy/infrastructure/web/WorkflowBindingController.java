package vn.viettel.okrkpi.policy.infrastructure.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.policies.model.ListWorkflowBindings200Response;
import vn.viettel.okrkpi.contract.policies.model.PolicyWorkflowTemplateList;
import vn.viettel.okrkpi.contract.policies.model.PolicyWorkflowTemplateVersion;
import vn.viettel.okrkpi.contract.policies.model.WorkflowBinding;
import vn.viettel.okrkpi.contract.policies.model.WorkflowBindingStatus;
import vn.viettel.okrkpi.contract.policies.model.WorkflowBindingUpdate;
import vn.viettel.okrkpi.contract.policies.model.WorkflowSimulateRequest;
import vn.viettel.okrkpi.contract.policies.model.WorkflowSimulateResult;
import vn.viettel.okrkpi.policy.application.WorkflowBindingService;
import vn.viettel.okrkpi.policy.application.WorkflowBindingService.BindingEdit;
import vn.viettel.okrkpi.policy.application.port.WorkflowBindings.Versioned;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.IdempotencyExceptions;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cấu hình luồng duyệt trong {@code policies.yaml} (CORE-05-11, QT-15, SCR-1504): {@code
 * listWorkflowBindings}, {@code getWorkflowBinding}, {@code saveWorkflowBinding}, {@code
 * simulateWorkflowBinding}, {@code listPolicyWorkflowTemplates}, {@code
 * getPolicyWorkflowTemplateVersion}. Dữ liệu ở {@code flowable.wf_binding} của SVC-06; MOD-05 kiểm
 * quyền, phiên bản gói và khoá kỳ rồi chuyển tiếp.
 */
@RestController
public class WorkflowBindingController {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;
  private static final String BASE = "/api/v1/policies/packs/";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final WorkflowBindingService service;
  private final PolicyIdempotency idempotency;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PolicyCaller> callers;
  private final ObjectMapper json;

  public WorkflowBindingController(
      WorkflowBindingService service,
      PolicyIdempotency idempotency,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PolicyCaller> callers,
      ObjectProvider<ObjectMapper> json) {
    this.service = Objects.requireNonNull(service, "service");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.json =
        json.getIfAvailable(ObjectMapper::new)
            .copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @GetMapping("/api/v1/policies/packs/{id}/versions/{v}/workflow-bindings")
  @RequiresPermission(operationId = "listWorkflowBindings", module = "policies")
  public ListWorkflowBindings200Response listWorkflowBindings(
      @PathVariable("id") UUID id, @PathVariable("v") String version) {
    WorkflowBindingService.VersionBindings found =
        notFound(() -> service.list(TenantContext.require(), id, version));
    return new ListWorkflowBindings200Response(found.items().stream().map(this::binding).toList());
  }

  @GetMapping("/api/v1/policies/packs/{id}/versions/{v}/workflow-bindings/{key}")
  @RequiresPermission(operationId = "getWorkflowBinding", module = "policies")
  public ResponseEntity<WorkflowBinding> getWorkflowBinding(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @PathVariable("key") String key) {
    Versioned found =
        notFound(() -> service.find(TenantContext.require(), id, version, key))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(binding(found.body()), found.rowVersion());
  }

  @PutMapping("/api/v1/policies/packs/{id}/versions/{v}/workflow-bindings/{key}")
  @RequiresPermission(
      action = "SCR-1504.cau-hinh",
      operationId = "saveWorkflowBinding",
      module = "policies")
  public ResponseEntity<WorkflowBinding> saveWorkflowBinding(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @PathVariable("key") String key,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody WorkflowBindingUpdate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        BASE + id + "/versions/" + version + "/workflow-bindings/" + key,
        body,
        WorkflowBinding.class,
        b -> b.getRowVersion() == null ? 0 : b.getRowVersion(),
        () -> {
          Versioned saved =
              notFound(
                  () ->
                      service.save(
                          tenant, actor, id, version, key, expected, idempotencyKey, edit(body)));
          return ETags.ok(binding(saved.body()), saved.rowVersion());
        });
  }

  @PostMapping("/api/v1/policies/workflow-bindings/simulate")
  @RequiresPermission(
      action = "SCR-1504.cau-hinh",
      operationId = "simulateWorkflowBinding",
      module = "policies")
  public WorkflowSimulateResult simulateWorkflowBinding(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody WorkflowSimulateRequest body) {
    TenantId tenant = TenantContext.require();
    currentUser();
    if (idempotencyKey == null || idempotencyKey.isEmpty()) {
      throw new IdempotencyExceptions.KeyRequired(false);
    }
    if (body.getTemplateKey() == null || body.getTemplateVersionNo() == null) {
      throw invalid("templateKey", "Cần chọn mẫu luồng và phiên bản mẫu");
    }
    Map<String, Object> result =
        service.simulate(tenant, json.convertValue(body, MAP), idempotencyKey);
    return json.convertValue(result, WorkflowSimulateResult.class);
  }

  @GetMapping("/api/v1/policies/workflow-templates")
  @RequiresPermission(operationId = "listPolicyWorkflowTemplates", module = "policies")
  public PolicyWorkflowTemplateList listPolicyWorkflowTemplates(
      @RequestParam(value = "processKind", required = false) String processKind,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    Map<String, Object> page =
        service.templates(TenantContext.require(), processKind, q, size, cursor);
    PolicyWorkflowTemplateList list = json.convertValue(page, PolicyWorkflowTemplateList.class);
    if (list.getItems() == null) {
      list.setItems(List.of());
    }
    return list;
  }

  @GetMapping("/api/v1/policies/workflow-templates/{key}/versions/{v}")
  @RequiresPermission(operationId = "getPolicyWorkflowTemplateVersion", module = "policies")
  public ResponseEntity<PolicyWorkflowTemplateVersion> getPolicyWorkflowTemplateVersion(
      @PathVariable("key") String key, @PathVariable("v") int version) {
    Versioned found =
        service
            .template(TenantContext.require(), key, version)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(
        json.convertValue(found.body(), PolicyWorkflowTemplateVersion.class), found.rowVersion());
  }

  private BindingEdit edit(WorkflowBindingUpdate body) {
    return new BindingEdit(
        body.getTemplateKey(),
        body.getTemplateVersionNo(),
        body.getScope() == null ? null : body.getScope().getFromPeriodId(),
        body.getScope() == null ? null : json.convertValue(body.getScope(), MAP),
        body.getParams());
  }

  private WorkflowBinding binding(Map<String, Object> body) {
    WorkflowBinding b = json.convertValue(body, WorkflowBinding.class);
    if (b.getStatus() == null) {
      b.setStatus(WorkflowBindingStatus.NHAP);
    }
    if (b.getParams() == null) {
      b.setParams(Map.of());
    }
    return b;
  }

  private UUID currentUser() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId().orElseThrow(WorkflowBindingController::unauthorized);
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

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
