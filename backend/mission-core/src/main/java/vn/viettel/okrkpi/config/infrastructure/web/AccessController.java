package vn.viettel.okrkpi.config.infrastructure.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import vn.viettel.okrkpi.config.application.AccessActor;
import vn.viettel.okrkpi.config.application.AccessHistoryService;
import vn.viettel.okrkpi.config.application.DelegationService;
import vn.viettel.okrkpi.config.application.RoleBindingService;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.config.domain.Delegation;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.contract.config.model.AccessEndInput;
import vn.viettel.okrkpi.contract.config.model.AccessEndKind;
import vn.viettel.okrkpi.contract.config.model.AccessEvent;
import vn.viettel.okrkpi.contract.config.model.AccessEventKind;
import vn.viettel.okrkpi.contract.config.model.AccessEventPage;
import vn.viettel.okrkpi.contract.config.model.DelegationCreate;
import vn.viettel.okrkpi.contract.config.model.DelegationKind;
import vn.viettel.okrkpi.contract.config.model.DelegationPage;
import vn.viettel.okrkpi.contract.config.model.DelegationStatus;
import vn.viettel.okrkpi.contract.config.model.Flow;
import vn.viettel.okrkpi.contract.config.model.Ref;
import vn.viettel.okrkpi.contract.config.model.RoleBindingApprove;
import vn.viettel.okrkpi.contract.config.model.RoleBindingCounts;
import vn.viettel.okrkpi.contract.config.model.RoleBindingCreate;
import vn.viettel.okrkpi.contract.config.model.RoleBindingPage;
import vn.viettel.okrkpi.contract.config.model.RoleBindingScope;
import vn.viettel.okrkpi.contract.config.model.RoleBindingStatus;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
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
 * Quản trị vai trò và uỷ quyền của {@code config.yaml} (CORE-08-16, đề xuất WEB-058, SCR-0105,
 * SCR-0106, SCR-0107): {@code listRoleBindings}, {@code grantRoleBinding}, {@code getRoleBinding},
 * {@code approveRoleBinding}, {@code rejectRoleBinding}, {@code revokeRoleBinding}, {@code
 * listAccessHistory}, {@code exportAccessHistory}, {@code listDelegations}, {@code
 * createDelegation}, {@code revokeDelegation}. Vai trò và bước lên được kiểm lại ở tầng ứng dụng.
 */
@RestController
@RequestMapping("/api/v1/config")
public class AccessController {

  private static final String BINDINGS = "/api/v1/config/role-bindings";
  private static final String DELEGATIONS = "/api/v1/config/delegations";
  private static final int REASON_MIN = 5;
  private static final int REASON_MAX = 500;

  private final RoleBindingService roles;
  private final DelegationService delegations;
  private final AccessHistoryService history;
  private final AccessDirectory directory;
  private final CursorCodec cursors;
  private final Callers callers;
  private final ConfigIdempotency idempotency;

  public AccessController(
      RoleBindingService roles,
      DelegationService delegations,
      AccessHistoryService history,
      ObjectProvider<AccessDirectory> directory,
      CursorCodec cursors,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency) {
    this.roles = Objects.requireNonNull(roles, "roles");
    this.delegations = Objects.requireNonNull(delegations, "delegations");
    this.history = Objects.requireNonNull(history, "history");
    this.directory = directory.getIfAvailable();
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
  }

