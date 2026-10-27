package vn.viettel.okrkpi.config.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
import vn.viettel.okrkpi.config.application.AccessActor;
import vn.viettel.okrkpi.config.application.CustomRoleService;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.config.infrastructure.authz.ClasspathPermissionCatalog;
import vn.viettel.okrkpi.contract.config.model.AccessEndInput;
import vn.viettel.okrkpi.contract.config.model.CustomRoleCreate;
import vn.viettel.okrkpi.contract.config.model.CustomRolePermission;
import vn.viettel.okrkpi.contract.config.model.CustomRoleStatus;
import vn.viettel.okrkpi.contract.config.model.CustomRoleUpdate;
import vn.viettel.okrkpi.contract.config.model.ListCustomRoles200Response;
import vn.viettel.okrkpi.contract.config.model.PermissionScope;
import vn.viettel.okrkpi.contract.config.model.RoleCode;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Vai trò tuỳ chỉnh của tenant và danh mục quyền (CORE-08-18, BS-04, SCR-0110): {@code
 * getPermissionCatalog}, {@code listCustomRoles}, {@code createCustomRole}, {@code getCustomRole},
 * {@code updateCustomRole}, {@code retireCustomRole}, {@code listMyCustomRoles}. Vai trò và bước
 * lên được kiểm lại ở tầng ứng dụng.
 */
@RestController
@RequestMapping("/api/v1/config")
public class CustomRoleController {

  private static final String ROLES = "/api/v1/config/custom-roles";
  private static final int REASON_MIN = 5;
  private static final int REASON_MAX = 500;

  private final CustomRoleService service;
  private final ClasspathPermissionCatalog catalog;
  private final Callers callers;
  private final ConfigIdempotency idempotency;

  public CustomRoleController(
      CustomRoleService service,
      ClasspathPermissionCatalog catalog,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency) {
    this.service = Objects.requireNonNull(service, "service");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
  }

  @GetMapping("/permission-catalog")
  @RequiresPermission(
      operationId = "getPermissionCatalog",
      module = "config",
      resourceType = "custom_role")
  public ResponseEntity<JsonNode> getPermissionCatalog() {
    // Phiên bản danh mục có dấu cách nên ETag lấy theo mã băm của nó.
    String tag = "\"catalog-" + Integer.toHexString(catalog.version().hashCode()) + "\"";
    return ResponseEntity.ok().eTag(tag).body(catalog.document());
  }

  @GetMapping("/custom-roles")
  @RequiresPermission(
      operationId = "listCustomRoles",
      module = "config",
      resourceType = "custom_role")
  public ListCustomRoles200Response listCustomRoles(
      @RequestParam(value = "status", required = false) String status) {
    TenantId tenant = TenantContext.require();
    CustomRole.Status s = status == null ? null : parseStatus(status);
    return new ListCustomRoles200Response(
        service.list(tenant, callers.accessActor(), s).stream()
            .map(CustomRoleController::toDto)
            .toList());
  }

