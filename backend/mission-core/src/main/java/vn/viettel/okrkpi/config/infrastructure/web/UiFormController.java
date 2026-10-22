package vn.viettel.okrkpi.config.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.FormSchemaService;
import vn.viettel.okrkpi.config.application.FormValidationService;
import vn.viettel.okrkpi.config.domain.FormSchema;
import vn.viettel.okrkpi.contract.config.model.PolicyPackVersionRef;
import vn.viettel.okrkpi.contract.config.model.UiFormPage;
import vn.viettel.okrkpi.contract.config.model.UiFormStatus;
import vn.viettel.okrkpi.contract.config.model.UiFormSummary;
import vn.viettel.okrkpi.contract.config.model.UiFormTestRunRequest;
import vn.viettel.okrkpi.contract.config.model.UiFormTestRunResult;
import vn.viettel.okrkpi.contract.config.model.UiFormVersion;
import vn.viettel.okrkpi.contract.config.model.UiFormVersionCelRulesInner;
import vn.viettel.okrkpi.contract.config.model.UiFormVersionCreate;
import vn.viettel.okrkpi.contract.config.model.UiFormVersionUpdate;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Biểu mẫu động của {@code config.yaml} (CORE-08-13, QT-18, SCR-1802, SCR-0901): {@code
 * listUiForms}, {@code createUiFormVersion}, {@code getCurrentUiForm}, {@code getUiFormVersion},
 * {@code updateUiFormVersion}; {@code runUiFormTests} (CORE-08-14).
 */
@RestController
@RequestMapping("/api/v1/ui/forms")
public class UiFormController {

  private static final String BASE = "/api/v1/ui/forms";

  private final FormSchemaService forms;
  private final FormValidationService validation;
  private final CursorCodec cursors;
  private final Callers callers;
  private final ConfigIdempotency idempotency;
  private final ObjectMapper json;

  public UiFormController(
      FormSchemaService forms,
      FormValidationService validation,
      CursorCodec cursors,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency,
      ObjectProvider<ObjectMapper> json) {
    this.forms = Objects.requireNonNull(forms, "forms");
    this.validation = Objects.requireNonNull(validation, "validation");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.json = json.getIfAvailable(ObjectMapper::new);
  }

  @GetMapping
  @RequiresPermission(operationId = "listUiForms", module = "config")
  public UiFormPage listUiForms(
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PageRequest page = cursors.pageRequest(limit, cursor, "ui-forms:" + (q == null ? "" : q));
    List<FormSchema> fetched =
        forms.list(
            TenantContext.require(),
            q,
            page.afterKey().map(k -> k.getString(0)).orElse(null),
            page.fetchSize());
    CursorPage<FormSchema> result = cursors.page(fetched, page, f -> CursorKey.of(f.formKey()));
    return new UiFormPage(
        result.items().stream().map(UiFormController::summary).toList(), result.nextCursor());
  }

