package vn.viettel.okrkpi.policy.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vn.viettel.okrkpi.contract.policies.model.GoldenTestCase;
import vn.viettel.okrkpi.contract.policies.model.GoldenTestCaseCreate;
import vn.viettel.okrkpi.contract.policies.model.GoldenTestCaseList;
import vn.viettel.okrkpi.contract.policies.model.GoldenTestCaseSource;
import vn.viettel.okrkpi.contract.policies.model.GoldenTestCaseStatus;
import vn.viettel.okrkpi.contract.policies.model.GoldenTestSuite;
import vn.viettel.okrkpi.contract.policies.model.PolicyDecisionComment;
import vn.viettel.okrkpi.contract.policies.model.Ref;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService.CaseEdit;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService.CasePage;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Ca kiểm thử vàng trong {@code policies.yaml} (CORE-05-06, QT-15, SCR-1503): {@code
 * listGoldenTestCases}, {@code createGoldenTestCase}, {@code getGoldenTestCase}, {@code
 * approveGoldenTestCase}.
 */
@RestController
@RequestMapping("/api/v1/policies/test-cases")
public class GoldenTestCaseController {

  private static final String BASE = "/api/v1/policies/test-cases";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final GoldenTestCaseService cases;
  private final PolicyIdempotency idempotency;
  private final CursorCodec cursors;
  private final ObjectMapper json;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PolicyCaller> callers;

  public GoldenTestCaseController(
      GoldenTestCaseService cases,
      PolicyIdempotency idempotency,
      CursorCodec cursors,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PolicyCaller> callers) {
    this.cases = Objects.requireNonNull(cases, "cases");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.json = json.getIfAvailable(ObjectMapper::new);
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @GetMapping
  @RequiresPermission(operationId = "listGoldenTestCases", module = "policies")
  public GoldenTestCaseList listGoldenTestCases(
      @RequestParam("pack") UUID pack,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    GoldenCaseStatus statusValue = status == null ? null : status(status);
    String query = q == null || q.isBlank() ? null : q.strip();
    PageRequest page =
        cursors.pageRequest(
            limit,
            cursor,
            "golden-cases:" + pack + ":" + status + ":" + (query == null ? "" : query));
    CasePage found =
        notFound(
            () ->
                cases.listCases(
                    TenantContext.require(),
                    pack,
                    statusValue,
                    query,
                    page.afterKey().map(k -> k.getString(0)).orElse(null),
                    page.fetchSize()));
    CursorPage<vn.viettel.okrkpi.policy.domain.GoldenTestCase> result =
        cursors.page(found.items(), page, c -> CursorKey.of(c.caseCode()));
    GoldenTestSuite suite =
        new GoldenTestSuite(found.summary().total())
            .pending(found.summary().pending())
            .approvedAt(time(found.summary().lastApprovedAt()));
    return new GoldenTestCaseList(
            result.items().stream().map(this::dto).toList(), result.nextCursor())
        .suite(suite);
  }

  @PostMapping
  @RequiresPermission(
      action = "SCR-1503.chay",
      operationId = "createGoldenTestCase",
      module = "policies")
  public ResponseEntity<GoldenTestCase> createGoldenTestCase(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody GoldenTestCaseCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE,
        body,
        GoldenTestCase.class,
        GoldenTestCase::getRowVersion,
        () -> {
          vn.viettel.okrkpi.policy.domain.GoldenTestCase created =
              notFound(
                  () ->
                      cases.addCase(
                          tenant,
                          actor,
                          new CaseEdit(
                              body.getPolicyPackId(),
                              body.getCaseCode(),
                              body.getDescription(),
                              body.getInput(),
                              body.getExpected(),
                              body.getLegalRef(),
                              body.getSource() == null ? null : body.getSource().getValue())));
          return ResponseEntity.status(HttpStatus.CREATED)
              .location(
                  ServletUriComponentsBuilder.fromCurrentContextPath()
                      .path(BASE + "/{id}")
                      .buildAndExpand(created.id())
                      .toUri())
              .eTag(ETags.of(created.rowVersion()))
              .body(dto(created));
        });
  }

  @GetMapping("/{id}")
  @RequiresPermission(operationId = "getGoldenTestCase", module = "policies")
  public ResponseEntity<GoldenTestCase> getGoldenTestCase(@PathVariable("id") UUID id) {
    vn.viettel.okrkpi.policy.domain.GoldenTestCase found =
        cases
            .findCase(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(dto(found), found.rowVersion());
  }

  @PostMapping("/{id}/approve")
  @RequiresPermission(
      action = "SCR-1503.ky-duyet",
      operationId = "approveGoldenTestCase",
      module = "policies")
  public ResponseEntity<GoldenTestCase> approveGoldenTestCase(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) PolicyDecisionComment body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/approve",
        body == null ? new PolicyDecisionComment() : body,
        GoldenTestCase.class,
        GoldenTestCase::getRowVersion,
        () -> {
          vn.viettel.okrkpi.policy.domain.GoldenTestCase approved =
              notFound(() -> cases.approveCase(tenant, actor, id, expected));
          return ETags.ok(dto(approved), approved.rowVersion());
        });
  }

  private GoldenTestCase dto(vn.viettel.okrkpi.policy.domain.GoldenTestCase c) {
    return new GoldenTestCase(
            c.id(),
            c.packId(),
            c.caseCode(),
            c.description(),
            read(c.inputJson()),
            read(c.expectedJson()),
            GoldenTestCaseSource.fromValue(c.source().name()),
            GoldenTestCaseStatus.fromValue(c.status().name()),
            (int) c.rowVersion())
        .legalRef(c.legalRef())
        .approvedBy(c.approvedBy() == null ? null : new Ref(c.approvedBy(), ""))
        .approvedAt(time(c.approvedAt()))
        .signed(c.signature() != null && c.signature().length > 0)
        .createdBy(c.createdBy() == null ? null : new Ref(c.createdBy(), ""));
  }

  private Map<String, Object> read(String value) {
    try {
      return json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON đã lưu không đọc được", e);
    }
  }

  private static java.time.OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static GoldenCaseStatus status(String value) {
    try {
      return GoldenCaseStatus.valueOf(value);
    } catch (IllegalArgumentException e) {
      String message = "Giá trị lọc không hợp lệ";
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          message,
          List.of(new ProblemFieldError("status", message, null)));
    }
  }

  private UUID currentUser() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId().orElseThrow(GoldenTestCaseController::unauthorized);
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