  @PostMapping("/custom-roles")
  @RequiresPermission(
      operationId = "createCustomRole",
      module = "config",
      resourceType = "custom_role")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.CustomRole> createCustomRole(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CustomRoleCreate body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        ROLES,
        body,
        vn.viettel.okrkpi.contract.config.model.CustomRole.class,
        vn.viettel.okrkpi.contract.config.model.CustomRole::getRowVersion,
        () -> {
          require(body.getBaseRole(), "baseRole");
          CustomRoleService.View v =
              service.create(
                  tenant,
                  actor,
                  body.getName(),
                  body.getDescription(),
                  body.getBaseRole().getValue(),
                  permissions(body.getPermissions()));
          return ResponseEntity.created(URI.create(ROLES + "/" + v.role().id()))
              .eTag(ETags.of(v.role().rowVersion()))
              .body(toDto(v));
        });
  }

  @GetMapping("/custom-roles/{id}")
  @RequiresPermission(
      operationId = "getCustomRole",
      module = "config",
      resourceType = "custom_role")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.CustomRole> getCustomRole(
      @PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    CustomRoleService.View v = notFoundAs404(() -> service.get(tenant, callers.accessActor(), id));
    return ResponseEntity.ok().eTag(ETags.of(v.role().rowVersion())).body(toDto(v));
  }

  @PutMapping("/custom-roles/{id}")
  @RequiresPermission(
      operationId = "updateCustomRole",
      module = "config",
      resourceType = "custom_role")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.CustomRole> updateCustomRole(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody CustomRoleUpdate body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return command(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        ROLES + "/" + id,
        body,
        () ->
            service.update(
                tenant,
                actor,
                id,
                expected,
                body.getName(),
                body.getDescription(),
                permissions(body.getPermissions())));
  }

  @PostMapping("/custom-roles/{id}/retire")
  @RequiresPermission(
      operationId = "retireCustomRole",
      module = "config",
      resourceType = "custom_role")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.CustomRole> retireCustomRole(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AccessEndInput body) {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return command(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        ROLES + "/" + id + "/retire",
        body,
        () -> service.retire(tenant, actor, id, expected, reason(body.getReason())));
  }

  @GetMapping("/me/custom-roles")
  @RequiresPermission(
      operationId = "listMyCustomRoles",
      module = "config",
      resourceType = "custom_role")
  public ListCustomRoles200Response listMyCustomRoles() {
    TenantId tenant = TenantContext.require();
    AccessActor actor = callers.accessActor();
    return new ListCustomRoles200Response(
        service.mine(tenant, actor.roles()).stream()
            .map(r -> toDto(new CustomRoleService.View(r, 0)))
            .toList());
  }

  // ------------------------------------------------------------------ hỗ trợ

  private ResponseEntity<vn.viettel.okrkpi.contract.config.model.CustomRole> command(
      TenantId tenant,
      AccessActor actor,
      String idempotencyKey,
      String method,
      String path,
      Object body,
      Supplier<CustomRoleService.View> work) {
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        method,
        path,
        body,
        vn.viettel.okrkpi.contract.config.model.CustomRole.class,
        vn.viettel.okrkpi.contract.config.model.CustomRole::getRowVersion,
        () -> {
          CustomRoleService.View v = notFoundAs404(work);
          return ResponseEntity.ok().eTag(ETags.of(v.role().rowVersion())).body(toDto(v));
        });
  }

  static vn.viettel.okrkpi.contract.config.model.CustomRole toDto(CustomRoleService.View v) {
    CustomRole r = v.role();
    List<CustomRolePermission> perms =
        r.permissions().entrySet().stream()
            .map(e -> new CustomRolePermission(e.getKey(), PermissionScope.fromValue(e.getValue())))
            .toList();
    vn.viettel.okrkpi.contract.config.model.CustomRole dto =
        new vn.viettel.okrkpi.contract.config.model.CustomRole(
            r.id(),
            r.code(),
            r.name(),
            RoleCode.fromValue(r.baseRole()),
            perms,
            CustomRoleStatus.fromValue(r.status().name()),
            v.holderCount(),
            r.rowVersion());
    dto.setDescription(r.description());
    dto.setCreatedAt(time(r.createdAt()));
    dto.setUpdatedAt(time(r.updatedAt()));
    return dto;
  }

  private static Map<String, String> permissions(List<CustomRolePermission> list) {
    Map<String, String> out = new LinkedHashMap<>();
    if (list != null) {
      for (CustomRolePermission p : list) {
        require(p.getKey(), "permissions.key");
        require(p.getScope(), "permissions.scope");
        out.put(p.getKey(), p.getScope().getValue());
      }
    }
    return out;
  }

  private static CustomRole.Status parseStatus(String value) {
    try {
      return CustomRole.Status.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw invalid("status", "Trạng thái không hợp lệ.");
    }
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

  private static <T> T notFoundAs404(Supplier<T> action) {
    try {
      return action.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