  @PostMapping("/{key}/versions")
  @RequiresPermission(operationId = "createUiFormVersion", module = "config")
  public ResponseEntity<UiFormVersion> createUiFormVersion(
      @PathVariable("key") String key,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody UiFormVersionCreate body) {
    TenantId tenant = TenantContext.require();
    java.util.UUID actor = callers.current();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + key + "/versions",
        body,
        UiFormVersion.class,
        UiFormVersion::getRowVersion,
        () -> {
          FormSchema f =
              forms.create(
                  tenant,
                  actor,
                  key,
                  body.getVersion(),
                  body.getCloneFromVersion(),
                  body.getPolicyPackVersionId());
          return ResponseEntity.status(HttpStatus.CREATED)
              .eTag(ETags.of(f.rowVersion()))
              .body(toDto(f));
        });
  }

  @GetMapping("/{key}/current")
  @RequiresPermission(operationId = "getCurrentUiForm", module = "config")
  public ResponseEntity<JsonNode> getCurrentUiForm(
      @PathVariable("key") String key,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    FormSchema f =
        forms
            .current(TenantContext.require(), key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return UiPackPublishController.conditional(
        f.version() + "-" + f.rowVersion(), ifNoneMatch, json.valueToTree(toDto(f)));
  }

  @GetMapping("/{key}/versions/{v}")
  @RequiresPermission(operationId = "getUiFormVersion", module = "config")
  public ResponseEntity<UiFormVersion> getUiFormVersion(
      @PathVariable("key") String key,
      @PathVariable("v") String version,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    FormSchema f = notFound(() -> forms.get(TenantContext.require(), key, version));
    if (ETags.notModified(ifNoneMatch, f.rowVersion())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .header(HttpHeaders.ETAG, ETags.of(f.rowVersion()))
          .build();
    }
    return ETags.ok(toDto(f), f.rowVersion());
  }

  @PutMapping("/{key}/versions/{v}")
  @RequiresPermission(
      action = "SCR-1802.dung",
      operationId = "updateUiFormVersion",
      module = "config")
  public ResponseEntity<UiFormVersion> updateUiFormVersion(
      @PathVariable("key") String key,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody UiFormVersionUpdate body) {
    TenantId tenant = TenantContext.require();
    java.util.UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        BASE + "/" + key + "/versions/" + version,
        body,
        UiFormVersion.class,
        UiFormVersion::getRowVersion,
        () -> {
          FormSchema f =
              notFound(
                  () ->
                      forms.update(
                          tenant,
                          actor,
                          key,
                          version,
                          expected,
                          json.valueToTree(body.getSchemaJson()),
                          json.valueToTree(body.getUiJson()),
                          rules(body.getCelRules()),
                          body.getPolicyPackVersionId()));
          return ETags.ok(toDto(f), f.rowVersion());
        });
  }

  @PostMapping("/{key}/versions/{v}/test-runs")
  @RequiresPermission(action = "SCR-1802.dung", operationId = "runUiFormTests", module = "config")
  public ResponseEntity<UiFormTestRunResult> runUiFormTests(
      @PathVariable("key") String key,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody UiFormTestRunRequest body) {
    TenantId tenant = TenantContext.require();
    java.util.UUID actor = callers.current();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + key + "/versions/" + version + "/test-runs",
        body,
        UiFormTestRunResult.class,
        r -> 0,
        () -> {
          ObjectNode run =
              notFound(
                  () -> validation.runTests(tenant, actor, key, version, json.valueToTree(body)));
          return ResponseEntity.ok(result(run));
        });
  }

  private UiFormTestRunResult result(ObjectNode run) {
    ObjectNode copy = run.deepCopy();
    copy.remove(FormSchemaService.SCORING_HASH);
    try {
      return json.treeToValue(copy, UiFormTestRunResult.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode rules(List<UiFormVersionCelRulesInner> rules) {
    var out = json.createArrayNode();
    if (rules != null) {
      for (UiFormVersionCelRulesInner r : rules) {
        ObjectNode n = out.addObject();
        n.put("field", r.getField());
        n.put("expr", r.getExpr());
        n.put("message", r.getMessage());
        if (Boolean.TRUE.equals(r.getServerOnly())) {
          n.put("serverOnly", true);
        }
      }
    }
    return out;
  }

  private static UiFormSummary summary(FormSchema f) {
    UiFormSummary s =
        new UiFormSummary(f.formKey(), f.version(), UiFormStatus.fromValue(f.status().name()));
    if (f.policyPackVersionId() != null) {
      s.setPolicyPack(new PolicyPackVersionRef(f.policyPackVersionId()));
    }
    s.setUpdatedAt(f.updatedAt().atOffset(ZoneOffset.UTC));
    return s;
  }

  private UiFormVersion toDto(FormSchema f) {
    UiFormVersion dto =
        new UiFormVersion(
            f.formKey(),
            f.version(),
            UiFormStatus.fromValue(f.status().name()),
            map(f.schemaJson()),
            map(f.uiJson()),
            f.rowVersion());
    try {
      dto.setCelRules(
          json.readValue(
              f.celRulesJson(), new TypeReference<List<UiFormVersionCelRulesInner>>() {}));
      if (f.lastTestRunJson() != null) {
        dto.setLastTestRun(result((ObjectNode) json.readTree(f.lastTestRunJson())));
      }
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    if (f.policyPackVersionId() != null) {
      dto.setPolicyPack(new PolicyPackVersionRef(f.policyPackVersionId()));
    }
    dto.setUpdatedAt(f.updatedAt().atOffset(ZoneOffset.UTC));
    return dto;
  }

  private Map<String, Object> map(String raw) {
    try {
      return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> T notFound(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
