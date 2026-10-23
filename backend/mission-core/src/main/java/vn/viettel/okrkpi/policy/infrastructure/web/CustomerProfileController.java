package vn.viettel.okrkpi.policy.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfile;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfileCreate;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfileList;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfileManifest;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfileVersion;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfileVersionCreate;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfileVersionList;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfileVersionSummary;
import vn.viettel.okrkpi.contract.policies.model.CustomerProfileVersionUpdate;
import vn.viettel.okrkpi.contract.policies.model.ParamConstraint;
import vn.viettel.okrkpi.contract.policies.model.ParamConstraintList;
import vn.viettel.okrkpi.contract.policies.model.ParamConstraintReplace;
import vn.viettel.okrkpi.contract.policies.model.PolicyDecisionComment;
import vn.viettel.okrkpi.contract.policies.model.PolicyDecisionReason;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackApprove;
import vn.viettel.okrkpi.contract.policies.model.PolicyPackVersionStatus;
import vn.viettel.okrkpi.contract.policies.model.Ref;
import vn.viettel.okrkpi.policy.application.CustomerProfileService;
import vn.viettel.okrkpi.policy.application.CustomerProfileService.CreateProfile;
import vn.viettel.okrkpi.policy.application.CustomerProfileService.ProfileView;
import vn.viettel.okrkpi.policy.application.CustomerProfileService.VersionView;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Approver;
import vn.viettel.okrkpi.policy.application.port.PolicyTestJobs;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Hồ sơ tập khách hàng trong {@code policies.yaml} (CORE-05-12, BS-03 ADR-33, QT-15): hồ sơ, phiên
 * bản, khung tham số DC-02 và vòng đời kiểm tra, gửi duyệt, ký duyệt, từ chối như Policy Pack. Bước
 * kiểm tra chạy đồng bộ rồi ghi một tác vụ {@code TEST_RUN} đã xong để bên gọi theo dõi bằng {@code
 * Location}; ký duyệt trả 202 với tác vụ phát hành đã xong ở cell hiện tại.
 */
@RestController
@RequestMapping("/api/v1/policies/customer-profiles")
public class CustomerProfileController {

  private static final String BASE = "/api/v1/policies/customer-profiles";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final CustomerProfileService service;
  private final CursorCodec cursors;
  private final PolicyIdempotency idempotency;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PolicyCaller> callers;
  private final ObjectProvider<PolicyTestJobs> jobs;
  private final ObjectMapper json;

  public CustomerProfileController(
      CustomerProfileService service,
      CursorCodec cursors,
      PolicyIdempotency idempotency,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PolicyCaller> callers,
      ObjectProvider<PolicyTestJobs> jobs,
      ObjectProvider<ObjectMapper> json) {
    this.service = Objects.requireNonNull(service, "service");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.json =
        json.getIfAvailable(ObjectMapper::new)
            .copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  // ───── Hồ sơ ─────

  @GetMapping
  @RequiresPermission(operationId = "listCustomerProfiles", module = "policies")
  public CustomerProfileList listCustomerProfiles(
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PageRequest page =
        cursors.pageRequest(limit, cursor, "customer-profiles:" + (q == null ? "" : q));
    List<ProfileView> fetched =
        service.listProfiles(
            TenantContext.require(),
            q,
            page.afterKey().map(k -> k.getString(0)).orElse(null),
            page.fetchSize());
    CursorPage<ProfileView> result =
        cursors.page(fetched, page, p -> CursorKey.of(p.profile().code()));
    return new CustomerProfileList(
        result.items().stream().map(CustomerProfileController::profile).toList(),
        result.nextCursor());
  }

  @PostMapping
  @RequiresPermission(operationId = "createCustomerProfile", module = "policies")
  public ResponseEntity<CustomerProfile> createCustomerProfile(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CustomerProfileCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE,
        body,
        CustomerProfile.class,
        p -> 0,
        () -> {
          ProfileView created =
              service.newProfile(
                  tenant,
                  actor,
                  new CreateProfile(
                      body.getCode(),
                      body.getName(),
                      body.getIsSimulated(),
                      body.getLegalBasis(),
                      body.getTenantTags()));
          return ResponseEntity.status(HttpStatus.CREATED)
              .location(
                  ServletUriComponentsBuilder.fromCurrentContextPath()
                      .path(BASE + "/{id}")
                      .buildAndExpand(created.profile().id())
                      .toUri())
              .body(profile(created));
        });
  }

