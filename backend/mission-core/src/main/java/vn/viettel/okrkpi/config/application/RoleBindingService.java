package vn.viettel.okrkpi.config.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.application.port.AccessEvents;
import vn.viettel.okrkpi.config.application.port.CustomRoleRepository;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository.Counts;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository.Filter;
import vn.viettel.okrkpi.config.domain.AccessChange;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.config.domain.RoleBinding.EndKind;
import vn.viettel.okrkpi.config.domain.RoleBinding.Scope;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cấp, duyệt thứ hai, từ chối, thu hồi, tra cứu và hết hạn vai trò ({@code config.role_binding},
 * CORE-08-16, WEB-058 phương án A; SCR-0105, SCR-0106; BR-01-13 đến BR-01-16). Mỗi thay đổi ghi
 * EVT-27 qua outbox trong cùng giao dịch để dữ liệu OPA và realm role Keycloak cập nhật.
 */
public class RoleBindingService {

  static final String TX = "configTransactionManager";
  static final String TENANT_ADMIN = "tenant_admin";
  static final String LEADER = "leader";
  static final String INSPECTOR = "inspector";

  private final RoleBindingRepository bindings;
  private final AccessEvents events;
  private final AccessDirectory directory;
  private final Clock clock;
  private final CustomRoleRepository customRoles;

  public RoleBindingService(
      RoleBindingRepository bindings, AccessEvents events, AccessDirectory directory, Clock clock) {
    this(bindings, events, directory, clock, null);
  }

  /**
   * {@code customRoles} khác null thì nhận cả vai trò tuỳ chỉnh đang hiệu lực của tenant
   * (CORE-08-18).
   */
  public RoleBindingService(
      RoleBindingRepository bindings,
      AccessEvents events,
      AccessDirectory directory,
      Clock clock,
      CustomRoleRepository customRoles) {
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.events = Objects.requireNonNull(events, "events");
    this.directory = directory;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.customRoles = customRoles;
  }

  /** Dữ liệu cấp vai trò ({@code RoleBindingCreate}). */
  public record Grant(
      UUID personId,
      String roleCode,
      UUID orgUnitId,
      Scope scope,
      String flow,
      Instant validFrom,
      Instant validTo,
      String reason) {}

  /** Một trang kèm số đếm thẻ lọc nhanh. */
  public record Page(List<RoleBinding> items, Counts counts) {}

  @Transactional(TX)
  public RoleBinding grant(TenantId tenantId, AccessActor actor, Grant g) {
    actor.requireAny(TENANT_ADMIN);
    actor.requireStepUp("grantRoleBinding", clock);
    if (!RoleBinding.ROLES.contains(g.roleCode()) && !activeCustomRole(tenantId, g.roleCode())) {
      throw invalid("roleCode", "Vai trò không hợp lệ.");
    }
    validity(g.validFrom(), g.validTo());
    if (g.personId().equals(actor.userId())) {
      throw selfGrant();
    }
    Instant now = clock.instant();
    bindings.lockKey(tenantId, g.personId(), g.roleCode(), g.orgUnitId(), g.scope().name());
    if (bindings.existsOpen(
        tenantId, g.personId(), g.roleCode(), g.orgUnitId(), g.scope().name(), now)) {
      throw new BusinessException(
          "CONFIG-409-DUPLICATE-BINDING",
          "Người này đã có vai trò này tại đơn vị và phạm vi đã chọn.");
    }
    RoleBinding b =
        new RoleBinding(
            Ids.newUuidV7(),
            g.personId(),
            g.roleCode(),
            g.orgUnitId(),
            g.scope(),
            g.flow(),
            g.validFrom(),
            g.validTo(),
            actor.userId(),
            now,
            g.reason(),
            null,
            null,
            null,
            null,
            null,
            null,
            0);
    bindings.insert(tenantId, b, actor.userId());
    events.roleChanged(tenantId, AccessChange.CAP_VAI_TRO, b, actor.userId(), now);
    return b;
  }

