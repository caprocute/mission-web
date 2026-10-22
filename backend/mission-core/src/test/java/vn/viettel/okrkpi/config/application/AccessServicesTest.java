package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.DelegationApi;
import vn.viettel.okrkpi.config.application.port.AccessEvents;
import vn.viettel.okrkpi.config.application.port.DelegationRepository;
import vn.viettel.okrkpi.config.application.port.RealmRoles;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository;
import vn.viettel.okrkpi.config.domain.AccessChange;
import vn.viettel.okrkpi.config.domain.Delegation;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-16: quy tắc cấp, duyệt, thu hồi vai trò, uỷ quyền, lịch sử và đồng bộ Keycloak. */
class AccessServicesTest {

  private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
  private static final TenantId T = TenantId.of(UUID.randomUUID());
  private static final UUID ADMIN = UUID.randomUUID();
  private static final UUID ADMIN2 = UUID.randomUUID();
  private static final UUID LEADER = UUID.randomUUID();
  private static final UUID PERSON = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID PARENT = UUID.randomUUID();

  private RoleBindingRepository bindings;
  private DelegationRepository delegations;
  private AccessEvents events;
  private AccessDirectory directory;
  private RoleBindingService roles;
  private DelegationService delegationService;
  private final Clock clock = Clock.fixed(NOW);

  @BeforeEach
  void setUp() {
    bindings = mock(RoleBindingRepository.class);
    delegations = mock(DelegationRepository.class);
    events = mock(AccessEvents.class);
    directory = mock(AccessDirectory.class);
    when(directory.ancestorsOf(T, UNIT)).thenReturn(List.of(PARENT));
    when(directory.subunitsOf(T, PARENT)).thenReturn(List.of(UNIT));
    when(bindings.update(any(), any(), anyInt(), any())).thenReturn(true);
    when(delegations.update(any(), any(), anyInt(), any())).thenReturn(true);
    roles = new RoleBindingService(bindings, events, directory, clock);
    delegationService = new DelegationService(delegations, bindings, events, directory, clock);
  }

  private static AccessActor actor(UUID id, String... roles) {
    return new AccessActor(id, List.of(roles), "aal2", NOW.getEpochSecond() - 60);
  }

  private static RoleBindingService.Grant grant(UUID person, String role) {
    return new RoleBindingService.Grant(
        person,
        role,
        UNIT,
        RoleBinding.Scope.DON_VI,
        null,
        NOW.minus(1, ChronoUnit.DAYS),
        NOW.plus(100, ChronoUnit.DAYS),
        "Phân công");
  }

  private static RoleBinding binding(String role, UUID approvedBy) {
    return new RoleBinding(
        UUID.randomUUID(),
        PERSON,
        role,
        UNIT,
        RoleBinding.Scope.DON_VI,
        "DANG",
        NOW.minus(1, ChronoUnit.DAYS),
        NOW.plus(100, ChronoUnit.DAYS),
        ADMIN,
        NOW.minus(1, ChronoUnit.DAYS),
        "Phân công",
        approvedBy,
        approvedBy == null ? null : NOW,
        null,
        null,
        null,
        null,
        0);
  }

  private static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  // ---- AccessActor ----

  @Test
  void actorChecksRolesAndStepUpWindow() {
    AccessActor ok = actor(ADMIN, "tenant_admin");
    ok.requireAny("leader", "tenant_admin");
    ok.requireStepUp("op", clock);
    assertThatThrownBy(() -> ok.requireAny("leader"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(
            () ->
                new AccessActor(ADMIN, null, "aal1", NOW.getEpochSecond())
                    .requireStepUp("op", clock))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () -> new AccessActor(ADMIN, List.of(), "aal2", null).requireStepUp("op", clock))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                new AccessActor(ADMIN, List.of(), "aal2", NOW.getEpochSecond() - 16 * 60)
                    .requireStepUp("op", clock))
        .isInstanceOf(StepUpRequiredException.class);
    assertThat(new AccessActor(ADMIN, null, null, null).roles()).isEmpty();
  }

  // ---- Vai trò ----

