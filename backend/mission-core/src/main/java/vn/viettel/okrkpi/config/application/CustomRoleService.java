package vn.viettel.okrkpi.config.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.application.port.AccessEvents;
import vn.viettel.okrkpi.config.application.port.CustomRoleRepository;
import vn.viettel.okrkpi.config.application.port.PermissionCatalogPort;
import vn.viettel.okrkpi.config.application.port.RealmRoles;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository;
import vn.viettel.okrkpi.config.application.port.TenantRolePublisher;
import vn.viettel.okrkpi.config.domain.AccessChange;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.config.domain.PermissionScopes;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Vai trò tuỳ chỉnh của tenant (CORE-08-18, BS-04 ADR-34 mục 4 và 5; SCR-0110; BR-01-23, BR-01-24).
 * Mỗi vai trò là một realm role Keycloak cùng mã và một mục trong {@code data.tenant_roles} của
 * OPA; định nghĩa đẩy sang OPA ngay sau khi giao dịch ghi thành công, lượt định kỳ ({@link
 * #publishAll}) phủ lại khi OPA khởi động lại.
 */
public class CustomRoleService {

  private static final Logger log = LoggerFactory.getLogger(CustomRoleService.class);
  static final String TX = RoleBindingService.TX;
  static final String RETIRED_REASON = "Vai trò đã ngừng sử dụng";
  private static final int SLUG_MAX = CustomRole.MAX_CODE - CustomRole.PREFIX.length() - 8 - 1 - 3;

  private final CustomRoleRepository roles;
  private final RoleBindingRepository bindings;
  private final PermissionCatalogPort catalog;
  private final TenantRolePublisher publisher;
  private final RealmRoles realm;
  private final AccessEvents events;
  private final Clock clock;

  public CustomRoleService(
      CustomRoleRepository roles,
      RoleBindingRepository bindings,
      PermissionCatalogPort catalog,
      TenantRolePublisher publisher,
      RealmRoles realm,
      AccessEvents events,
      Clock clock) {
    this.roles = Objects.requireNonNull(roles, "roles");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.realm = realm;
    this.events = Objects.requireNonNull(events, "events");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Vai trò kèm số người đang giữ. */
  public record View(CustomRole role, int holderCount) {}

  @Transactional(value = TX, readOnly = true)
  public List<View> list(TenantId tenantId, AccessActor actor, CustomRole.Status status) {
    actor.requireAny(RoleBindingService.TENANT_ADMIN, RoleBindingService.INSPECTOR);
    Map<String, Integer> holders = roles.holderCounts(tenantId);
    return roles.list(tenantId, status).stream()
        .map(r -> new View(r, holders.getOrDefault(r.code(), 0)))
        .toList();
  }

  @Transactional(value = TX, readOnly = true)
  public View get(TenantId tenantId, AccessActor actor, UUID id) {
    actor.requireAny(RoleBindingService.TENANT_ADMIN, RoleBindingService.INSPECTOR);
    CustomRole r = require(tenantId, id);
    return new View(r, roles.holderCounts(tenantId).getOrDefault(r.code(), 0));
  }

  /**
   * Định nghĩa các vai trò tuỳ chỉnh đang hiệu lực mà người gọi giữ ({@code roles} lấy từ token).
   */
  @Transactional(value = TX, readOnly = true)
  public List<CustomRole> mine(TenantId tenantId, List<String> heldRoles) {
    Set<String> held = Set.copyOf(heldRoles);
    return roles.list(tenantId, CustomRole.Status.HIEU_LUC).stream()
        .filter(r -> held.contains(r.code()))
        .toList();
  }

  @Transactional(TX)
  public View create(
      TenantId tenantId,
      AccessActor actor,
      String name,
      String description,
      String baseRole,
      Map<String, String> permissions) {
    actor.requireAny(RoleBindingService.TENANT_ADMIN);
    actor.requireStepUp("createCustomRole", clock);
    if (!CustomRole.BASE_ROLES.contains(baseRole)) {
      throw RoleBindingService.invalid(
          "baseRole", "Vai trò gốc không dùng được cho vai trò tuỳ chỉnh.");
    }
    String cleanName = name(name);
    if (roles.nameTaken(tenantId, cleanName, null)) {
      throw nameTaken();
    }
    Map<String, String> perms = validate(baseRole, permissions);
    Instant now = clock.instant();
    CustomRole r =
        new CustomRole(
            Ids.newUuidV7(),
            code(tenantId, cleanName),
            cleanName,
            blankToNull(description),
            baseRole,
            perms,
            CustomRole.Status.HIEU_LUC,
            now,
            now,
            null,
            0);
    roles.insert(tenantId, r, actor.userId());
    if (realm != null) {
      // Realm role phải có trước khi cấp cho người dùng; lỗi Keycloak huỷ cả lần tạo.
      realm.ensure(r.code(), cleanName);
    }
    events.customRoleChanged(tenantId, "TAO_VAI_TRO_TUY_CHINH", r.id(), 0, actor.userId());
    publishAfterCommit(tenantId);
    return new View(r, 0);
  }

  @Transactional(TX)
  public View update(
      TenantId tenantId,
      AccessActor actor,
      UUID id,
      Long expectedVersion,
      String name,
      String description,
      Map<String, String> permissions) {
    actor.requireAny(RoleBindingService.TENANT_ADMIN);
    actor.requireStepUp("updateCustomRole", clock);
    CustomRole r = require(tenantId, id);
    if (!r.active()) {
      throw retired();
    }
    checkVersion(r, expectedVersion);
    String cleanName = name(name);
    if (roles.nameTaken(tenantId, cleanName, id)) {
      throw nameTaken();
    }
    CustomRole saved =
        r.withContent(
            cleanName,
            blankToNull(description),
            validate(r.baseRole(), permissions),
            clock.instant());
    if (!roles.update(tenantId, saved, r.rowVersion(), actor.userId(), null)) {
      throw conflict();
    }
    events.customRoleChanged(
        tenantId, "SUA_VAI_TRO_TUY_CHINH", id, r.rowVersion() + 1, actor.userId());
    publishAfterCommit(tenantId);
    CustomRole after = require(tenantId, id);
    return new View(after, roles.holderCounts(tenantId).getOrDefault(after.code(), 0));
  }

  /** BR-01-24: ngừng sử dụng và thu hồi mọi bản ghi cấp còn mở với lý do cố định. */
  @Transactional(TX)
  public View retire(
      TenantId tenantId, AccessActor actor, UUID id, Long expectedVersion, String reason) {
    actor.requireAny(RoleBindingService.TENANT_ADMIN);
    actor.requireStepUp("retireCustomRole", clock);
    CustomRole r = require(tenantId, id);
    if (!r.active()) {
      throw retired();
    }
    checkVersion(r, expectedVersion);
    Instant now = clock.instant();
    if (!roles.update(tenantId, r.retired(now), r.rowVersion(), actor.userId(), reason)) {
      throw conflict();
    }
    for (RoleBinding b : openBindings(tenantId, r.code(), now)) {
      RoleBinding ended =
          b.endedBy(actor.userId(), now, RoleBinding.EndKind.THU_HOI, RETIRED_REASON);
      if (bindings.update(tenantId, ended, b.rowVersion(), actor.userId())) {
        events.roleChanged(tenantId, AccessChange.THU_HOI_VAI_TRO, ended, actor.userId(), now);
      }
    }
    events.customRoleChanged(
        tenantId, "NGUNG_VAI_TRO_TUY_CHINH", id, r.rowVersion() + 1, actor.userId());
    publishAfterCommit(tenantId);
    return new View(require(tenantId, id), 0);
  }

  /**
   * Đẩy lại định nghĩa của một tenant (khởi động, định kỳ). Gọi trong giao dịch có ngữ cảnh tenant.
   */
  @Transactional(value = TX, readOnly = true)
  public void publishAll(TenantId tenantId) {
    publisher.publish(tenantId, roles.list(tenantId, null));
  }

  // ------------------------------------------------------------------ kiểm tra

  /** BR-01-23: mỗi chức năng phải có ở vai trò gốc và phạm vi không rộng hơn. */
  Map<String, String> validate(String baseRole, Map<String, String> permissions) {
    if (permissions == null || permissions.isEmpty()) {
      throw RoleBindingService.invalid("permissions", "Vai trò cần ít nhất một quyền.");
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : permissions.entrySet()) {
      String key = e.getKey();
      String scope = e.getValue();
      var base = catalog.scopeOf(key, baseRole);
      if (base.isEmpty()) {
        errors.add(
            new ProblemFieldError(
                "permissions." + key, "Vai trò gốc không có quyền này.", "BR-01-23"));
      } else if (!PermissionScopes.known(scope) || !PermissionScopes.within(scope, base.get())) {
        errors.add(
            new ProblemFieldError(
                "permissions." + key, "Phạm vi rộng hơn phạm vi của vai trò gốc.", "BR-01-23"));
      } else {
        out.put(key, scope);
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "CONFIG-422-CUSTOM-ROLE-WIDER",
          "Vai trò tuỳ chỉnh chỉ được thu hẹp quyền của vai trò gốc.",
          errors);
    }
    return out;
  }

  private String code(TenantId tenantId, String name) {
    String prefix =
        CustomRole.PREFIX + tenantId.value().toString().replace("-", "").substring(0, 8) + "_";
    String base = prefix + PermissionScopes.slug(name, SLUG_MAX);
    String code = base;
    for (int i = 2; roles.codeTaken(code); i++) {
      code = base + "_" + i;
    }
    return code;
  }

  private List<RoleBinding> openBindings(TenantId tenantId, String code, Instant now) {
    List<RoleBinding> out = new ArrayList<>();
    for (RoleBinding.Status s :
        List.of(RoleBinding.Status.HIEU_LUC, RoleBinding.Status.SAP_HIEU_LUC)) {
      out.addAll(
          bindings.list(
              tenantId,
              new RoleBindingRepository.Filter(List.of(), null, code, s, null),
              now,
              null,
              10_000));
    }
    return out;
  }

  private void publishAfterCommit(TenantId tenantId) {
    List<CustomRole> snapshot = roles.list(tenantId, null);
    Runnable push =
        () -> {
          try {
            publisher.publish(tenantId, snapshot);
          } catch (RuntimeException e) {
            log.warn(
                "Chưa đẩy được vai trò tuỳ chỉnh của tenant {} sang OPA: {}",
                tenantId,
                e.getMessage());
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              push.run();
            }
          });
    } else {
      push.run();
    }
  }

  private CustomRole require(TenantId tenantId, UUID id) {
    return roles.find(tenantId, id).orElseThrow(NoSuchElementException::new);
  }

  private static void checkVersion(CustomRole r, Long expected) {
    if (expected != null && expected != r.rowVersion()) {
      throw new VersionConflictException("CONFIG", expected, (long) r.rowVersion());
    }
  }

  private static String name(String name) {
    String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
    if (n.length() < 3 || n.length() > 120) {
      throw RoleBindingService.invalid("name", "Tên vai trò dài từ 3 đến 120 ký tự.");
    }
    return n;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static BusinessException nameTaken() {
    return new BusinessException(
        "CONFIG-409-CUSTOM-ROLE-NAME", "Đã có vai trò cùng tên. Vui lòng chọn tên khác.");
  }

  private static BusinessException retired() {
    return new BusinessException("CONFIG-409-CUSTOM-ROLE-RETIRED", "Vai trò này đã ngừng sử dụng.");
  }

  private static VersionConflictException conflict() {
    return new VersionConflictException("CONFIG", null, null);
  }
}