  @GetMapping("/{profileId}")
  @RequiresPermission(operationId = "getCustomerProfile", module = "policies")
  public CustomerProfile getCustomerProfile(@PathVariable("profileId") UUID profileId) {
    return profile(
        service
            .findProfile(TenantContext.require(), profileId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }

  // ───── Phiên bản ─────

  @GetMapping("/{profileId}/versions")
  @RequiresPermission(operationId = "listCustomerProfileVersions", module = "policies")
  public CustomerProfileVersionList listCustomerProfileVersions(
      @PathVariable("profileId") UUID profileId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PolicyVersionStatus statusValue = status == null ? null : status(status);
    PageRequest page =
        cursors.pageRequest(limit, cursor, "customer-profile-versions:" + profileId + ":" + status);
    List<vn.viettel.okrkpi.policy.domain.CustomerProfileVersion> fetched =
        notFound(
            () ->
                service.listVersions(
                    TenantContext.require(),
                    profileId,
                    statusValue,
                    page.afterKey().map(k -> k.getUuid(0)).orElse(null),
                    page.fetchSize()));
    CursorPage<vn.viettel.okrkpi.policy.domain.CustomerProfileVersion> result =
        cursors.page(fetched, page, v -> CursorKey.of(v.id()));
    return new CustomerProfileVersionList(
        result.items().stream().map(CustomerProfileController::summary).toList(),
        result.nextCursor());
  }

  @PostMapping("/{profileId}/versions")
  @RequiresPermission(operationId = "createCustomerProfileVersion", module = "policies")
  public ResponseEntity<CustomerProfileVersion> createCustomerProfileVersion(
      @PathVariable("profileId") UUID profileId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CustomerProfileVersionCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + profileId + "/versions",
        body,
        CustomerProfileVersion.class,
        CustomerProfileVersion::getRowVersion,
        () -> {
          VersionView created =
              notFound(
                  () ->
                      service.newVersion(
                          tenant,
                          actor,
                          profileId,
                          body.getBaseVersion(),
                          body.getVersion(),
                          body.getChangeNote(),
                          manifest(body.getManifest())));
          return ResponseEntity.status(HttpStatus.CREATED)
              .location(
                  ServletUriComponentsBuilder.fromCurrentContextPath()
                      .path(BASE + "/{id}/versions/{v}")
                      .buildAndExpand(profileId, created.version().version())
                      .toUri())
              .eTag(ETags.of(created.version().rowVersion()))
              .body(detail(created));
        });
  }

  @GetMapping("/{profileId}/versions/{v}")
  @RequiresPermission(operationId = "getCustomerProfileVersion", module = "policies")
  public ResponseEntity<CustomerProfileVersion> getCustomerProfileVersion(
      @PathVariable("profileId") UUID profileId, @PathVariable("v") String version) {
    VersionView view =
        service
            .findVersion(TenantContext.require(), profileId, version)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(detail(view), view.version().rowVersion());
  }

  @PutMapping("/{profileId}/versions/{v}")
  @RequiresPermission(operationId = "updateCustomerProfileVersion", module = "policies")
  public ResponseEntity<CustomerProfileVersion> updateCustomerProfileVersion(
      @PathVariable("profileId") UUID profileId,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody CustomerProfileVersionUpdate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        BASE + "/" + profileId + "/versions/" + version,
        body,
        CustomerProfileVersion.class,
        CustomerProfileVersion::getRowVersion,
        () -> {
          VersionView saved =
              notFound(
                  () ->
                      service.update(
                          tenant,
                          actor,
                          profileId,
                          version,
                          expected,
                          manifest(body.getManifest()),
                          body.getChangeNote()));
          return ETags.ok(detail(saved), saved.version().rowVersion());
        });
  }

  // ───── Khung tham số ─────

  @GetMapping("/{profileId}/versions/{v}/param-constraints")
  @RequiresPermission(operationId = "listParamConstraints", module = "policies")
  public ResponseEntity<ParamConstraintList> listParamConstraints(
      @PathVariable("profileId") UUID profileId, @PathVariable("v") String version) {
    TenantId tenant = TenantContext.require();
    VersionView view =
        service
            .findVersion(tenant, profileId, version)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(
        constraints(notFound(() -> service.constraints(tenant, profileId, version))),
        view.version().rowVersion());
  }