  @GetMapping("/role-bindings")
  @RequiresPermission(
      operationId = "listRoleBindings",
      module = "config",
      resourceType = "role_binding")
  public RoleBindingPage listRoleBindings(
      @RequestParam(value = "orgUnitId", required = false) UUID orgUnitId,
      @RequestParam(value = "includeSubunits", required = false) Boolean includeSubunits,
      @RequestParam(value = "personId", required = false) UUID personId,
      @RequestParam(value = "role", required = false) String role,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "awaitingMe", required = false) Boolean awaitingMe,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    PageRequest page = cursors.pageRequest(limit, cursor, "role-bindings");
    if (role != null && !RoleBinding.ROLES.contains(role) && !CustomRole.isCustomCode(role)) {
      throw invalid("role", "Vai trò không hợp lệ.");
    }
    RoleBindingService.Page result =
        roles.list(
            tenant,
            actor,
            orgUnitId,
            Boolean.TRUE.equals(includeSubunits),
            personId,
            role,
            status == null ? null : parse(RoleBinding.Status.class, status, "status"),
            Boolean.TRUE.equals(awaitingMe),
            page.afterKey().map(k -> k.getUuid(0)).orElse(null),
            page.fetchSize());
    CursorPage<RoleBinding> paged = cursors.page(result.items(), page, b -> CursorKey.of(b.id()));
    Names names = names(tenant, paged.items(), List.of());
    Instant now = Instant.now();
    return new RoleBindingPage(
        paged.items().stream().map(b -> toDto(b, names, now)).toList(),
        paged.nextCursor(),
        new RoleBindingCounts(
            result.counts().active(),
            result.counts().pending(),
            result.counts().awaitingMe(),
            result.counts().expiringSoon()));
  }

  @PostMapping("/role-bindings")
  @RequiresPermission(
      operationId = "grantRoleBinding",
      module = "config",
      resourceType = "role_binding")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.RoleBinding> grantRoleBinding(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody RoleBindingCreate body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        BINDINGS,
        body,
        vn.viettel.okrkpi.contract.config.model.RoleBinding.class,
        vn.viettel.okrkpi.contract.config.model.RoleBinding::getRowVersion,
        () -> {
          require(body.getPersonId(), "personId");
          require(body.getRoleCode(), "roleCode");
          require(body.getOrgUnitId(), "orgUnitId");
          require(body.getScope(), "scope");
          RoleBinding b =
              roles.grant(
                  tenant,
                  actor,
                  new RoleBindingService.Grant(
                      body.getPersonId(),
                      body.getRoleCode(),
                      body.getOrgUnitId(),
                      RoleBinding.Scope.valueOf(body.getScope().getValue()),
                      body.getFlow() == null ? null : body.getFlow().getValue(),
                      instant(body.getValidFrom(), "validFrom"),
                      instant(body.getValidTo(), "validTo"),
                      reason(body.getReason())));
          return ResponseEntity.created(URI.create(BINDINGS + "/" + b.id()))
              .eTag(ETags.of(b.rowVersion()))
              .body(toDto(tenant, b));
        });
  }

  @GetMapping("/role-bindings/{id}")
  @RequiresPermission(
      operationId = "getRoleBinding",
      module = "config",
      resourceType = "role_binding")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.RoleBinding> getRoleBinding(
      @PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    RoleBinding b = notFoundAs404(() -> roles.get(tenant, callers.accessActor(), id));
    return ETags.ok(toDto(tenant, b), b.rowVersion());
  }

  @PostMapping("/role-bindings/{id}/approve")
  @RequiresPermission(
      operationId = "approveRoleBinding",
      module = "config",
      resourceType = "role_binding")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.RoleBinding> approveRoleBinding(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) RoleBindingApprove body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return bindingCommand(
        tenant,
        actor,
        idempotencyKey,
        "/approve",
        id,
        body,
        () -> roles.approve(tenant, actor, id, expected));
  }

  @PostMapping("/role-bindings/{id}/reject")
  @RequiresPermission(
      operationId = "rejectRoleBinding",
      module = "config",
      resourceType = "role_binding")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.RoleBinding> rejectRoleBinding(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AccessEndInput body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return bindingCommand(
        tenant,
        actor,
        idempotencyKey,
        "/reject",
        id,
        body,
        () -> roles.reject(tenant, actor, id, expected, reason(body.getReason())));
  }

  @PostMapping("/role-bindings/{id}/revoke")
  @RequiresPermission(
      operationId = "revokeRoleBinding",
      module = "config",
      resourceType = "role_binding")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.RoleBinding> revokeRoleBinding(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AccessEndInput body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return bindingCommand(
        tenant,
        actor,
        idempotencyKey,
        "/revoke",
        id,
        body,
        () -> roles.revoke(tenant, actor, id, expected, reason(body.getReason())));
  }

  @GetMapping("/access-history")
  @RequiresPermission(
      operationId = "listAccessHistory",
      module = "config",
      resourceType = "role_binding")
  public AccessEventPage listAccessHistory(
      @RequestParam(value = "personId", required = false) UUID personId,
      @RequestParam(value = "orgUnitId", required = false) UUID orgUnitId,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    PageRequest page = cursors.pageRequest(limit, cursor, "access-history");
    AccessHistoryService.After after =
        page.afterKey()
            .map(k -> new AccessHistoryService.After(k.getInstant(0), k.getUuid(1)))
            .orElse(null);
    List<AccessHistoryService.Entry> fetched =
        history.list(tenant, callers.accessActor(), personId, orgUnitId, after, page.fetchSize());
    CursorPage<AccessHistoryService.Entry> paged =
        cursors.page(fetched, page, e -> CursorKey.of(e.at(), e.id()));
    Map<UUID, String> people = personNames(tenant, paged.items());
    return new AccessEventPage(
        paged.items().stream().map(e -> toDto(e, people)).toList(), paged.nextCursor());
  }

  @GetMapping(value = "/access-history/export", produces = "text/csv")
  @RequiresPermission(
      operationId = "exportAccessHistory",
      module = "config",
      resourceType = "role_binding")
  public ResponseEntity<byte[]> exportAccessHistory(
      @RequestParam(value = "personId", required = false) UUID personId) {
    TenantId tenant = TenantContext.require();
    List<AccessHistoryService.Entry> entries =
        history.export(tenant, callers.accessActor(), personId);
    Map<UUID, String> people = personNames(tenant, entries);
    StringBuilder csv =
        new StringBuilder("﻿Thời điểm,Loại thay đổi,Người thực hiện,Người được cấp,Nội dung\r\n");
    for (AccessHistoryService.Entry e : entries) {
      csv.append(cell(time(e.at()).toString()))
          .append(',')
          .append(cell(KIND_LABEL.get(e.kind().name())))
          .append(',')
          .append(cell(e.actorId() == null ? "Hệ thống" : people.getOrDefault(e.actorId(), "")))
          .append(',')
          .append(cell(people.getOrDefault(e.personId(), "")))
          .append(',')
          .append(cell(e.summary()))
          .append("\r\n");
    }
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lich-su-phan-quyen.csv\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(csv.toString().getBytes(StandardCharsets.UTF_8));
  }

  @GetMapping("/delegations")
  @RequiresPermission(
      operationId = "listDelegations",
      module = "config",
      resourceType = "delegation")
  public DelegationPage listDelegations(
      @RequestParam(value = "orgUnitId", required = false) UUID orgUnitId,
      @RequestParam(value = "personId", required = false) UUID personId,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    PageRequest page = cursors.pageRequest(limit, cursor, "delegations");
    List<Delegation> fetched =
        delegations.list(
            tenant,
            callers.accessActor(),
            orgUnitId,
            personId,
            kind == null ? null : parse(Delegation.Kind.class, kind, "kind"),
            status == null ? null : parse(Delegation.Status.class, status, "status"),
            page.afterKey().map(k -> k.getUuid(0)).orElse(null),
            page.fetchSize());
    CursorPage<Delegation> paged = cursors.page(fetched, page, d -> CursorKey.of(d.id()));
    Names names = names(tenant, List.of(), paged.items());
    Instant now = Instant.now();
    return new DelegationPage(
        paged.items().stream().map(d -> toDto(d, names, now)).toList(), paged.nextCursor());
  }

  @PostMapping("/delegations")
  @RequiresPermission(
      operationId = "createDelegation",
      module = "config",
      resourceType = "delegation")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.Delegation> createDelegation(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody DelegationCreate body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        DELEGATIONS,
        body,
        vn.viettel.okrkpi.contract.config.model.Delegation.class,
        vn.viettel.okrkpi.contract.config.model.Delegation::getRowVersion,
        () -> {
          require(body.getKind(), "kind");
          require(body.getDelegatorId(), "delegatorId");
          require(body.getDelegateId(), "delegateId");
          require(body.getOrgUnitId(), "orgUnitId");
          Delegation d =
              delegations.create(
                  tenant,
                  actor,
                  new DelegationService.Declare(
                      Delegation.Kind.valueOf(body.getKind().getValue()),
                      body.getDelegatorId(),
                      body.getDelegateId(),
                      body.getOrgUnitId(),
                      body.getSubjectId(),
                      instant(body.getValidFrom(), "validFrom"),
                      instant(body.getValidTo(), "validTo"),
                      reason(body.getReason())));
          return ResponseEntity.created(URI.create(DELEGATIONS + "/" + d.id()))
              .eTag(ETags.of(d.rowVersion()))
              .body(toDto(tenant, d));
        });
  }

  @PostMapping("/delegations/{id}/revoke")
  @RequiresPermission(
      operationId = "revokeDelegation",
      module = "config",
      resourceType = "delegation")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.Delegation> revokeDelegation(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AccessEndInput body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        DELEGATIONS + "/" + id + "/revoke",
        body,
        vn.viettel.okrkpi.contract.config.model.Delegation.class,
        vn.viettel.okrkpi.contract.config.model.Delegation::getRowVersion,
        () -> {
          Delegation d =
              notFoundAs404(
                  () -> delegations.revoke(tenant, actor, id, expected, reason(body.getReason())));
          return ETags.ok(toDto(tenant, d), d.rowVersion());
        });
  }

  private ResponseEntity<vn.viettel.okrkpi.contract.config.model.RoleBinding> bindingCommand(
      TenantId tenant,
      AccessActor actor,
      String idempotencyKey,
      String action,
      UUID id,
      Object body,
      Supplier<RoleBinding> command) {
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        BINDINGS + "/" + id + action,
        body,
        vn.viettel.okrkpi.contract.config.model.RoleBinding.class,
        vn.viettel.okrkpi.contract.config.model.RoleBinding::getRowVersion,
        () -> {
          RoleBinding b = notFoundAs404(command);
          return ETags.ok(toDto(tenant, b), b.rowVersion());
        });
  }

  // ---- Ánh xạ DTO ----

  /** Tên người và đơn vị cần cho một trang. */
  private record Names(Map<UUID, String> people, Map<UUID, String> units) {
    Ref person(UUID id) {
      return id == null ? null : new Ref(id, people.getOrDefault(id, ""));
    }

    Ref unit(UUID id) {
      return id == null ? null : new Ref(id, units.getOrDefault(id, ""));
    }
  }

  private Names names(TenantId tenant, Collection<RoleBinding> bs, Collection<Delegation> ds) {
    Set<UUID> people = new HashSet<>();
    Set<UUID> units = new HashSet<>();
    for (RoleBinding b : bs) {
      people.add(b.personId());
      people.add(b.grantedBy());
      people.add(b.approvedBy());
      people.add(b.endedBy());
      units.add(b.orgUnitId());
    }
    for (Delegation d : ds) {
      people.add(d.delegatorId());
      people.add(d.delegateId());
      people.add(d.createdBy());
      people.add(d.endedBy());
      units.add(d.orgUnitId());
    }
    people.remove(null);
    if (directory == null) {
      return new Names(Map.of(), Map.of());
    }
    return new Names(
        people.isEmpty() ? Map.of() : directory.personNames(tenant, people),
        units.isEmpty() ? Map.of() : directory.unitNames(tenant, units));
  }

  private Map<UUID, String> personNames(
      TenantId tenant, Collection<AccessHistoryService.Entry> entries) {
    Set<UUID> ids = new HashSet<>();
    for (AccessHistoryService.Entry e : entries) {
      ids.add(e.personId());
      ids.add(e.actorId());
    }
    ids.remove(null);
    return directory == null || ids.isEmpty() ? Map.of() : directory.personNames(tenant, ids);
  }

  private vn.viettel.okrkpi.contract.config.model.RoleBinding toDto(
      TenantId tenant, RoleBinding b) {
    return toDto(b, names(tenant, List.of(b), List.of()), Instant.now());
  }

  private vn.viettel.okrkpi.contract.config.model.Delegation toDto(TenantId tenant, Delegation d) {
    return toDto(d, names(tenant, List.of(), List.of(d)), Instant.now());
  }

  private static vn.viettel.okrkpi.contract.config.model.RoleBinding toDto(
      RoleBinding b, Names names, Instant now) {
    vn.viettel.okrkpi.contract.config.model.RoleBinding dto =
        new vn.viettel.okrkpi.contract.config.model.RoleBinding(
            b.id(),
            names.person(b.personId()),
            b.roleCode(),
            names.unit(b.orgUnitId()),
            RoleBindingScope.fromValue(b.scope().name()),
            time(b.validFrom()),
            time(b.validTo()),
            RoleBindingStatus.fromValue(b.status(now).name()),
            b.sensitive(),
            names.person(b.grantedBy()),
            time(b.grantedAt()),
            b.reason() == null ? "" : b.reason(),
            b.rowVersion());
    dto.setFlow(b.flow() == null ? null : Flow.fromValue(b.flow()));
    dto.setApprovedBy(names.person(b.approvedBy()));
    dto.setApprovedAt(time(b.approvedAt()));
    dto.setEndedBy(names.person(b.endedBy()));
    dto.setEndedAt(time(b.endedAt()));
    dto.setEndKind(b.endKind() == null ? null : AccessEndKind.fromValue(b.endKind().name()));
    dto.setEndReason(b.endReason());
    return dto;
  }

  private static vn.viettel.okrkpi.contract.config.model.Delegation toDto(
      Delegation d, Names names, Instant now) {
    vn.viettel.okrkpi.contract.config.model.Delegation dto =
        new vn.viettel.okrkpi.contract.config.model.Delegation(
            d.id(),
            DelegationKind.fromValue(d.kind().name()),
            names.person(d.delegatorId()),
            names.person(d.delegateId()),
            names.unit(d.orgUnitId()),
            time(d.validFrom()),
            time(d.validTo()),
            d.reason() == null ? "" : d.reason(),
            DelegationStatus.fromValue(d.status(now).name()),
            names.person(d.createdBy()),
            time(d.createdAt()),
            d.rowVersion());
    dto.setSubject(d.subjectId() == null ? null : new Ref(d.subjectId(), ""));
    dto.setEndedBy(names.person(d.endedBy()));
    dto.setEndedAt(time(d.endedAt()));
    dto.setEndReason(d.endReason());
    return dto;
  }

  private static AccessEvent toDto(AccessHistoryService.Entry e, Map<UUID, String> people) {
    AccessEvent dto =
        new AccessEvent(
            e.id(),
            time(e.at()),
            AccessEventKind.fromValue(e.kind().name()),
            new Ref(e.personId(), people.getOrDefault(e.personId(), "")),
            e.summary(),
            e.refId());
    dto.setActor(
        e.actorId() == null ? null : new Ref(e.actorId(), people.getOrDefault(e.actorId(), "")));
    return dto;
  }

  private static final Map<String, String> KIND_LABEL =
      Map.of(
          "CAP_VAI_TRO", "Cấp vai trò",
          "DUYET_VAI_TRO", "Duyệt thứ hai",
          "TU_CHOI_VAI_TRO", "Từ chối cấp vai trò",
          "THU_HOI_VAI_TRO", "Thu hồi vai trò",
          "HET_HAN_VAI_TRO", "Vai trò hết hạn",
          "TAO_UY_QUYEN", "Uỷ quyền",
          "THU_HOI_UY_QUYEN", "Thu hồi uỷ quyền",
          "HET_HAN_UY_QUYEN", "Uỷ quyền hết hạn");

  // ---- Kiểm đầu vào ----

  /** Ô CSV: bọc nháy kép, chặn chèn công thức bảng tính (ô mở đầu bằng = + - @). */
  static String cell(String value) {
    String v = value == null ? "" : value;
    if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
      v = "'" + v;
    }
    return "\"" + v.replace("\"", "\"\"") + "\"";
  }

  private static String reason(String reason) {
    String r = reason == null ? "" : reason.strip();
    if (r.length() < REASON_MIN || r.length() > REASON_MAX) {
      throw invalid("reason", "Lý do cần từ 5 đến 500 ký tự.");
    }
    return r;
  }

  private static void require(Object value, String field) {
    if (value == null) {
      throw invalid(field, "Thông tin này là bắt buộc.");
    }
  }

  private static Instant instant(OffsetDateTime value, String field) {
    require(value, field);
    return value.toInstant();
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw invalid(field, "Giá trị không hợp lệ.");
    }
  }

  private static <T> T notFoundAs404(Supplier<T> action) {
    try {
      return action.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