  @Transactional(TX)
  public RoleBinding approve(TenantId tenantId, AccessActor actor, UUID id, Long expectedVersion) {
    actor.requireAny(TENANT_ADMIN, LEADER);
    actor.requireStepUp("approveRoleBinding", clock);
    RoleBinding b = require(tenantId, id);
    checkApprover(tenantId, actor, b);
    if (!b.pending()) {
      throw notPending();
    }
    checkVersion(b, expectedVersion);
    Instant now = clock.instant();
    RoleBinding saved = b.approved(actor.userId(), now);
    save(tenantId, saved, b.rowVersion(), actor);
    events.roleChanged(tenantId, AccessChange.DUYET_VAI_TRO, saved, actor.userId(), now);
    return saved;
  }

  @Transactional(TX)
  public RoleBinding reject(
      TenantId tenantId, AccessActor actor, UUID id, Long expectedVersion, String reason) {
    actor.requireAny(TENANT_ADMIN, LEADER);
    actor.requireStepUp("rejectRoleBinding", clock);
    RoleBinding b = require(tenantId, id);
    checkApprover(tenantId, actor, b);
    if (!b.pending()) {
      throw notPending();
    }
    checkVersion(b, expectedVersion);
    Instant now = clock.instant();
    RoleBinding saved = b.endedBy(actor.userId(), now, EndKind.TU_CHOI, reason);
    save(tenantId, saved, b.rowVersion(), actor);
    events.roleChanged(tenantId, AccessChange.TU_CHOI_VAI_TRO, saved, actor.userId(), now);
    return saved;
  }

  @Transactional(TX)
  public RoleBinding revoke(
      TenantId tenantId, AccessActor actor, UUID id, Long expectedVersion, String reason) {
    actor.requireAny(TENANT_ADMIN);
    actor.requireStepUp("revokeRoleBinding", clock);
    RoleBinding b = require(tenantId, id);
    if (b.personId().equals(actor.userId())) {
      throw selfGrant();
    }
    Instant now = clock.instant();
    RoleBinding.Status status = b.status(now);
    if (b.ended() || status == RoleBinding.Status.HET_HAN) {
      throw new BusinessException("CONFIG-409-ALREADY-ENDED", "Vai trò này đã kết thúc.");
    }
    checkVersion(b, expectedVersion);
    RoleBinding saved = b.endedBy(actor.userId(), now, EndKind.THU_HOI, reason);
    save(tenantId, saved, b.rowVersion(), actor);
    events.roleChanged(tenantId, AccessChange.THU_HOI_VAI_TRO, saved, actor.userId(), now);
    return saved;
  }

  @Transactional(value = TX, readOnly = true)
  public RoleBinding get(TenantId tenantId, AccessActor actor, UUID id) {
    actor.requireAny(TENANT_ADMIN, LEADER, INSPECTOR);
    return require(tenantId, id);
  }

  /**
   * Danh sách theo đơn vị (kèm cấp dưới) hoặc theo người.
   *
   * @param fetch số bản ghi cần lấy (cỡ trang cộng một)
   */
  @Transactional(value = TX, readOnly = true)
  public Page list(
      TenantId tenantId,
      AccessActor actor,
      UUID orgUnitId,
      boolean includeSubunits,
      UUID personId,
      String roleCode,
      RoleBinding.Status status,
      boolean awaitingMe,
      UUID afterId,
      int fetch) {
    actor.requireAny(TENANT_ADMIN, LEADER, INSPECTOR);
    List<UUID> units = units(tenantId, orgUnitId, includeSubunits);
    Instant now = clock.instant();
    Filter filter =
        new Filter(units, personId, roleCode, status, awaitingMe ? actor.userId() : null);
    return new Page(
        bindings.list(tenantId, filter, now, afterId, fetch),
        bindings.counts(tenantId, units, actor.userId(), now));
  }