  @PutMapping("/{profileId}/versions/{v}/param-constraints")
  @RequiresPermission(operationId = "replaceParamConstraints", module = "policies")
  public ResponseEntity<ParamConstraintList> replaceParamConstraints(
      @PathVariable("profileId") UUID profileId,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody ParamConstraintReplace body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        BASE + "/" + profileId + "/versions/" + version + "/param-constraints",
        body,
        ParamConstraintList.class,
        l -> 0,
        () -> {
          List<vn.viettel.okrkpi.policy.domain.ParamConstraint> items =
              body.getItems() == null
                  ? null
                  : body.getItems().stream().map(CustomerProfileController::constraint).toList();
          VersionView saved =
              notFound(
                  () ->
                      service.replaceConstraints(
                          tenant, actor, profileId, version, expected, items));
          return ETags.ok(
              constraints(service.constraints(tenant, profileId, version)),
              saved.version().rowVersion());
        });
  }

  // ───── Vòng đời ─────

  @PostMapping("/{profileId}/versions/{v}/validate")
  @RequiresPermission(operationId = "validateCustomerProfileVersion", module = "policies")
  public ResponseEntity<Job> validateCustomerProfileVersion(
      @PathVariable("profileId") UUID profileId,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + profileId + "/versions/" + version + "/validate",
        Map.of(),
        Job.class,
        j -> 0,
        () -> {
          VersionView validated =
              notFound(() -> service.validate(tenant, actor, profileId, version, expected));
          return finishedJob(
              tenant,
              actor,
              PolicyTestJobs.TEST_RUN,
              profileId,
              validated,
              "Phiên bản hồ sơ đã kiểm tra đạt");
        });
  }

  @PostMapping("/{profileId}/versions/{v}/submit")
  @RequiresPermission(operationId = "submitCustomerProfileVersion", module = "policies")
  public ResponseEntity<CustomerProfileVersion> submitCustomerProfileVersion(
      @PathVariable("profileId") UUID profileId,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) PolicyDecisionComment body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    PolicyDecisionComment request = body == null ? new PolicyDecisionComment() : body;
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + profileId + "/versions/" + version + "/submit",
        request,
        CustomerProfileVersion.class,
        CustomerProfileVersion::getRowVersion,
        () -> {
          VersionView submitted =
              notFound(
                  () ->
                      service.submit(
                          tenant, actor, profileId, version, expected, request.getComment()));
          return ETags.ok(detail(submitted), submitted.version().rowVersion());
        });
  }

  @PostMapping("/{profileId}/versions/{v}/approve")
  @RequiresPermission(operationId = "approveCustomerProfileVersion", module = "policies")
  public ResponseEntity<Job> approveCustomerProfileVersion(
      @PathVariable("profileId") UUID profileId,
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
        BASE + "/" + profileId + "/versions/" + version + "/approve",
        request,
        Job.class,
        j -> 0,
        () -> {
          VersionView approved =
              notFound(
                  () ->
                      service.approve(
                          tenant,
                          approver,
                          profileId,
                          version,
                          expected,
                          request.getEsignSessionId(),
                          request.getComment()));
          return finishedJob(
              tenant,
              approver.userId(),
              "POLICY_PUBLISH",
              profileId,
              approved,
              "Phiên bản hồ sơ đã ký duyệt và phát hành");
        });
  }

  @PostMapping("/{profileId}/versions/{v}/reject")
  @RequiresPermission(operationId = "rejectCustomerProfileVersion", module = "policies")
  public ResponseEntity<CustomerProfileVersion> rejectCustomerProfileVersion(
      @PathVariable("profileId") UUID profileId,
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
        BASE + "/" + profileId + "/versions/" + version + "/reject",
        body,
        CustomerProfileVersion.class,
        CustomerProfileVersion::getRowVersion,
        () -> {
          VersionView rejected =
              notFound(
                  () ->
                      service.reject(
                          tenant, actor, profileId, version, expected, body.getReason()));
          return ETags.ok(detail(rejected), rejected.version().rowVersion());
        });
  }

  // ───── Ánh xạ ─────

  /**
   * Tác vụ đã xong: ghi vào kho tác vụ của MOD-05 (nếu có) để {@code GET /api/v1/jobs/{id}} trả kết
   * quả, trỏ {@code resultLocation} về phiên bản hồ sơ.
   */
  private ResponseEntity<Job> finishedJob(
      TenantId tenant, UUID actor, String type, UUID profileId, VersionView view, String message) {
    String jobId = Ids.newUuidV7().toString();
    String location = BASE + "/" + profileId + "/versions/" + view.version().version();
    PolicyTestJobs store = jobs.getIfAvailable();
    if (store != null) {
      store.accepted(tenant, jobId, actor);
      store.finished(tenant, jobId, location, message);
    }
    return Jobs.accepted(
        new Job(
            jobId,
            type,
            JobStatus.HOAN_TAT,
            100,
            message,
            location,
            null,
            OffsetDateTime.now(ZoneOffset.UTC)),
        0);
  }

  private Map<String, Object> manifest(CustomerProfileManifest manifest) {
    return manifest == null ? null : json.convertValue(manifest, MAP);
  }

  private CustomerProfileVersion detail(VersionView view) {
    vn.viettel.okrkpi.policy.domain.CustomerProfileVersion v = view.version();
    CustomerProfileManifest manifest;
    try {
      manifest = json.readValue(v.manifestJson(), CustomerProfileManifest.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Bản kê của phiên bản hồ sơ không đọc được", e);
    }
    return new CustomerProfileVersion(
            v.id(), v.profileId(), v.version(), status(v.status()), (int) v.rowVersion())
        .changeNote(v.changeNote())
        .publishedAt(time(v.publishedAt()))
        .createdAt(time(v.createdAt()))
        .createdBy(new Ref(v.createdBy(), ""))
        .manifest(manifest)
        .contentHash(v.contentHash())
        .approvedBy(v.approvedBy() == null ? null : new Ref(v.approvedBy(), ""))
        .approvedAt(time(v.approvedAt()))
        .signedAt(time(v.signedAt()))
        .paramConstraintCount(view.constraintCount());
  }

  private static CustomerProfile profile(ProfileView view) {
    vn.viettel.okrkpi.policy.domain.CustomerProfile p = view.profile();
    return new CustomerProfile(p.id(), p.code(), p.name(), p.simulated())
        .legalBasis(p.legalBasis())
        .tenantTags(p.tenantTags())
        .versions(view.versions().stream().map(CustomerProfileController::summary).toList());
  }

  private static CustomerProfileVersionSummary summary(
      vn.viettel.okrkpi.policy.domain.CustomerProfileVersion v) {
    return new CustomerProfileVersionSummary(
            v.id(), v.profileId(), v.version(), status(v.status()), (int) v.rowVersion())
        .changeNote(v.changeNote())
        .publishedAt(time(v.publishedAt()))
        .createdAt(time(v.createdAt()))
        .createdBy(new Ref(v.createdBy(), ""));
  }

  private static ParamConstraintList constraints(
      List<vn.viettel.okrkpi.policy.domain.ParamConstraint> items) {
    return new ParamConstraintList(
        items.stream()
            .map(
                c ->
                    new ParamConstraint(
                            c.paramKey(),
                            c.canToggle(),
                            c.canChangeLevels(),
                            ParamConstraint.TightenDirectionEnum.fromValue(c.tightenDirection()))
                        .id(c.id())
                        .minValue(c.minValue())
                        .maxValue(c.maxValue())
                        .allowedScopes(
                            c.allowedScopes().stream()
                                .map(ParamConstraint.AllowedScopesEnum::fromValue)
                                .toList()))
            .toList());
  }

  private static vn.viettel.okrkpi.policy.domain.ParamConstraint constraint(ParamConstraint c) {
    if (c.getParamKey() == null || c.getTightenDirection() == null) {
      throw invalid("items", "Mỗi khung tham số cần khoá tham số và chiều siết");
    }
    return new vn.viettel.okrkpi.policy.domain.ParamConstraint(
        null,
        c.getParamKey().strip(),
        Boolean.TRUE.equals(c.getCanToggle()),
        Boolean.TRUE.equals(c.getCanChangeLevels()),
        c.getMinValue(),
        c.getMaxValue(),
        c.getTightenDirection().getValue(),
        c.getAllowedScopes() == null
            ? List.of()
            : c.getAllowedScopes().stream()
                .map(ParamConstraint.AllowedScopesEnum::getValue)
                .toList());
  }

  private static PolicyPackVersionStatus status(PolicyVersionStatus s) {
    return PolicyPackVersionStatus.fromValue(s.name());
  }

  private static PolicyVersionStatus status(String value) {
    try {
      return PolicyVersionStatus.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw invalid("status", "Giá trị lọc không hợp lệ");
    }
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private Approver currentApprover() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return new Approver(
          caller.currentUserId().orElseThrow(CustomerProfileController::unauthorized),
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
      return caller.currentUserId().orElseThrow(CustomerProfileController::unauthorized);
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

  private static BusinessException invalid(String field, String message) {
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
