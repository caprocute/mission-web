package vn.viettel.okrkpi.config.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.DelegationApi;
import vn.viettel.okrkpi.config.application.port.AccessEvents;
import vn.viettel.okrkpi.config.application.port.DelegationRepository;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository;
import vn.viettel.okrkpi.config.domain.AccessChange;
import vn.viettel.okrkpi.config.domain.Delegation;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Uỷ quyền khi vắng mặt ({@code config.delegation}, CORE-08-16, SCR-0107): lãnh đạo đơn vị khai báo
 * duyệt thay, nghiệm thu thay, check-in KR thay có thời hạn; thu hồi trước hạn; tác vụ hết hạn.
 * Công bố {@link DelegationApi} cho MOD-01, MOD-03 kiểm người làm thay (BR-01-13, BR-01-15 đến
 * BR-01-17).
 */
public class DelegationService implements DelegationApi {

  static final String TX = RoleBindingService.TX;

  private final DelegationRepository delegations;
  private final RoleBindingRepository bindings;
  private final AccessEvents events;
  private final AccessDirectory directory;
  private final Clock clock;

  public DelegationService(
      DelegationRepository delegations,
      RoleBindingRepository bindings,
      AccessEvents events,
      AccessDirectory directory,
      Clock clock) {
    this.delegations = Objects.requireNonNull(delegations, "delegations");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.events = Objects.requireNonNull(events, "events");
    this.directory = directory;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Dữ liệu khai báo ({@code DelegationCreate}). */
  public record Declare(
      Delegation.Kind kind,
      UUID delegatorId,
      UUID delegateId,
      UUID orgUnitId,
      UUID subjectId,
      Instant validFrom,
      Instant validTo,
      String reason) {}

  @Transactional(TX)
  public Delegation create(TenantId tenantId, AccessActor actor, Declare d) {
    actor.requireAny(RoleBindingService.LEADER);
    actor.requireStepUp("createDelegation", clock);
    RoleBindingService.validity(d.validFrom(), d.validTo());
    if (d.delegatorId().equals(d.delegateId()) || actor.userId().equals(d.delegateId())) {
      throw RoleBindingService.selfGrant();
    }
    if (d.subjectId() != null && d.kind() != Delegation.Kind.CHECK_IN_THAY) {
      throw RoleBindingService.invalid("subjectId", "Chỉ uỷ quyền check-in thay mới chọn KR.");
    }
    Instant now = clock.instant();
    requireLeaderOf(tenantId, actor, d.orgUnitId(), now);
    delegations.lockKey(tenantId, d.delegatorId(), d.kind());
    // BR-01-17: người uỷ quyền đang làm thay người khác trong khoảng này thì không uỷ quyền lại.
    if (!delegations
        .receivedDuring(tenantId, d.kind(), d.delegatorId(), d.validFrom(), d.validTo())
        .isEmpty()) {
      throw new BusinessException(
          "CONFIG-422-REDELEGATION",
          "Người uỷ quyền đang nhận uỷ quyền của người khác nên không được uỷ quyền lại.");
    }
    if (!delegations
        .overlapping(
            tenantId, d.kind(), d.delegatorId(), d.delegateId(), d.validFrom(), d.validTo())
        .isEmpty()) {
      throw new BusinessException(
          "CONFIG-409-DUPLICATE-BINDING",
          "Đã có uỷ quyền cùng loại cho người nhận trong thời gian này.");
    }
    Delegation created =
        new Delegation(
            Ids.newUuidV7(),
            d.kind(),
            d.delegatorId(),
            d.delegateId(),
            d.orgUnitId(),
            d.subjectId(),
            d.validFrom(),
            d.validTo(),
            d.reason(),
            actor.userId(),
            now,
            null,
            null,
            null,
            0);
    delegations.insert(tenantId, created, actor.userId());
    events.delegationChanged(tenantId, AccessChange.TAO_UY_QUYEN, created, actor.userId(), now);
    return created;
  }

  @Transactional(TX)
  public Delegation revoke(
      TenantId tenantId, AccessActor actor, UUID id, Long expectedVersion, String reason) {
    actor.requireAny(RoleBindingService.LEADER);
    actor.requireStepUp("revokeDelegation", clock);
    Delegation d = delegations.find(tenantId, id).orElseThrow(NoSuchElementException::new);
    Instant now = clock.instant();
    requireLeaderOf(tenantId, actor, d.orgUnitId(), now);
    if (d.ended() || d.status(now) == Delegation.Status.HET_HAN) {
      throw new BusinessException("CONFIG-409-ALREADY-ENDED", "Uỷ quyền này đã kết thúc.");
    }
    if (expectedVersion != null && expectedVersion != d.rowVersion()) {
      throw new VersionConflictException("CONFIG", expectedVersion, (long) d.rowVersion());
    }
    Delegation saved = d.revoked(actor.userId(), now, reason);
    if (!delegations.update(tenantId, saved, d.rowVersion(), actor.userId())) {
      throw new VersionConflictException("CONFIG", (long) d.rowVersion(), null);
    }
    events.delegationChanged(tenantId, AccessChange.THU_HOI_UY_QUYEN, saved, actor.userId(), now);
    return saved;
  }

  @Transactional(value = TX, readOnly = true)
  public List<Delegation> list(
      TenantId tenantId,
      AccessActor actor,
      UUID orgUnitId,
      UUID personId,
      Delegation.Kind kind,
      Delegation.Status status,
      UUID afterId,
      int fetch) {
    actor.requireAny(
        RoleBindingService.TENANT_ADMIN, RoleBindingService.LEADER, RoleBindingService.INSPECTOR);
    List<UUID> units = new ArrayList<>();
    if (orgUnitId != null) {
      units.add(orgUnitId);
      if (directory != null) {
        units.addAll(directory.subunitsOf(tenantId, orgUnitId));
      }
    }
    return delegations.list(
        tenantId,
        new DelegationRepository.Filter(units, personId, kind, status),
        clock.instant(),
        afterId,
        fetch);
  }

  /** Phát EVT-27 {@code HET_HAN_UY_QUYEN} cho uỷ quyền quá hạn; chạy lại không phát trùng. */
  @Transactional(TX)
  public int expireDue(TenantId tenantId, int limit) {
    Instant now = clock.instant();
    int done = 0;
    for (Delegation d : delegations.dueExpiry(tenantId, now, limit)) {
      if (delegations.markExpiryEmitted(tenantId, d.id(), now)) {
        events.delegationChanged(
            tenantId, AccessChange.HET_HAN_UY_QUYEN, d.expiredMarked(), null, now);
        done++;
      }
    }
    return done;
  }

  @Override
  @Transactional(value = TX, readOnly = true)
  public Optional<DelegationGrant> activeFor(
      TenantId tenant,
      Kind kind,
      UUID delegatorId,
      UUID delegateId,
      UUID orgUnitId,
      UUID subjectId,
      Instant at) {
    Instant end = at.plusMillis(1);
    return delegations
        .overlapping(tenant, Delegation.Kind.valueOf(kind.name()), delegatorId, delegateId, at, end)
        .stream()
        .filter(d -> d.effective(at))
        .filter(d -> orgUnitId == null || d.orgUnitId().equals(orgUnitId))
        .filter(d -> d.subjectId() == null || d.subjectId().equals(subjectId))
        .findFirst()
        .map(DelegationService::grant);
  }

  @Override
  @Transactional(value = TX, readOnly = true)
  public List<DelegationGrant> receivedBy(TenantId tenant, Kind kind, UUID delegateId, Instant at) {
    return delegations
        .receivedDuring(
            tenant, Delegation.Kind.valueOf(kind.name()), delegateId, at, at.plusMillis(1))
        .stream()
        .filter(d -> d.effective(at))
        .map(DelegationService::grant)
        .toList();
  }

  private static DelegationGrant grant(Delegation d) {
    return new DelegationGrant(
        d.id(),
        Kind.valueOf(d.kind().name()),
        d.delegatorId(),
        d.delegateId(),
        d.orgUnitId(),
        d.subjectId(),
        d.validFrom(),
        d.validTo());
  }

  /** Phạm vi {@code Cd}: lãnh đạo có vai trò {@code leader} tại đơn vị hoặc đơn vị cấp trên. */
  private void requireLeaderOf(TenantId tenantId, AccessActor actor, UUID unit, Instant now) {
    List<UUID> units = new ArrayList<>();
    units.add(unit);
    if (directory != null) {
      units.addAll(directory.ancestorsOf(tenantId, unit));
    }
    if (!bindings.holds(tenantId, actor.userId(), RoleBindingService.LEADER, units, now)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không phải lãnh đạo của đơn vị này.");
    }
  }
}
