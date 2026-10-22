package vn.viettel.okrkpi.policy.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.contract.policies.model.Flow;
import vn.viettel.okrkpi.contract.policies.model.PeriodRef;
import vn.viettel.okrkpi.contract.policies.model.PolicyBinding;
import vn.viettel.okrkpi.contract.policies.model.PolicyDecisionReason;
import vn.viettel.okrkpi.contract.policies.model.PolicyManifest;
import vn.viettel.okrkpi.contract.policies.model.PolicyPack;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackApprove;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackList;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackOrigin;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackSubmit;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackVersion;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackVersionCreate;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackVersionList;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackVersionStatus;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackVersionSummary;
import vn.viettel.okrkpi.contract.policies.model.Ref;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Approved;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Approver;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Submit;
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackView;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest.Scope;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Gói và phiên bản Policy Pack của {@code policies.yaml} (CORE-05-02, QT-15, SCR-1501): {@code
 * listPolicyPacks}, {@code getPolicyPack}, {@code listPolicyPackVersions}, {@code
 * createPolicyPackVersion}, {@code getPolicyPackVersion}; trình và phê duyệt phát hành (CORE-05-09,
 * SCR-1503): {@code submitPolicyPackVersion}, {@code approvePolicyPackVersion}, {@code
 * rejectPolicyPackVersion}.
 */
@RestController
@RequestMapping("/api/v1/policies/packs")
public class PolicyPackController {

  private static final String BASE = "/api/v1/policies/packs";

  private final PolicyPackService packs;
  private final CursorCodec cursors;
  private final PolicyIdempotency idempotency;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PolicyCaller> callers;
  private final ObjectProvider<PeriodApi> periods;
  private final ObjectMapper json;
  private final ObjectProvider<PolicyApprovalService> approvals;

