package vn.viettel.okrkpi.iam.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.iam.sync.PersonSyncService.Outcome;
import vn.viettel.okrkpi.iam.sync.PersonSyncService.PersonState;

class PersonSyncServiceTest {

  private static final UUID TENANT = UUID.fromString("0192f000-0000-7000-8000-0000000000a1");
  private final FakeDirectory dir = new FakeDirectory();
  private final PersonSyncService service = new PersonSyncService(dir);

  @Test
  void taoMoiGiuMaVaGanVaoOrganization() {
    UUID p = UUID.randomUUID();
    assertThat(service.apply(new PersonState(TENANT, p, "Nguyễn Văn  An", "DANG_CONG_TAC", 1))).isEqualTo(Outcome.CREATED);
    Map<String, Object> u = dir.users.get(p);
    assertThat(u).containsEntry("id", p.toString()).containsEntry("firstName", "An").containsEntry("lastName", "Nguyễn Văn")
        .containsEntry("enabled", true);
    assertThat(dir.members).containsExactly("org-a:" + p);
  }

  @Test
  void phienBanCuHoacTrungBiBoQua() {
    UUID p = UUID.randomUUID();
    service.apply(new PersonState(TENANT, p, "Trần Bình", "DANG_CONG_TAC", 3));
    assertThat(service.apply(new PersonState(TENANT, p, "Tên cũ", "DANG_CONG_TAC", 3))).isEqualTo(Outcome.SKIPPED);
    assertThat(service.apply(new PersonState(TENANT, p, "Tên cũ hơn", "DANG_CONG_TAC", 2))).isEqualTo(Outcome.SKIPPED);
    assertThat(dir.users.get(p)).containsEntry("firstName", "Bình");
    assertThat(service.apply(new PersonState(TENANT, p, "Trần Thị Bình", "DANG_CONG_TAC", 4))).isEqualTo(Outcome.UPDATED);
    assertThat(dir.users.get(p)).containsEntry("lastName", "Trần Thị");
  }

  @Test
  void thoiCongTacThiKhoaVaDangXuatMotLan() {
    UUID p = UUID.randomUUID();
    service.apply(new PersonState(TENANT, p, "Lê Cường", "DANG_CONG_TAC", 1));
    assertThat(service.apply(new PersonState(TENANT, p, "Lê Cường", "THOI_VIEC", 2))).isEqualTo(Outcome.DISABLED);
    assertThat(service.apply(new PersonState(TENANT, p, "Lê Cường", "THOI_VIEC", 3))).isEqualTo(Outcome.DISABLED);
    assertThat(dir.users.get(p)).containsEntry("enabled", false);
    assertThat(dir.logouts).containsExactly(p);
    assertThat(service.apply(new PersonState(TENANT, p, "Lê Cường", "DANG_CONG_TAC", 4))).isEqualTo(Outcome.UPDATED);
    assertThat(dir.users.get(p)).containsEntry("enabled", true);
  }

  @Test
  void nguoiMoiDaNghiDuocTaoOTrangThaiKhoa() {
    UUID p = UUID.randomUUID();
    assertThat(service.apply(new PersonState(TENANT, p, "Phạm", "NGHI_HUU", 1))).isEqualTo(Outcome.DISABLED);
    assertThat(dir.users.get(p)).containsEntry("enabled", false).containsEntry("firstName", "Phạm").containsEntry("lastName", "");
  }

  @Test
  void tenantChuaCoOrganizationLaLoiTamThoi() {
    assertThatThrownBy(() -> service.apply(new PersonState(UUID.randomUUID(), UUID.randomUUID(), "A B", "DANG_CONG_TAC", 1)))
        .isInstanceOf(Directory.TransientException.class);
  }

  @Test
  void nguoiDungCuaTenantKhacLaLoiVinhVien() {
    UUID p = UUID.randomUUID();
    service.apply(new PersonState(TENANT, p, "A B", "DANG_CONG_TAC", 1));
    assertThatThrownBy(() -> service.apply(new PersonState(UUID.randomUUID(), p, "A B", "DANG_CONG_TAC", 2)))
        .isInstanceOf(Directory.PermanentException.class);
  }

  @Test
  void nguoiDungCoSanChuaGanTenantDuocNhan() {
    UUID p = UUID.randomUUID();
    Map<String, Object> u = new LinkedHashMap<>();
    u.put("id", p.toString());
    u.put("enabled", true);
    dir.users.put(p, u);
    assertThat(service.apply(new PersonState(TENANT, p, "Đỗ Dung", "DANG_CONG_TAC", 1))).isEqualTo(Outcome.UPDATED);
    assertThat(dir.members).containsExactly("org-a:" + p);
  }

  static final class FakeDirectory implements Directory {
    final Map<UUID, Map<String, Object>> users = new HashMap<>();
    final List<String> members = new ArrayList<>();
    final List<UUID> logouts = new ArrayList<>();

    @Override
    public Optional<Map<String, Object>> findUser(UUID id) {
      return Optional.ofNullable(users.get(id)).map(LinkedHashMap::new);
    }

    @Override
    public void createUser(Map<String, Object> user) {
      users.put(UUID.fromString((String) user.get("id")), new LinkedHashMap<>(user));
    }

    @Override
    public void updateUser(UUID id, Map<String, Object> user) {
      users.put(id, new LinkedHashMap<>(user));
    }

    @Override
    public List<Map<String, Object>> findUsersByAttribute(String name, String value) {
      return users.values().stream().filter(u -> u.get("attributes") instanceof Map<?, ?> a
          && List.of(value).equals(a.get(name))).map(u -> (Map<String, Object>) new LinkedHashMap<>(u)).toList();
    }

    @Override
    public void logout(UUID id) {
      logouts.add(id);
    }

    @Override
    public Optional<String> organizationOf(UUID tenant) {
      return TENANT.equals(tenant) ? Optional.of("org-a") : Optional.empty();
    }

    @Override
    public void addMember(String organizationId, UUID userId) {
      members.add(organizationId + ":" + userId);
    }
  }
}
