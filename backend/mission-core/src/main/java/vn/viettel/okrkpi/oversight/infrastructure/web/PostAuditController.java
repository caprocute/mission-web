package vn.viettel.okrkpi.oversight.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.contract.oversight.model.ClosePostAuditRequest;
import vn.viettel.okrkpi.contract.oversight.model.CreatePostAuditRequest;
import vn.viettel.okrkpi.contract.oversight.model.ListPostAudits200Response;
import vn.viettel.okrkpi.contract.oversight.model.PostAudit;
import vn.viettel.okrkpi.contract.oversight.model.PostAuditConclusion;
import vn.viettel.okrkpi.contract.oversight.model.PostAuditKind;
import vn.viettel.okrkpi.contract.oversight.model.PostAuditTargetType;
import vn.viettel.okrkpi.contract.oversight.model.Ref;
import vn.viettel.okrkpi.oversight.application.port.PostAuditRepository;
import vn.viettel.okrkpi.oversight.application.postaudit.PostAuditService;
import vn.viettel.okrkpi.oversight.application.postaudit.PostAuditService.View;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Đợt hậu kiểm (CORE-07-09, QT-13 bước 1 và 13, SCR-1301): {@code listPostAudits}, {@code
 * createPostAudit}, {@code getPostAudit}, {@code closePostAudit}. Phát hiện C2 chỉ trả ở chi tiết
 * và phản hồi đóng, danh sách không giải mã.
 */
@RestController
@RequestMapping(PostAuditController.BASE)
public class PostAuditController {

  static final String BASE = "/api/v1/oversight/post-audits";
  private static final String SCOPE = "post-audits";

  private final ObjectProvider<PostAuditService> service;
  private final ObjectProvider<OversightIdempotency> idempotency;
  private final ObjectProvider<AccessDirectory> directory;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OversightCaller> custom;
  private final CursorCodec cursors;