  public PolicyPackController(
      PolicyPackService packs,
      CursorCodec cursors,
      PolicyIdempotency idempotency,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PolicyCaller> callers,
      ObjectProvider<PeriodApi> periods,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<PolicyApprovalService> approvals) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.json = json.getIfAvailable(ObjectMapper::new);
    this.approvals = Objects.requireNonNull(approvals, "approvals");
  }

  @GetMapping
  @RequiresPermission(operationId = "listPolicyPacks", module = "policies")
  public PolicyPackList listPolicyPacks(
      @RequestParam(value = "flow", required = false) String flow,
      @RequestParam(value = "origin", required = false) String origin,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    String flowName = flow == null ? null : flow(flow);
    PackOrigin originValue = origin == null ? null : enumValue("origin", origin, PackOrigin.class);
    PageRequest page =
        cursors.pageRequest(
            limit, cursor, "policy-packs:" + flow + ":" + origin + ":" + (q == null ? "" : q));
    List<PackView> fetched =
        packs.listPacks(
            TenantContext.require(),
            flowName,
            originValue,
            q,
            page.afterKey().map(k -> k.getString(0)).orElse(null),
            page.fetchSize());
    CursorPage<PackView> result = cursors.page(fetched, page, p -> CursorKey.of(p.pack().code()));
    return new PolicyPackList(
        result.items().stream().map(PolicyPackController::pack).toList(), result.nextCursor());
  }

  @GetMapping("/{id}")
  @RequiresPermission(operationId = "getPolicyPack", module = "policies")
  public PolicyPack getPolicyPack(@PathVariable("id") UUID id) {
    return pack(
        packs
            .findPack(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }

  @GetMapping("/{id}/versions")
  @RequiresPermission(operationId = "listPolicyPackVersions", module = "policies")
  public PolicyPackVersionList listPolicyPackVersions(
      @PathVariable("id") UUID id,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PolicyVersionStatus statusValue =
        status == null ? null : enumValue("status", status, PolicyVersionStatus.class);
    PageRequest page = cursors.pageRequest(limit, cursor, "policy-versions:" + id + ":" + status);
    List<vn.viettel.okrkpi.policy.domain.PolicyPackVersion> fetched =
        notFound(
            () ->
                packs.listVersions(
                    TenantContext.require(),
                    id,
                    statusValue,
                    page.afterKey().map(k -> k.getUuid(0)).orElse(null),
                    page.fetchSize()));
    CursorPage<vn.viettel.okrkpi.policy.domain.PolicyPackVersion> result =
        cursors.page(fetched, page, v -> CursorKey.of(v.id()));
    return new PolicyPackVersionList(
        result.items().stream().map(PolicyPackController::summary).toList(), result.nextCursor());
  }

  @PostMapping("/{id}/versions")
  @RequiresPermission(
      action = "SCR-1501.soan",
      operationId = "createPolicyPackVersion",
      module = "policies")
  public ResponseEntity<PolicyPackVersion> createPolicyPackVersion(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody PolicyPackVersionCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/versions",
        body,
        PolicyPackVersion.class,
        PolicyPackVersion::getRowVersion,
        () -> {
          VersionView created =
              notFound(
                  () ->
                      packs.newVersion(
                          tenant,
                          actor,
                          id,
                          body.getBaseVersion(),
                          body.getVersion(),
                          body.getChangeNote()));
          return ResponseEntity.status(HttpStatus.CREATED)
              .location(
                  ServletUriComponentsBuilder.fromCurrentContextPath()
                      .path(BASE + "/{id}/versions/{v}")
                      .buildAndExpand(id, created.version().version())
                      .toUri())
              .eTag(ETags.of(created.version().rowVersion()))
              .body(detail(tenant, created));
        });
  }

  @GetMapping("/{id}/versions/{v}")
  @RequiresPermission(operationId = "getPolicyPackVersion", module = "policies")
  public ResponseEntity<PolicyPackVersion> getPolicyPackVersion(
      @PathVariable("id") UUID id, @PathVariable("v") String version) {
    TenantId tenant = TenantContext.require();
    VersionView view =
        packs
            .findVersion(tenant, id, version)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(detail(tenant, view), view.version().rowVersion());
  }

  @PostMapping("/{id}/versions/{v}/submit")
  @RequiresPermission(
      action = "SCR-1501.soan",
      operationId = "submitPolicyPackVersion",
      module = "policies")
  public ResponseEntity<PolicyPackVersion> submitPolicyPackVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody PolicyPackSubmit body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/versions/" + version + "/submit",
        body,
        PolicyPackVersion.class,
        PolicyPackVersion::getRowVersion,
        () -> {
          List<Scope> scopes =
              body.getScopes() == null
                  ? List.of()
                  : body.getScopes().stream()
                      .map(
                          s ->
                              new Scope(
                                  s.getFlow() == null ? "" : s.getFlow().getValue(),
                                  s.getSubjectGroupId(),
                                  s.getOrgUnitId()))
                      .toList();
          notFound(
              () ->
                  approvals()
                      .submit(
                          tenant,
                          actor,
                          id,
                          version,
                          expected,
                          new Submit(body.getEffectivePeriodId(), scopes, body.getNote())));
          return versionResponse(tenant, id, version);
        });
  }

  @PostMapping("/{id}/versions/{v}/approve")
  @RequiresPermission(
      action = "SCR-1503.ky-duyet",
      operationId = "approvePolicyPackVersion",
      module = "policies")
  public ResponseEntity<Job> approvePolicyPackVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) PolicyPackApprove body) {
    TenantId tenant = TenantContext.require();
    Approver approver = currentApprover();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    PolicyPackApprove request = body == null ? new PolicyPackApprove() : body;
    return idempotency.run(
        tenant,
        approver.userId(),
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/versions/" + version + "/approve",
        request,
        Job.class,
        j -> 0,
        () -> {
          Approved approved =
              notFound(
                  () ->
                      approvals()
                          .approve(
                              tenant,
                              approver,
                              id,
                              version,
                              expected,
                              request.getEsignSessionId(),
                              request.getComment()));
          return Jobs.accepted(
              new Job(
                  approved.jobId(),
                  "POLICY_PUBLISH",
                  vn.viettel.okrkpi.shared.job.JobStatus.CHO,
                  0,
                  null,
                  null,
                  null,
                  OffsetDateTime.now(ZoneOffset.UTC)));
        });
  }

  @PostMapping("/{id}/versions/{v}/reject")
  @RequiresPermission(operationId = "rejectPolicyPackVersion", module = "policies")
  public ResponseEntity<PolicyPackVersion> rejectPolicyPackVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody PolicyDecisionReason body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/versions/" + version + "/reject",
        body,
        PolicyPackVersion.class,
        PolicyPackVersion::getRowVersion,
        () -> {
          notFound(
              () -> approvals().reject(tenant, actor, id, version, expected, body.getReason()));
          return versionResponse(tenant, id, version);
        });
  }

  private ResponseEntity<PolicyPackVersion> versionResponse(
      TenantId tenant, UUID id, String version) {
    VersionView view =
        packs
            .findVersion(tenant, id, version)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(detail(tenant, view), view.version().rowVersion());
  }

  private PolicyApprovalService approvals() {
    return approvals.getObject();
  }

  private Approver currentApprover() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return new Approver(
          caller.currentUserId().orElseThrow(PolicyPackController::unauthorized),
          caller.acr().orElse(null),
          caller.authTime().orElse(null));
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    return new Approver(
        currentUser(),
        subject == null ? null : subject.acr(),
        subject == null ? null : subject.authTime());
  }

  private UUID currentUser() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId().orElseThrow(PolicyPackController::unauthorized);
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

  private PolicyPackVersion detail(TenantId tenant, VersionView view) {
    vn.viettel.okrkpi.policy.domain.PolicyPackVersion v = view.version();
    PolicyPackVersion dto =
        new PolicyPackVersion(
                v.id(), v.packId(), v.version(), status(v.status()), (int) v.rowVersion())
            .effectiveFrom(v.effectiveFrom())
            .goldenPassRate(v.goldenPassRate())
            .publishedAt(time(v.publishedAt()))
            .changeNote(v.changeNote())
            .createdAt(time(v.createdAt()))
            .createdBy(new Ref(v.createdBy(), ""))
            .packCode(view.pack().code())
            .manifest(manifest(v.manifestJson()))
            .contentHash(v.contentHash())
            .lastTestRunId(v.lastTestRunId())
            .approvedBy(v.approvedBy() == null ? null : new Ref(v.approvedBy(), ""))
            .approvedAt(time(v.approvedAt()))
            .signedAt(time(v.signedAt()))
            .sourceVersionId(v.sourceVersionId());
    PeriodApi periodApi = periods.getIfAvailable();
    for (vn.viettel.okrkpi.policy.domain.PolicyBinding b : view.bindings()) {
      if (periodApi == null) {
        break;
      }
      periodApi
          .findById(tenant, PeriodId.of(b.periodId()))
          .ifPresent(
              p ->
                  dto.addBindingsItem(
                      new PolicyBinding(
                              b.id(),
                              new PeriodRef(p.id().value(), p.name(), p.startDate(), p.endDate()),
                              Flow.fromValue(b.flow()),
                              b.frozen())
                          .subjectGroupId(b.subjectGroupId())
                          .orgUnitId(b.orgUnitId())));
    }
    return dto;
  }

  private PolicyManifest manifest(String manifestJson) {
    try {
      return json.readValue(manifestJson, PolicyManifest.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Manifest của phiên bản không đọc được", e);
    }
  }

  private static String flow(String value) {
    try {
      return Flow.fromValue(value).getValue();
    } catch (IllegalArgumentException e) {
      throw invalidFilter("flow");
    }
  }

  private static PolicyPack pack(PackView view) {
    vn.viettel.okrkpi.policy.domain.PolicyPack p = view.pack();
    return new PolicyPack(
            p.id(),
            p.code(),
            p.name(),
            Flow.fromValue(p.flow()),
            PolicyPackOrigin.fromValue(p.origin().name()))
        .sector(p.sector())
        .parentPackId(p.parentPackId())
        .legalBasis(p.legalBasis())
        .versions(view.versions().stream().map(PolicyPackController::summary).toList());
  }

  private static PolicyPackVersionSummary summary(
      vn.viettel.okrkpi.policy.domain.PolicyPackVersion v) {
    return new PolicyPackVersionSummary(
            v.id(), v.packId(), v.version(), status(v.status()), (int) v.rowVersion())
        .effectiveFrom(v.effectiveFrom())
        .goldenPassRate(v.goldenPassRate())
        .publishedAt(time(v.publishedAt()))
        .changeNote(v.changeNote())
        .createdAt(time(v.createdAt()))
        .createdBy(new Ref(v.createdBy(), ""));
  }

  private static PolicyPackVersionStatus status(PolicyVersionStatus s) {
    return PolicyPackVersionStatus.fromValue(s.name());
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static <E extends Enum<E>> E enumValue(String field, String value, Class<E> type) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw invalidFilter(field);
    }
  }

  private static BusinessException invalidFilter(String field) {
    String message = "Giá trị lọc không hợp lệ";
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
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
