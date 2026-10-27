package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.application.port.AccessEvents;
import vn.viettel.okrkpi.config.application.port.CustomRoleRepository;
import vn.viettel.okrkpi.config.application.port.RealmRoles;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository;
import vn.viettel.okrkpi.config.application.port.TenantRolePublisher;
import vn.viettel.okrkpi.config.domain.AccessChange;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.config.domain.PermissionScopes;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.config.infrastructure.authz.ClasspathPermissionCatalog;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * CORE-08-18: vai trò tuỳ chỉnh chỉ thu hẹp vai trò gốc (BR-01-23), ngừng thì thu hồi (BR-01-24).
 */
class CustomRoleServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
  private static final UUID TENANT_UUID = UUID.fromString("0a1b2c3d-0000-7000-8000-000000000001");
  private static final TenantId T = TenantId.of(TENANT_UUID);
  private static final UUID ADMIN = UUID.randomUUID();
  private static final UUID PERSON = UUID.randomUUID();

  private CustomRoleRepository repo;
  private RoleBindingRepository bindings;
  private TenantRolePublisher publisher;
  private RealmRoles realm;
  private AccessEvents events;
  private CustomRoleService service;
  private final Clock clock = Clock.fixed(NOW);

  @BeforeEach
  void setUp() {
    repo = mock(CustomRoleRepository.class);
    bindings = mock(RoleBindingRepository.class);
    publisher = mock(TenantRolePublisher.class);
    realm = mock(RealmRoles.class);
    events = mock(AccessEvents.class);
    when(repo.update(any(), any(), anyInt(), any(), any())).thenReturn(true);
    when(bindings.update(any(), any(), anyInt(), any())).thenReturn(true);
    service =
        new CustomRoleService(
            repo,
            bindings,
            new ClasspathPermissionCatalog(new ObjectMapper()),
            publisher,
            realm,
            events,
            clock);
  }

  private static AccessActor admin() {
    return new AccessActor(ADMIN, List.of("tenant_admin"), "aal2", NOW.getEpochSecond() - 60);
  }

  private static CustomRole role(CustomRole.Status status) {
    return new CustomRole(
        UUID.randomUUID(),
        "tc_0a1b2c3d_can_bo_xem",
        "Cán bộ chỉ xem",
        null,
        "member",
        Map.of("SCR-0201.xem", "B"),
        status,
        NOW,
        NOW,
        null,
        3);
  }

  private static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @Test
  void taoVaiTroThuHepSinhMaTheoTenantVaTaoRealmRole() {
    CustomRoleService.View v =
        service.create(
            T,
            admin(),
            "  Cán bộ chỉ xem ",
            "Chỉ xem mục tiêu",
            "member",
            Map.of("SCR-0201.xem", "B"));

    assertThat(v.role().code()).isEqualTo("tc_0a1b2c3d_can_bo_chi_xem");
    assertThat(v.role().code()).hasSizeLessThanOrEqualTo(CustomRole.MAX_CODE);
    assertThat(v.role().name()).isEqualTo("Cán bộ chỉ xem");
    verify(repo).insert(eq(T), any(), eq(ADMIN));
    verify(realm).ensure("tc_0a1b2c3d_can_bo_chi_xem", "Cán bộ chỉ xem");
    verify(events).customRoleChanged(eq(T), eq("TAO_VAI_TRO_TUY_CHINH"), any(), eq(0), eq(ADMIN));
    // Ngoài giao dịch thì đẩy OPA ngay.
    verify(publisher).publish(eq(T), any());
  }

  @Test
  void maTrungThiThemHauTo() {
    when(repo.codeTaken("tc_0a1b2c3d_can_bo_chi_xem")).thenReturn(true);
    CustomRoleService.View v =
        service.create(T, admin(), "Cán bộ chỉ xem", null, "member", Map.of("SCR-0201.xem", "Dv"));
    assertThat(v.role().code()).isEqualTo("tc_0a1b2c3d_can_bo_chi_xem_2");
  }

  @Test
  void khongDuocRongHonVaiTroGoc() {
    assertThatThrownBy(
            () ->
                service.create(
                    T, admin(), "Rộng hơn", null, "member", Map.of("SCR-0201.xem", "Tn")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-422-CUSTOM-ROLE-WIDER"));
    assertThatThrownBy(
            () ->
                service.create(T, admin(), "Không có", null, "member", Map.of("SCR-0101.xem", "B")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-422-CUSTOM-ROLE-WIDER"));
    verify(repo, never()).insert(any(), any(), any());
  }

  @Test
  void vaiTroGocChiGomNamVaiTroKhongNhayCam() {
    for (String base :
        List.of("tenant_admin", "platform_admin", "decider", "second_approver", "policy_author")) {
      assertThatThrownBy(
              () ->
                  service.create(
                      T, admin(), "Thử " + base, null, base, Map.of("SCR-0201.xem", "B")))
          .isInstanceOf(BusinessException.class);
    }
  }

  @Test
  void canQuanTriVaBuocLen() {
    AccessActor manager = new AccessActor(ADMIN, List.of("manager"), "aal2", NOW.getEpochSecond());
    assertThatThrownBy(
            () -> service.create(T, manager, "X", null, "member", Map.of("SCR-0201.xem", "B")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    AccessActor aal1 =
        new AccessActor(ADMIN, List.of("tenant_admin"), "aal1", NOW.getEpochSecond());
    assertThatThrownBy(
            () -> service.create(T, aal1, "X", null, "member", Map.of("SCR-0201.xem", "B")))
        .isInstanceOf(StepUpRequiredException.class);
  }

  @Test
  void tenTrungBiTuChoi() {
    when(repo.nameTaken(T, "Cán bộ chỉ xem", null)).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.create(
                    T, admin(), "Cán bộ chỉ xem", null, "member", Map.of("SCR-0201.xem", "B")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-CUSTOM-ROLE-NAME"));
  }

  @Test
  void suaKiemPhienBanVaKhongSuaVaiTroDaNgung() {
    CustomRole r = role(CustomRole.Status.HIEU_LUC);
    when(repo.find(T, r.id())).thenReturn(Optional.of(r));
    assertThatThrownBy(
            () ->
                service.update(T, admin(), r.id(), 2L, r.name(), null, Map.of("SCR-0201.xem", "B")))
        .isInstanceOf(VersionConflictException.class);

    CustomRole off = role(CustomRole.Status.NGUNG);
    when(repo.find(T, off.id())).thenReturn(Optional.of(off));
    assertThatThrownBy(
            () ->
                service.update(
                    T, admin(), off.id(), 3L, off.name(), null, Map.of("SCR-0201.xem", "B")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("CONFIG-409-CUSTOM-ROLE-RETIRED"));
  }

  @Test
  void ngungVaiTroThuHoiBanGhiCapVaPhatEvt27() {
    CustomRole r = role(CustomRole.Status.HIEU_LUC);
    when(repo.find(T, r.id())).thenReturn(Optional.of(r));
    RoleBinding open =
        new RoleBinding(
            UUID.randomUUID(),
            PERSON,
            r.code(),
            UUID.randomUUID(),
            RoleBinding.Scope.DON_VI,
            null,
            NOW.minus(1, ChronoUnit.DAYS),
            NOW.plus(30, ChronoUnit.DAYS),
            ADMIN,
            NOW.minus(1, ChronoUnit.DAYS),
            "Phân công",
            null,
            null,
            null,
            null,
            null,
            null,
            0);
    when(bindings.list(eq(T), any(), any(), any(), anyInt()))
        .thenReturn(List.of(open))
        .thenReturn(List.of());

    service.retire(T, admin(), r.id(), 3L, "Không còn dùng");

    ArgumentCaptor<RoleBinding> ended = ArgumentCaptor.forClass(RoleBinding.class);
    verify(bindings).update(eq(T), ended.capture(), eq(0), eq(ADMIN));
    assertThat(ended.getValue().endKind()).isEqualTo(RoleBinding.EndKind.THU_HOI);
    verify(events).roleChanged(eq(T), eq(AccessChange.THU_HOI_VAI_TRO), any(), eq(ADMIN), eq(NOW));
    verify(repo).update(eq(T), any(), eq(3), eq(ADMIN), eq("Không còn dùng"));
    verify(events)
        .customRoleChanged(eq(T), eq("NGUNG_VAI_TRO_TUY_CHINH"), eq(r.id()), eq(4), eq(ADMIN));
  }

  @Test
  void vaiTroCuaToiChiLayVaiTroDangGiu() {
    CustomRole r = role(CustomRole.Status.HIEU_LUC);
    when(repo.list(T, CustomRole.Status.HIEU_LUC)).thenReturn(List.of(r));
    assertThat(service.mine(T, List.of("member", r.code()))).containsExactly(r);
    assertThat(service.mine(T, List.of("member"))).isEmpty();
  }

  @Test
  void phamViHepRong() {
    assertThat(PermissionScopes.within("B", "Dv")).isTrue();
    assertThat(PermissionScopes.within("Cd", "Dv")).isFalse();
    assertThat(PermissionScopes.within("Gn", "Gn")).isTrue();
    assertThat(PermissionScopes.within("Gn", "Tn")).isFalse();
    assertThat(PermissionScopes.slug("Cán bộ Đảng viên!", 17)).isEqualTo("can_bo_dang_vien");
  }

  @Test
  void capVaiTroTuyChinhChiKhiDangHieuLuc() {
    RoleBindingRepository rb = mock(RoleBindingRepository.class);
    RoleBindingService grants = new RoleBindingService(rb, events, null, clock, repo);
    CustomRole r = role(CustomRole.Status.HIEU_LUC);
    when(repo.findByCode(T, r.code())).thenReturn(Optional.of(r));
    RoleBindingService.Grant g =
        new RoleBindingService.Grant(
            PERSON,
            r.code(),
            UUID.randomUUID(),
            RoleBinding.Scope.DON_VI,
            null,
            NOW,
            NOW.plus(10, ChronoUnit.DAYS),
            "Phân công");
    assertThat(grants.grant(T, admin(), g).roleCode()).isEqualTo(r.code());

    when(repo.findByCode(T, r.code())).thenReturn(Optional.of(role(CustomRole.Status.NGUNG)));
    assertThatThrownBy(() -> grants.grant(T, admin(), g)).isInstanceOf(BusinessException.class);
  }
}
