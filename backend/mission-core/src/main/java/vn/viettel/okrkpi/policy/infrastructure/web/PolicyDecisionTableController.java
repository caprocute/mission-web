package vn.viettel.okrkpi.policy.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.policies.model.DecisionHitPolicy;
import vn.viettel.okrkpi.contract.policies.model.DecisionInput;
import vn.viettel.okrkpi.contract.policies.model.DecisionRule;
import vn.viettel.okrkpi.contract.policies.model.DecisionTable;
import vn.viettel.okrkpi.contract.policies.model.DecisionTableSaved;
import vn.viettel.okrkpi.contract.policies.model.DecisionTableSavedAllOfWarnings;
import vn.viettel.okrkpi.contract.policies.model.DecisionTableUpdate;
import vn.viettel.okrkpi.contract.policies.model.ListDecisionTables200Response;
import vn.viettel.okrkpi.contract.policies.model.RatingLevel;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService.SavedTable;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService.TableEdit;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService.TableView;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Column;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Row;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Bảng quyết định DMN 1.4 của phiên bản Policy Pack trong {@code policies.yaml} (CORE-05-05, QT-15,
 * SCR-1502): {@code listDecisionTables}, {@code getDecisionTable}, {@code saveDecisionTable},
 * {@code exportDecisionTableDmn}.
 */
@RestController
@RequestMapping("/api/v1/policies/packs/{id}/versions/{v}/decision-tables")
public class PolicyDecisionTableController {

  private static final String BASE = "/api/v1/policies/packs/";

  private final PolicyDecisionTableService tables;
  private final PolicyIdempotency idempotency;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PolicyCaller> callers;

  public PolicyDecisionTableController(
      PolicyDecisionTableService tables,
      PolicyIdempotency idempotency,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PolicyCaller> callers) {
    this.tables = Objects.requireNonNull(tables, "tables");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @GetMapping
  @RequiresPermission(operationId = "listDecisionTables", module = "policies")
  public ListDecisionTables200Response listDecisionTables(
      @PathVariable("id") UUID id, @PathVariable("v") String version) {
    List<TableView> found = notFound(() -> tables.listTables(TenantContext.require(), id, version));
    return new ListDecisionTables200Response(
        found.stream().map(PolicyDecisionTableController::table).toList());
  }

  @GetMapping("/{key}")
  @RequiresPermission(operationId = "getDecisionTable", module = "policies")
  public ResponseEntity<DecisionTable> getDecisionTable(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @PathVariable("key") String key) {
    TableView view = require(id, version, key);
    return ETags.ok(table(view), view.stored().rowVersion());
  }

  @GetMapping(value = "/{key}/dmn", produces = MediaType.APPLICATION_XML_VALUE)
  @RequiresPermission(operationId = "exportDecisionTableDmn", module = "policies")
  public ResponseEntity<String> exportDecisionTableDmn(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @PathVariable("key") String key) {
    TableView view = require(id, version, key);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(key + "-" + version + ".dmn", StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
        .body(view.stored().table().dmnXml());
  }

  @PutMapping("/{key}")
  @RequiresPermission(
      action = "SCR-1502.soan",
      operationId = "saveDecisionTable",
      module = "policies")
  public ResponseEntity<DecisionTableSaved> saveDecisionTable(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @PathVariable("key") String key,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody DecisionTableUpdate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        BASE + id + "/versions/" + version + "/decision-tables/" + key,
        body,
        DecisionTableSaved.class,
        DecisionTableSaved::getRowVersion,
        () -> {
          SavedTable saved =
              notFound(
                  () -> tables.storeTable(tenant, actor, id, version, key, expected, edit(body)));
          return ETags.ok(saved(saved), saved.view().stored().rowVersion());
        });
  }

  private TableView require(UUID id, String version, String key) {
    return tables
        .findTable(TenantContext.require(), id, version, key)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static TableEdit edit(DecisionTableUpdate body) {
    List<Column> inputs =
        body.getInputs() == null
            ? List.of()
            : body.getInputs().stream()
                .map(
                    i ->
                        new Column(
                            i.getName(),
                            i.getLabel(),
                            i.getType() == null ? null : i.getType().getValue()))
                .toList();
    List<Row> rules =
        body.getRules() == null
            ? List.of()
            : body.getRules().stream()
                .map(
                    r ->
                        new Row(
                            r.getInputs(),
                            r.getRatingLevel() == null ? null : r.getRatingLevel().getValue(),
                            r.getClause(),
                            r.getExplanation()))
                .toList();
    return new TableEdit(
        body.getHitPolicy() == null ? null : body.getHitPolicy().getValue(),
        body.getLegalRef(),
        inputs,
        rules,
        body.getExplanationTemplates());
  }

  private static DecisionTable table(TableView view) {
    vn.viettel.okrkpi.policy.domain.DecisionTable t = view.stored().table();
    return new DecisionTable(
            t.id(),
            t.tableKey(),
            DecisionHitPolicy.fromValue(t.hitPolicy()),
            inputs(view),
            rules(view),
            (int) view.stored().rowVersion())
        .legalRef(t.legalRef())
        .explanationTemplates(view.explanationTemplates())
        .dmnHash(HexFormat.of().formatHex(t.dmnHash()));
  }

  private static DecisionTableSaved saved(SavedTable saved) {
    TableView view = saved.view();
    vn.viettel.okrkpi.policy.domain.DecisionTable t = view.stored().table();
    DecisionTableSaved dto =
        new DecisionTableSaved(
                t.id(),
                t.tableKey(),
                DecisionHitPolicy.fromValue(t.hitPolicy()),
                inputs(view),
                rules(view),
                (int) view.stored().rowVersion())
            .legalRef(t.legalRef())
            .explanationTemplates(view.explanationTemplates())
            .dmnHash(HexFormat.of().formatHex(t.dmnHash()))
            .warnings(new java.util.ArrayList<>());
    saved
        .shadowed()
        .forEach(
            s ->
                dto.addWarningsItem(
                    new DecisionTableSavedAllOfWarnings(
                            "MSG-15-04", PolicyDecisionTableService.shadowMessage(s))
                        .row(s.row())
                        .shadowedBy(s.shadowedBy())));
    return dto;
  }

  private static List<DecisionInput> inputs(TableView view) {
    return view.spec().columns().stream()
        .map(
            c ->
                new DecisionInput(c.name(), DecisionInput.TypeEnum.fromValue(c.type()))
                    .label(c.label()))
        .toList();
  }

  private static List<DecisionRule> rules(TableView view) {
    return view.spec().rows().stream()
        .map(
            r ->
                new DecisionRule(
                    new java.util.LinkedHashMap<>(r.inputs()),
                    RatingLevel.fromValue(r.ratingLevel()),
                    r.clause(),
                    r.explanation()))
        .toList();
  }

  private UUID currentUser() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId().orElseThrow(PolicyDecisionTableController::unauthorized);
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