  public PostAuditController(
      ObjectProvider<PostAuditService> service,
      ObjectProvider<OversightIdempotency> idempotency,
      ObjectProvider<AccessDirectory> directory,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OversightCaller> custom,
      CursorCodec cursors) {
    this.service = Objects.requireNonNull(service, "service");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.custom = Objects.requireNonNull(custom, "custom");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  @GetMapping
  @RequiresPermission(operationId = "listPostAudits", module = "oversight")
  public ListPostAudits200Response listPostAudits(
      @RequestParam(value = "period", required = false) UUID periodId,
      @RequestParam(value = "open", required = false) Boolean open,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    PageRequest page = cursors.pageRequest(limit, cursor, SCOPE);
    PostAuditRepository.After after =
        page.afterKey()
            .map(k -> new PostAuditRepository.After(k.getInstant(0), k.getUuid(1)))
            .orElse(null);
    List<vn.viettel.okrkpi.oversight.domain.postaudit.PostAudit> fetched =
        service().list(tenant, actor(), periodId, open, after, page.fetchSize());
    CursorPage<vn.viettel.okrkpi.oversight.domain.postaudit.PostAudit> result =
        cursors.page(fetched, page, a -> CursorKey.of(a.openedAt(), a.id()));
    Map<UUID, String> names = names(tenant, result.items());
    List<PostAudit> items = new ArrayList<>();
    for (var a : result.items()) {
      items.add(toDto(a, null, names));
    }
    return new ListPostAudits200Response().items(items).nextCursor(result.nextCursor());
  }

  @PostMapping
  @RequiresPermission(
      operationId = "createPostAudit",
      module = "oversight",
      resourceType = "post_audit")
  public ResponseEntity<PostAudit> createPostAudit(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CreatePostAuditRequest body) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE,
            body,
            PostAudit.class,
            PostAudit::getRowVersion,
            () -> {
              View v =
                  guarded(
                      () ->
                          service()
                              .create(
                                  tenant,
                                  actor,
                                  new PostAuditService.Draft(
                                      body.getPeriodId(),
                                      body.getAuditKind() == null
                                          ? null
                                          : body.getAuditKind().getValue(),
                                      body.getTargetType() == null
                                          ? null
                                          : body.getTargetType().getValue(),
                                      body.getTargetId(),
                                      body.getBasis())));
              return ResponseEntity.created(URI.create(BASE + "/" + v.audit().id()))
                  .eTag(ETags.of(v.audit().rowVersion()))
                  .body(toDto(tenant, v));
            });
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getPostAudit",
      module = "oversight",
      resourceType = "post_audit")
  public ResponseEntity<PostAudit> getPostAudit(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    View v = guarded(() -> service().get(tenant, actor(), id));
    return ETags.ok(toDto(tenant, v), v.audit().rowVersion());
  }

  @PostMapping("/{id}/close")
  @RequiresPermission(
      operationId = "closePostAudit",
      module = "oversight",
      resourceType = "post_audit")
  public ResponseEntity<PostAudit> closePostAudit(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody ClosePostAuditRequest body) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE + "/" + id + "/close",
            body,
            PostAudit.class,
            PostAudit::getRowVersion,
            () -> {
              View v =
                  guarded(
                      () ->
                          service()
                              .close(
                                  tenant,
                                  actor,
                                  id,
                                  ifMatch,
                                  new PostAuditService.Closing(
                                      body.getConclusion() == null
                                          ? null
                                          : body.getConclusion().getValue(),
                                      body.getFindings(),
                                      body.getReopenRequestId(),
                                      body.getEvidenceObjectIds())));
              return ETags.ok(toDto(tenant, v), v.audit().rowVersion());
            });
  }

  private PostAudit toDto(TenantId tenant, View v) {
    return toDto(v.audit(), v.findings(), names(tenant, List.of(v.audit())));
  }

  private static PostAudit toDto(
      vn.viettel.okrkpi.oversight.domain.postaudit.PostAudit a,
      String findings,
      Map<UUID, String> names) {
    PostAudit dto =
        new PostAudit()
            .id(a.id())
            .periodId(a.periodId())
            .auditKind(PostAuditKind.fromValue(a.auditKind()))
            .targetType(PostAuditTargetType.fromValue(a.targetType()))
            .targetId(a.targetId())
            .inspector(new Ref().id(a.inspectorId()).name(names.getOrDefault(a.inspectorId(), "")))
            .openedAt(odt(a.openedAt()))
            .rowVersion(a.rowVersion());
    dto.setBasis(a.basis());
    dto.setFindings(findings);
    dto.setConclusion(
        a.conclusion() == null ? null : PostAuditConclusion.fromValue(a.conclusion()));
    dto.setReopenRequestId(a.reopenRequestId());
    dto.setClosedAt(odt(a.closedAt()));
    return dto;
  }

  private Map<UUID, String> names(
      TenantId tenant, List<vn.viettel.okrkpi.oversight.domain.postaudit.PostAudit> audits) {
    AccessDirectory dir = directory.getIfAvailable();
    if (dir == null || audits.isEmpty()) {
      return Map.of();
    }
    Set<UUID> people = new java.util.HashSet<>();
    audits.forEach(a -> people.add(a.inspectorId()));
    return dir.personNames(tenant, people);
  }

  private static OffsetDateTime odt(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private Actor actor() {
    OversightCaller caller = custom.getIfAvailable();
    if (caller != null) {
      return new Actor(
          caller.currentUserId().orElseThrow(PostAuditController::unauthorized),
          caller.roles(),
          caller.unitId().orElse(null),
          caller.acr().orElse(null),
          caller.authTime().orElse(null));
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw unauthorized();
    }
    try {
      return new Actor(
          UUID.fromString(subject.userId()),
          subject.roles(),
          subject.unitId() == null || subject.unitId().isBlank()
              ? null
              : UUID.fromString(subject.unitId()),
          subject.acr(),
          subject.authTime());
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  private PostAuditService service() {
    PostAuditService s = service.getIfAvailable();
    if (s == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return s;
  }

  private OversightIdempotency idempotency() {
    OversightIdempotency i = idempotency.getIfAvailable();
    if (i == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return i;
  }

  private static <T> T guarded(Supplier<T> action) {
    try {
      return action.get();
    } catch (PostAuditService.NotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (PostAuditService.SealingUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