  @Test
  void grantValidatesAndEmits() {
    RoleBinding b = roles.grant(T, actor(ADMIN, "tenant_admin"), grant(PERSON, "decider"));
    assertThat(b.pending()).isTrue();
    assertThat(b.status(NOW)).isEqualTo(RoleBinding.Status.CHO_DUYET);
    verify(bindings).insert(eq(T), any(), eq(ADMIN));
    verify(events).roleChanged(eq(T), eq(AccessChange.CAP_VAI_TRO), any(), eq(ADMIN), eq(NOW));

    AccessActor admin = actor(ADMIN, "tenant_admin");
    assertThatThrownBy(() -> roles.grant(T, actor(ADMIN, "leader"), grant(PERSON, "member")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> roles.grant(T, admin, grant(PERSON, "khac")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> roles.grant(T, admin, grant(ADMIN, "member")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-403-SELF-GRANT"));
    RoleBindingService.Grant backwards =
        new RoleBindingService.Grant(
            PERSON, "member", UNIT, RoleBinding.Scope.DON_VI, null, NOW, NOW, "x");
    assertThatThrownBy(() -> roles.grant(T, admin, backwards))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-422-VALIDITY"));
    when(bindings.existsOpen(any(), any(), anyString(), any(), anyString(), any()))
        .thenReturn(true);
    assertThatThrownBy(() -> roles.grant(T, admin, grant(PERSON, "member")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-DUPLICATE-BINDING"));
  }

  @Test
  void approveNeedsSecondPersonInScope() {
    RoleBinding pending = binding("decider", null);
    when(bindings.find(T, pending.id())).thenReturn(Optional.of(pending));

    assertThatThrownBy(() -> roles.approve(T, actor(ADMIN, "tenant_admin"), pending.id(), 0L))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-403-SELF-GRANT"));
    assertThatThrownBy(() -> roles.approve(T, actor(PERSON, "tenant_admin"), pending.id(), 0L))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-403-SELF-GRANT"));
    assertThatThrownBy(() -> roles.approve(T, actor(LEADER, "leader"), pending.id(), 0L))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> roles.approve(T, actor(ADMIN2, "tenant_admin"), pending.id(), 5L))
        .isInstanceOf(VersionConflictException.class);

    when(bindings.holds(eq(T), eq(LEADER), eq("leader"), eq(List.of(UNIT, PARENT)), eq(NOW)))
        .thenReturn(true);
    RoleBinding approved = roles.approve(T, actor(LEADER, "leader"), pending.id(), 0L);
    assertThat(approved.status(NOW)).isEqualTo(RoleBinding.Status.HIEU_LUC);
    assertThat(approved.rowVersion()).isEqualTo(1);
    verify(events).roleChanged(eq(T), eq(AccessChange.DUYET_VAI_TRO), any(), eq(LEADER), eq(NOW));

    when(bindings.find(T, pending.id())).thenReturn(Optional.of(approved));
    assertThatThrownBy(() -> roles.approve(T, actor(ADMIN2, "tenant_admin"), pending.id(), 1L))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-NOT-PENDING"));
    assertThatThrownBy(
            () -> roles.reject(T, actor(ADMIN2, "tenant_admin"), pending.id(), 1L, "Không phù hợp"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-NOT-PENDING"));
  }

  @Test
  void rejectRevokeAndConflicts() {
    RoleBinding pending = binding("policy_author", null);
    when(bindings.find(T, pending.id())).thenReturn(Optional.of(pending));
    RoleBinding rejected =
        roles.reject(T, actor(ADMIN2, "tenant_admin"), pending.id(), 0L, "Không phù hợp");
    assertThat(rejected.status(NOW)).isEqualTo(RoleBinding.Status.TU_CHOI);

    RoleBinding active = binding("member", null);
    when(bindings.find(T, active.id())).thenReturn(Optional.of(active));
    assertThatThrownBy(() -> roles.revoke(T, actor(PERSON, "tenant_admin"), active.id(), 0L, "x"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-403-SELF-GRANT"));
    RoleBinding revoked =
        roles.revoke(T, actor(ADMIN, "tenant_admin"), active.id(), null, "Điều chuyển");
    assertThat(revoked.status(NOW)).isEqualTo(RoleBinding.Status.DA_THU_HOI);
    assertThat(revoked.validTo()).isEqualTo(NOW);

    when(bindings.find(T, active.id())).thenReturn(Optional.of(revoked));
    assertThatThrownBy(() -> roles.revoke(T, actor(ADMIN, "tenant_admin"), active.id(), 1L, "x"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-ALREADY-ENDED"));

    RoleBinding other = binding("member", null);
    when(bindings.find(T, other.id())).thenReturn(Optional.of(other));
    when(bindings.update(eq(T), any(), eq(0), any())).thenReturn(false);
    assertThatThrownBy(() -> roles.revoke(T, actor(ADMIN, "tenant_admin"), other.id(), 0L, "x"))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> roles.get(T, actor(ADMIN, "tenant_admin"), UUID.randomUUID()))
        .isInstanceOf(java.util.NoSuchElementException.class);
    assertThat(roles.get(T, actor(ADMIN, "inspector"), other.id())).isEqualTo(other);
  }

  @Test
  void revokeBeforeStartKeepsValidRange() {
    RoleBinding future =
        new RoleBinding(
            UUID.randomUUID(),
            PERSON,
            "member",
            UNIT,
            RoleBinding.Scope.DON_VI,
            null,
            NOW.plus(1, ChronoUnit.DAYS),
            NOW.plus(2, ChronoUnit.DAYS),
            ADMIN,
            NOW,
            "r",
            null,
            null,
            null,
            null,
            null,
            null,
            0);
    assertThat(future.status(NOW)).isEqualTo(RoleBinding.Status.SAP_HIEU_LUC);
    RoleBinding ended = future.endedBy(ADMIN, NOW, RoleBinding.EndKind.THU_HOI, "x");
    assertThat(ended.validTo()).isAfter(ended.validFrom());
    RoleBinding expired =
        new RoleBinding(
            UUID.randomUUID(),
            PERSON,
            "member",
            UNIT,
            RoleBinding.Scope.DON_VI,
            null,
            NOW.minus(2, ChronoUnit.DAYS),
            NOW.minus(1, ChronoUnit.DAYS),
            ADMIN,
            NOW,
            "r",
            null,
            null,
            null,
            null,
            null,
            null,
            3);
    assertThat(expired.status(NOW)).isEqualTo(RoleBinding.Status.HET_HAN);
    assertThat(expired.effective(NOW)).isFalse();
    assertThat(expired.expiredMarked().rowVersion()).isEqualTo(4);
    when(bindings.find(T, expired.id())).thenReturn(Optional.of(expired));
    assertThatThrownBy(() -> roles.revoke(T, actor(ADMIN, "tenant_admin"), expired.id(), 3L, "x"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-ALREADY-ENDED"));
  }

  @Test
  void listCountsExpireAndEffective() {
    RoleBindingRepository.Counts counts = new RoleBindingRepository.Counts(1, 2, 0, 1);
    when(bindings.counts(eq(T), eq(List.of(PARENT, UNIT)), eq(ADMIN), eq(NOW))).thenReturn(counts);
    RoleBindingService.Page page =
        roles.list(T, actor(ADMIN, "tenant_admin"), PARENT, true, null, null, null, true, null, 21);
    assertThat(page.counts()).isEqualTo(counts);
    RoleBindingService.Page all =
        roles.list(
            T,
            actor(ADMIN, "leader"),
            null,
            false,
            PERSON,
            "member",
            RoleBinding.Status.HIEU_LUC,
            false,
            null,
            21);
    assertThat(all.items()).isEmpty();

    RoleBinding a = binding("member", null);
    RoleBinding b = binding("leader", null);
    when(bindings.dueExpiry(T, NOW, 10)).thenReturn(List.of(a, b));
    when(bindings.markExpiryEmitted(T, a.id(), NOW)).thenReturn(true);
    when(bindings.markExpiryEmitted(T, b.id(), NOW)).thenReturn(false);
    assertThat(roles.expireDue(T, null, 10)).isEqualTo(1);
    verify(events).roleChanged(eq(T), eq(AccessChange.HET_HAN_VAI_TRO), any(), eq(null), eq(NOW));
    when(bindings.effectiveOf(T, PERSON, NOW)).thenReturn(List.of(a));
    assertThat(roles.effectiveOf(T, PERSON)).containsExactly(a);
  }

  // ---- Uỷ quyền ----

  private static DelegationService.Declare declare(
      UUID delegator, UUID delegate, UUID subject, Delegation.Kind kind) {
    return new DelegationService.Declare(
        kind,
        delegator,
        delegate,
        UNIT,
        subject,
        NOW.minus(1, ChronoUnit.HOURS),
        NOW.plus(7, ChronoUnit.DAYS),
        "Đi công tác");
  }

  @Test
  void delegationRules() {
    AccessActor leader = actor(LEADER, "leader");
    assertThatThrownBy(
            () ->
                delegationService.create(
                    T, leader, declare(LEADER, PERSON, null, Delegation.Kind.DUYET_THAY)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    when(bindings.holds(eq(T), eq(LEADER), eq("leader"), any(), eq(NOW))).thenReturn(true);

    Delegation d =
        delegationService.create(
            T, leader, declare(LEADER, PERSON, null, Delegation.Kind.DUYET_THAY));
    assertThat(d.status(NOW)).isEqualTo(Delegation.Status.HIEU_LUC);
    verify(events)
        .delegationChanged(eq(T), eq(AccessChange.TAO_UY_QUYEN), any(), eq(LEADER), eq(NOW));

    assertThatThrownBy(
            () ->
                delegationService.create(
                    T, leader, declare(PERSON, PERSON, null, Delegation.Kind.DUYET_THAY)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-403-SELF-GRANT"));
    assertThatThrownBy(
            () ->
                delegationService.create(
                    T, leader, declare(PERSON, LEADER, null, Delegation.Kind.DUYET_THAY)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-403-SELF-GRANT"));
    assertThatThrownBy(
            () ->
                delegationService.create(
                    T,
                    leader,
                    declare(LEADER, PERSON, UUID.randomUUID(), Delegation.Kind.DUYET_THAY)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                delegationService.create(
                    T,
                    actor(ADMIN, "tenant_admin"),
                    declare(LEADER, PERSON, null, Delegation.Kind.DUYET_THAY)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));

    when(delegations.receivedDuring(
            eq(T), eq(Delegation.Kind.NGHIEM_THU_THAY), eq(ADMIN2), any(), any()))
        .thenReturn(List.of(d));
    assertThatThrownBy(
            () ->
                delegationService.create(
                    T, leader, declare(ADMIN2, PERSON, null, Delegation.Kind.NGHIEM_THU_THAY)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-422-REDELEGATION"));
    when(delegations.overlapping(
            eq(T), eq(Delegation.Kind.CHECK_IN_THAY), any(), any(), any(), any()))
        .thenReturn(List.of(d));
    assertThatThrownBy(
            () ->
                delegationService.create(
                    T,
                    leader,
                    declare(LEADER, PERSON, UUID.randomUUID(), Delegation.Kind.CHECK_IN_THAY)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-DUPLICATE-BINDING"));

    when(delegations.find(T, d.id())).thenReturn(Optional.of(d));
    assertThatThrownBy(() -> delegationService.revoke(T, leader, d.id(), 4L, "Về sớm"))
        .isInstanceOf(VersionConflictException.class);
    Delegation revoked = delegationService.revoke(T, leader, d.id(), 0L, "Về sớm");
    assertThat(revoked.status(NOW)).isEqualTo(Delegation.Status.DA_THU_HOI);
    when(delegations.find(T, d.id())).thenReturn(Optional.of(revoked));
    assertThatThrownBy(() -> delegationService.revoke(T, leader, d.id(), 1L, "x"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-ALREADY-ENDED"));
    when(delegations.find(T, d.id())).thenReturn(Optional.of(d));
    when(delegations.update(eq(T), any(), eq(0), any())).thenReturn(false);
    assertThatThrownBy(() -> delegationService.revoke(T, leader, d.id(), null, "x"))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void delegationQueriesAndExpiry() {
    Delegation active =
        delegation(null, NOW.minus(1, ChronoUnit.HOURS), NOW.plus(1, ChronoUnit.DAYS));
    Delegation future =
        delegation(null, NOW.plus(1, ChronoUnit.DAYS), NOW.plus(2, ChronoUnit.DAYS));
    assertThat(future.status(NOW)).isEqualTo(Delegation.Status.SAP_HIEU_LUC);
    when(delegations.overlapping(
            eq(T), eq(Delegation.Kind.DUYET_THAY), eq(LEADER), eq(PERSON), any(), any()))
        .thenReturn(List.of(future, active));
    assertThat(
            delegationService.activeFor(
                T, DelegationApi.Kind.DUYET_THAY, LEADER, PERSON, UNIT, null, NOW))
        .get()
        .extracting(DelegationApi.DelegationGrant::id)
        .isEqualTo(active.id());
    assertThat(
            delegationService.activeFor(
                T, DelegationApi.Kind.DUYET_THAY, LEADER, PERSON, PARENT, null, NOW))
        .isEmpty();
    when(delegations.receivedDuring(
            eq(T), eq(Delegation.Kind.DUYET_THAY), eq(PERSON), any(), any()))
        .thenReturn(List.of(active, future));
    assertThat(delegationService.receivedBy(T, DelegationApi.Kind.DUYET_THAY, PERSON, NOW))
        .hasSize(1);

    assertThat(
            delegationService.list(
                T, actor(ADMIN, "inspector"), PARENT, null, null, null, null, 21))
        .isEmpty();
    verify(delegations)
        .list(
            eq(T),
            eq(new DelegationRepository.Filter(List.of(PARENT, UNIT), null, null, null)),
            eq(NOW),
            eq(null),
            eq(21));
    assertThat(
            delegationService.list(
                T,
                actor(ADMIN, "leader"),
                null,
                PERSON,
                Delegation.Kind.DUYET_THAY,
                Delegation.Status.HIEU_LUC,
                null,
                21))
        .isEmpty();

    Delegation old = delegation(null, NOW.minus(3, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS));
    assertThat(old.status(NOW)).isEqualTo(Delegation.Status.HET_HAN);
    assertThat(old.expiredMarked().rowVersion()).isEqualTo(1);
    when(delegations.dueExpiry(T, NOW, 5)).thenReturn(List.of(old));
    when(delegations.markExpiryEmitted(T, old.id(), NOW)).thenReturn(true, false);
    assertThat(delegationService.expireDue(T, 5)).isEqualTo(1);
    assertThat(delegationService.expireDue(T, 5)).isZero();
  }

  private static Delegation delegation(UUID subject, Instant from, Instant to) {
    return new Delegation(
        UUID.randomUUID(),
        Delegation.Kind.DUYET_THAY,
        LEADER,
        PERSON,
        UNIT,
        subject,
        from,
        to,
        "Đi công tác",
        LEADER,
        NOW.minus(5, ChronoUnit.DAYS),
        null,
        null,
        null,
        0);
  }

  // ---- Lịch sử ----

  @Test
  void historyTimelineCursorAndExport() {
    AccessHistoryService history =
        new AccessHistoryService(bindings, delegations, directory, clock);
    RoleBinding approved = binding("decider", ADMIN2);
    RoleBinding revoked =
        binding("member", null)
            .endedBy(ADMIN, NOW.minus(1, ChronoUnit.HOURS), RoleBinding.EndKind.THU_HOI, "x");
    RoleBinding rejected =
        binding("policy_author", null)
            .endedBy(ADMIN2, NOW.minus(2, ChronoUnit.HOURS), RoleBinding.EndKind.TU_CHOI, "x");
    RoleBinding expired =
        new RoleBinding(
            UUID.randomUUID(),
            PERSON,
            "leader",
            UNIT,
            RoleBinding.Scope.DON_VI,
            null,
            NOW.minus(3, ChronoUnit.DAYS),
            NOW.minus(2, ChronoUnit.DAYS),
            ADMIN,
            NOW.minus(3, ChronoUnit.DAYS),
            "r",
            null,
            null,
            null,
            null,
            null,
            null,
            0);
    Delegation ended =
        delegation(null, NOW.minus(1, ChronoUnit.DAYS), NOW.plus(1, ChronoUnit.DAYS))
            .revoked(LEADER, NOW.minus(30, ChronoUnit.MINUTES), "x");
    Delegation lapsed =
        delegation(null, NOW.minus(4, ChronoUnit.DAYS), NOW.minus(3, ChronoUnit.DAYS));
    when(bindings.history(eq(T), eq(PERSON), any(), anyInt()))
        .thenReturn(List.of(approved, revoked, rejected, expired));
    when(delegations.history(eq(T), eq(PERSON), any(), anyInt()))
        .thenReturn(List.of(ended, lapsed));
    when(directory.unitNames(eq(T), any())).thenReturn(Map.of(UNIT, "Phòng Tổ chức"));

    AccessActor admin = actor(ADMIN, "tenant_admin");
    List<AccessHistoryService.Entry> all = history.list(T, admin, PERSON, null, null, 100);
    // decider: cấp, duyệt; member: cấp, thu hồi; policy_author: cấp, từ chối; leader: cấp, hết hạn;
    // uỷ quyền: tạo, thu hồi; tạo, hết hạn.
    assertThat(all).hasSize(12);
    assertThat(all).isSortedAccordingTo((x, y) -> y.at().compareTo(x.at()));
    assertThat(all.get(0).summary()).contains("tại Phòng Tổ chức");
    assertThat(all)
        .extracting(AccessHistoryService.Entry::kind)
        .contains(
            AccessChange.HET_HAN_UY_QUYEN,
            AccessChange.HET_HAN_VAI_TRO,
            AccessChange.TU_CHOI_VAI_TRO);

    AccessHistoryService.Entry third = all.get(2);
    List<AccessHistoryService.Entry> rest =
        history.list(
            T, admin, PERSON, null, new AccessHistoryService.After(third.at(), third.id()), 100);
    assertThat(rest).hasSize(9).doesNotContain(third);
    assertThat(history.list(T, admin, null, UNIT, null, 3)).hasSize(0);
    assertThat(AccessHistoryService.entryId(approved.id(), AccessChange.CAP_VAI_TRO))
        .isEqualTo(AccessHistoryService.entryId(approved.id(), AccessChange.CAP_VAI_TRO));

    assertThatThrownBy(() -> history.list(T, admin, null, null, null, 10))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThat(history.export(T, admin, PERSON)).hasSize(12);
    assertThatThrownBy(() -> history.export(T, admin, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> history.export(T, actor(LEADER, "leader"), PERSON))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));

    AccessHistoryService noDirectory = new AccessHistoryService(bindings, delegations, null, clock);
    assertThat(noDirectory.list(T, admin, PERSON, UNIT, null, 100).get(0).summary())
        .doesNotContain(" tại ");
  }

  // ---- Đồng bộ Keycloak ----

  @Test
  void realmSyncAddsAndRemovesOnlyManagedRoles() {
    RealmRoles realm = mock(RealmRoles.class);
    RealmRoleSync sync = new RealmRoleSync(bindings, realm, clock);
    when(bindings.effectiveOf(T, PERSON, NOW))
        .thenReturn(List.of(binding("leader", null), binding("member", null)));
    when(realm.rolesOf(PERSON)).thenReturn(List.of("member", "inspector", "offline_access"));
    RealmRoleSync.Result r = sync.sync(T, PERSON);
    assertThat(r.added()).containsExactly("leader");
    assertThat(r.removed()).containsExactly("inspector");
    assertThat(r.changed()).isTrue();
    verify(realm).add(PERSON, "leader");
    verify(realm).remove(PERSON, "inspector");
    verify(realm, never()).remove(PERSON, "offline_access");

    UUID quiet = UUID.randomUUID();
    List<UUID> page1 = new ArrayList<>(List.of(PERSON, quiet));
    when(bindings.persons(T, null, 2)).thenReturn(page1);
    when(bindings.persons(T, quiet, 2)).thenReturn(List.of());
    when(realm.rolesOf(quiet)).thenReturn(List.of());
    when(bindings.effectiveOf(T, quiet, NOW)).thenReturn(List.of());
    assertThat(sync.reconcile(T, 2)).isEqualTo(1);
    assertThat(new RealmRoleSync.Result(java.util.Set.of(), java.util.Set.of()).changed())
        .isFalse();
  }
}
