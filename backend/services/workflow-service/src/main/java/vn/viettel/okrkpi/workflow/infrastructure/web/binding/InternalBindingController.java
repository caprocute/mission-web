package vn.viettel.okrkpi.workflow.infrastructure.web.binding;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.binding.WorkflowBindingService;
import vn.viettel.okrkpi.workflow.domain.binding.BindingDraft;
import vn.viettel.okrkpi.workflow.domain.binding.BindingScope;
import vn.viettel.okrkpi.workflow.domain.binding.LintIssue;
import vn.viettel.okrkpi.workflow.domain.binding.LintResult;
import vn.viettel.okrkpi.workflow.domain.binding.SampleRun;
import vn.viettel.okrkpi.workflow.domain.binding.StepPreview;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateInfo;
import vn.viettel.okrkpi.workflow.domain.binding.TestSummary;
import vn.viettel.okrkpi.workflow.infrastructure.web.CurrentCaller;

/**
 * API nội bộ của SVC-06 cho lớp công bố {@code /policies/.../workflow-bindings} và {@code
 * /policies/workflow-templates} của MOD-05 (DV-06-08, CORE-05-11; QT-15, SCR-1504). Chỉ client dịch
 * vụ có vai trò {@code service_client}; tenant đích đọc từ {@code X-Tenant-Id}. Tài liệu trao đổi
 * theo lược đồ của hợp đồng công bố ({@code WorkflowBinding}, {@code WorkflowSimulateResult},
 * {@code PolicyWorkflowTemplateList}, {@code PolicyWorkflowTemplateVersion}); đường dẫn khớp client
 * {@code WorkflowBindingClient} của mission-core. Hợp đồng chưa có các thao tác nội bộ này nên
 * không xuất ra tài liệu OpenAPI (wiki trang 14).
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/workflow")
public class InternalBindingController {

  static final String VALIDATION = "COMMON-422-VALIDATION";
  private static final String CURSOR_SCOPE = "workflow-templates";

  private final WorkflowBindingService bindings;
  private final CursorCodec cursors;

  public InternalBindingController(WorkflowBindingService bindings, CursorCodec cursors) {
    this.bindings = bindings;
    this.cursors = cursors;
  }

  @GetMapping("/bindings")
  public Map<String, Object> list(@RequestParam UUID policyPackVersionId) {
    return Map.of(
        "items",
        bindings.list(tenant(), policyPackVersionId).stream()
            .map(InternalBindingController::binding)
            .toList());
  }

  @GetMapping("/bindings/{processKind}")
  public ResponseEntity<Map<String, Object>> get(
      @PathVariable String processKind, @RequestParam UUID policyPackVersionId) {
    BindingDraft d =
        bindings
            .find(tenant(), policyPackVersionId, processKind)
            .orElseThrow(WorkflowNotFoundException::new);
    return ETags.ok(binding(d), d.rowVersion());
  }

  @PutMapping("/bindings/{processKind}")
  @Idempotent(schema = "flowable")
  public ResponseEntity<Map<String, Object>> save(
      @PathVariable String processKind,
      @RequestParam UUID policyPackVersionId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody Map<String, Object> body) {
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    List<ProblemFieldError> errors = new ArrayList<>();
    String templateKey = string(body, "templateKey", errors);
    int templateVersion = positive(body.get("templateVersionNo"), "templateVersionNo", errors);
    BindingScope scope = scope(body.get("scope"), errors);
    Map<String, Object> params = map(body.get("params"), "params", errors);
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, "Dữ liệu gửi lên chưa hợp lệ.", errors);
    }
    BindingDraft saved =
        bindings.save(
            new WorkflowBindingService.SaveCommand(
                tenant(),
                policyPackVersionId,
                processKind,
                templateKey,
                templateVersion,
                scope,
                params,
                expected,
                CurrentCaller.actor()));
    return ETags.ok(binding(saved), saved.rowVersion());
  }

  @PostMapping("/bindings/simulate")
  public Map<String, Object> simulate(@RequestBody Map<String, Object> body) {
    List<ProblemFieldError> errors = new ArrayList<>();
    String templateKey = string(body, "templateKey", errors);
    int templateVersion = positive(body.get("templateVersionNo"), "templateVersionNo", errors);
    Map<String, Object> params = map(body.get("params"), "params", errors);
    List<String> samples = new ArrayList<>();
    Object rawSamples = body.get("samples");
    if (rawSamples instanceof List<?> list && list.size() <= 20) {
      list.forEach(s -> samples.add(String.valueOf(s)));
    } else if (rawSamples != null) {
      errors.add(ProblemFieldError.of("samples", "Tối đa 20 hồ sơ mẫu.", "maxItems"));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, "Dữ liệu gửi lên chưa hợp lệ.", errors);
    }
    WorkflowBindingService.Simulation s =
        bindings.simulate(templateKey, templateVersion, params, samples);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("steps", s.steps().stream().map(InternalBindingController::step).toList());
    out.put("lint", lint(s.lint()));
    if (s.templateTests() != null) {
      out.put("templateTests", tests(s.templateTests()));
    }
    out.put("sampleRuns", s.sampleRuns().stream().map(InternalBindingController::run).toList());
    return out;
  }

  /** Saga phát hành Policy Pack bước 4 (CORE-05-10): chép tham số của phiên bản vào wf_binding. */
  @PostMapping("/bindings/activate")
  @Idempotent(schema = "flowable")
  public Map<String, Object> activate(
      @RequestParam UUID policyPackVersionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    return Map.of(
        "items",
        bindings.activate(tenant(), policyPackVersionId, CurrentCaller.actor()).stream()
            .map(InternalBindingController::binding)
            .toList());
  }

  /** Bù trừ hoặc thu hồi gói: trả lại dòng wf_binding cũ. */
  @PostMapping("/bindings/deactivate")
  @Idempotent(schema = "flowable")
  public Map<String, Object> deactivate(
      @RequestParam UUID policyPackVersionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    return Map.of(
        "items",
        bindings.deactivate(tenant(), policyPackVersionId, CurrentCaller.actor()).stream()
            .map(InternalBindingController::binding)
            .toList());
  }

  @GetMapping("/templates")
  public Map<String, Object> templates(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String processKind,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    String scope = CURSOR_SCOPE + ":" + processKind + ":" + q;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    List<TemplateInfo> all =
        status == null || "HIEU_LUC".equals(status)
            ? bindings.templates(processKind, q)
            : List.of();
    List<TemplateInfo> after =
        page.after() == null
            ? all
            : all.stream()
                .filter(
                    t ->
                        (t.key() + "@" + String.format("%06d", t.versionNo()))
                                .compareTo(page.after().getString(0))
                            > 0)
                .toList();
    List<TemplateInfo> fetched = after.stream().limit(page.limit() + 1L).toList();
    CursorPage<TemplateInfo> result =
        cursors.page(
            fetched, page, t -> CursorKey.of(t.key() + "@" + String.format("%06d", t.versionNo())));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", result.items().stream().map(InternalBindingController::summary).toList());
    out.put("nextCursor", result.nextCursor());
    return out;
  }

  @GetMapping("/templates/{key}/versions/{versionNo}")
  public Map<String, Object> template(@PathVariable String key, @PathVariable int versionNo) {
    TemplateInfo t =
        bindings.findTemplate(key, versionNo).orElseThrow(WorkflowNotFoundException::new);
    Map<String, Object> out = summary(t);
    out.put(
        "paramSchema",
        vn.viettel.okrkpi.workflow.domain.binding.ParamsLinter.paramSchema(t.rules()));
    out.put(
        "previewSteps",
        WorkflowBindingService.previewSteps(t).stream()
            .map(InternalBindingController::step)
            .toList());
    return out;
  }

  private static UUID tenant() {
    return TenantContext.require().value();
  }

  static Map<String, Object> binding(BindingDraft d) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", d.id());
    out.put("processKind", d.processKind());
    out.put("templateKey", d.templateKey());
    out.put("templateVersionNo", d.templateVersionNo());
    Map<String, Object> scope = new LinkedHashMap<>();
    if (d.scope().flow() != null) {
      scope.put("flow", d.scope().flow());
    }
    scope.put("subjectGroupId", d.scope().subjectGroupId());
    if (d.scope().periodType() != null) {
      scope.put("periodType", d.scope().periodType());
    }
    scope.put("fromPeriodId", d.scope().fromPeriodId());
    out.put("scope", scope);
    out.put("params", d.params());
    out.put("status", d.status().name());
    out.put("lint", lint(d.lint()));
    if (d.tests() != null) {
      out.put("templateTests", tests(d.tests()));
    }
    out.put("rowVersion", d.rowVersion());
    return out;
  }

  private static Map<String, Object> summary(TemplateInfo t) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("key", t.key());
    out.put("name", t.name());
    out.put("versionNo", t.versionNo());
    out.put("status", "HIEU_LUC");
    out.put("processKind", t.processKind());
    out.put("testCount", WorkflowBindingService.testCount());
    return out;
  }

  private static Map<String, Object> lint(LintResult lint) {
    List<Map<String, Object>> issues = new ArrayList<>();
    for (LintIssue i : lint.issues()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rule", i.rule());
      if (i.elementId() != null) {
        m.put("elementId", i.elementId());
      }
      m.put("severity", i.severity());
      m.put("message", i.message());
      issues.add(m);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("passed", lint.passed());
    out.put("issues", issues);
    return out;
  }

  private static Map<String, Object> tests(TestSummary t) {
    return Map.of("passed", t.passed(), "total", t.total());
  }

  private static Map<String, Object> step(StepPreview s) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("stepKey", s.stepKey());
    out.put("name", s.name());
    if (s.role() != null) {
      out.put("role", s.role());
    }
    out.put("signRequired", s.signRequired());
    out.put("batchSign", s.batchSign());
    out.put("skippable", s.skippable());
    return out;
  }

  private static Map<String, Object> run(SampleRun r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sample", r.sample().label());
    out.put("skippedSteps", r.skippedSteps());
    out.put("endState", r.endState());
    return out;
  }

  private static String string(
      Map<String, Object> body, String field, List<ProblemFieldError> errors) {
    Object v = body.get(field);
    if (!(v instanceof String s) || s.isBlank() || s.length() > 64) {
      errors.add(ProblemFieldError.of(field, "Cần chọn mẫu luồng.", "required"));
      return "";
    }
    return s;
  }

  private static int positive(Object v, String field, List<ProblemFieldError> errors) {
    if (v instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= 1) {
      return n.intValue();
    }
    errors.add(ProblemFieldError.of(field, "Phiên bản mẫu phải là số nguyên từ 1.", "minimum"));
    return 1;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object v, String field, List<ProblemFieldError> errors) {
    if (v instanceof Map<?, ?> m) {
      return new LinkedHashMap<>((Map<String, Object>) m);
    }
    errors.add(ProblemFieldError.of(field, "Cần khai tham số của mẫu.", "required"));
    return Map.of();
  }

  private static BindingScope scope(Object v, List<ProblemFieldError> errors) {
    if (!(v instanceof Map<?, ?> m)) {
      errors.add(ProblemFieldError.of("scope", "Cần chọn phạm vi áp dụng.", "required"));
      return null;
    }
    UUID from = uuid(m.get("fromPeriodId"));
    if (from == null) {
      errors.add(
          ProblemFieldError.of("scope.fromPeriodId", "Cần chọn kỳ bắt đầu áp dụng.", "required"));
      return null;
    }
    Object subject = m.get("subjectGroupId");
    UUID subjectGroup = uuid(subject);
    if (subject != null && subjectGroup == null) {
      errors.add(
          ProblemFieldError.of("scope.subjectGroupId", "Khối đối tượng không hợp lệ.", "format"));
    }
    Object flow = m.get("flow");
    Object periodType = m.get("periodType");
    return new BindingScope(
        flow == null ? null : flow.toString(),
        subjectGroup,
        periodType == null ? null : periodType.toString(),
        from);
  }

  private static UUID uuid(Object v) {
    if (v == null) {
      return null;
    }
    try {
      return UUID.fromString(v.toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