  /**
   * Phát EVT-27 {@code HET_HAN_VAI_TRO} cho bản ghi quá hạn; chạy lại không phát trùng.
   *
   * @return số bản ghi đã xử lý
   */
  @Transactional(TX)
  public int expireDue(TenantId tenantId, UUID systemActor, int limit) {
    Instant now = clock.instant();
    int done = 0;
    for (RoleBinding b : bindings.dueExpiry(tenantId, now, limit)) {
      if (bindings.markExpiryEmitted(tenantId, b.id(), now)) {
        events.roleChanged(tenantId, AccessChange.HET_HAN_VAI_TRO, b.expiredMarked(), null, now);
        done++;
      }
    }
    return done;
  }

  /** Vai trò đang hiệu lực của một người (bộ đồng bộ Keycloak). */
  @Transactional(value = TX, readOnly = true)
  public List<RoleBinding> effectiveOf(TenantId tenantId, UUID personId) {
    return bindings.effectiveOf(tenantId, personId, clock.instant());
  }

  private List<UUID> units(TenantId tenantId, UUID orgUnitId, boolean includeSubunits) {
    if (orgUnitId == null) {
      return List.of();
    }
    List<UUID> units = new ArrayList<>();
    units.add(orgUnitId);
    if (includeSubunits && directory != null) {
      units.addAll(directory.subunitsOf(tenantId, orgUnitId));
    }
    return units;
  }

  /**
   * Người duyệt thứ hai khác người cấp, khác người được cấp; là {@code tenant_admin} hoặc {@code
   * leader} đang có vai trò trên đơn vị phạm vi hay đơn vị cấp trên (quyết định WEB-058 điểm 3).
   */
  private void checkApprover(TenantId tenantId, AccessActor actor, RoleBinding b) {
    if (actor.userId().equals(b.grantedBy()) || actor.userId().equals(b.personId())) {
      throw selfGrant();
    }
    if (actor.has(TENANT_ADMIN)) {
      return;
    }
    List<UUID> units = new ArrayList<>();
    units.add(b.orgUnitId());
    if (directory != null) {
      units.addAll(directory.ancestorsOf(tenantId, b.orgUnitId()));
    }
    if (!bindings.holds(tenantId, actor.userId(), LEADER, units, clock.instant())) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có thẩm quyền duyệt tại đơn vị này.");
    }
  }

  private RoleBinding require(TenantId tenantId, UUID id) {
    return bindings.find(tenantId, id).orElseThrow(NoSuchElementException::new);
  }

  private void save(TenantId tenantId, RoleBinding saved, int expected, AccessActor actor) {
    if (!bindings.update(tenantId, saved, expected, actor.userId())) {
      throw new VersionConflictException("CONFIG", (long) expected, null);
    }
  }

  static void checkVersion(RoleBinding b, Long expected) {
    if (expected != null && expected != b.rowVersion()) {
      throw new VersionConflictException("CONFIG", expected, (long) b.rowVersion());
    }
  }

  static void validity(Instant from, Instant to) {
    if (from == null || to == null || !to.isAfter(from)) {
      throw new BusinessException(
          "CONFIG-422-VALIDITY",
          "Thời hạn kết thúc phải sau thời điểm bắt đầu.",
          List.of(
              new ProblemFieldError(
                  "validTo", "Thời hạn kết thúc phải sau thời điểm bắt đầu.", null)));
    }
  }

  static BusinessException selfGrant() {
    return new BusinessException(
        "CONFIG-403-SELF-GRANT",
        "Không được tự cấp, tự duyệt hay tự thu hồi quyền của chính mình.");
  }

  private static BusinessException notPending() {
    return new BusinessException("CONFIG-409-NOT-PENDING", "Đề nghị này không còn chờ duyệt.");
  }

  static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private boolean activeCustomRole(TenantId tenantId, String code) {
    return customRoles != null
        && CustomRole.isCustomCode(code)
        && customRoles.findByCode(tenantId, code).map(CustomRole::active).orElse(false);
  }
}
