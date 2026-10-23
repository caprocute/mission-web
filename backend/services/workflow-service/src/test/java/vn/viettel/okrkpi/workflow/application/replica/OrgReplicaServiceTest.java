package vn.viettel.okrkpi.workflow.application.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.port.OrgUnitReplicaStore;
import vn.viettel.okrkpi.workflow.application.port.PersonReplicaStore;
import vn.viettel.okrkpi.workflow.domain.replica.OrgTree;
import vn.viettel.okrkpi.workflow.domain.replica.OrgTreeCycleException;
import vn.viettel.okrkpi.workflow.domain.replica.OrgUnitReplica;
import vn.viettel.okrkpi.workflow.domain.replica.PersonReplica;

/** Áp EVT-14, EVT-16 vào bản sao: upsert theo {@code source_version}, dò vòng cây (DV-06-10). */
class OrgReplicaServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

  /** Bản giả giữ đúng ngữ nghĩa upsert có điều kiện và phép đi lên của cây phiên bản mở. */
  static final class Units implements OrgUnitReplicaStore {
    final Map<String, OrgUnitReplica> rows = new HashMap<>();

    @Override
    public boolean upsert(UUID tenantId, OrgUnitReplica u, UUID eventId) {
      String key = tenantId + "|" + u.id() + "|" + u.validFrom();
      OrgUnitReplica current = rows.get(key);
      if (current != null && current.sourceVersion() >= u.sourceVersion()) {
        return false;
      }
      rows.put(key, u);
      return true;
    }

    @Override
    public boolean reachesUpward(UUID tenantId, OrgTree tree, UUID from, UUID target) {
      Set<UUID> seen = new HashSet<>();
      UUID current = from;
      while (current != null && seen.add(current)) {
        if (current.equals(target)) {
          return true;
        }
        UUID id = current;
        current =
            rows.values().stream()
                .filter(u -> u.id().equals(id) && u.openEnded())
                .findFirst()
                .map(u -> u.parentOn(tree))
                .orElse(null);
      }
      return false;
    }
  }

  static final class Persons implements PersonReplicaStore {
    final Map<UUID, PersonReplica> rows = new HashMap<>();

    @Override
    public boolean upsert(UUID tenantId, PersonReplica p, UUID eventId) {
      PersonReplica current = rows.get(p.id());
      if (current != null && current.sourceVersion() >= p.sourceVersion()) {
        return false;
      }
      rows.put(p.id(), p);
      return true;
    }
  }

  private final Units units = new Units();
  private final Persons persons = new Persons();
  private final OrgReplicaService service = new OrgReplicaService(units, persons);

  static OrgUnitReplica unit(UUID id, UUID party, UUID admin, LocalDate to, long version) {
    return new OrgUnitReplica(
        id, FROM, to, "DV-" + version, "Đơn vị", "CHI_BO", party, admin, null, version);
  }

  @Test
  void banMoiGhiDeBanCuKhongDeBanMoi() {
    UUID id = UUID.randomUUID();
    UUID parent = UUID.randomUUID();
    assertThat(service.applyUnit(TENANT, unit(id, parent, null, null, 5), UUID.randomUUID()))
        .isTrue();
    assertThat(service.applyUnit(TENANT, unit(id, null, null, null, 4), UUID.randomUUID()))
        .isFalse();
    assertThat(service.applyUnit(TENANT, unit(id, null, null, null, 5), UUID.randomUUID()))
        .isFalse();
    assertThat(units.rows.values())
        .singleElement()
        .extracting(OrgUnitReplica::partyParentId)
        .isEqualTo(parent);
    assertThat(service.applyUnit(TENANT, unit(id, null, parent, null, 6), UUID.randomUUID()))
        .isTrue();
    assertThat(units.rows.values())
        .singleElement()
        .extracting(OrgUnitReplica::adminParentId)
        .isEqualTo(parent);
  }

  @Test
  void doiCapTrenTaoVongTrenCayDangHoacChuyenMonBiTuChoi() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    service.applyUnit(TENANT, unit(a, null, null, null, 1), UUID.randomUUID());
    service.applyUnit(TENANT, unit(b, a, a, null, 1), UUID.randomUUID());
    assertThatThrownBy(
            () -> service.applyUnit(TENANT, unit(a, b, null, null, 2), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            OrgTreeCycleException.class, ex -> assertThat(ex.tree()).isEqualTo(OrgTree.DANG));
    assertThatThrownBy(
            () -> service.applyUnit(TENANT, unit(a, null, b, null, 3), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            OrgTreeCycleException.class, ex -> assertThat(ex.tree()).isEqualTo(OrgTree.CHUYEN_MON));
  }

  @Test
  void tuTrucThuocChinhMinhLaVongKhongGhi() {
    UUID a = UUID.randomUUID();
    assertThatThrownBy(
            () -> service.applyUnit(TENANT, unit(a, a, null, null, 1), UUID.randomUUID()))
        .isInstanceOf(OrgTreeCycleException.class)
        .hasMessageContaining(a.toString());
    assertThatThrownBy(
            () -> service.applyUnit(TENANT, unit(a, null, a, null, 1), UUID.randomUUID()))
        .isInstanceOf(OrgTreeCycleException.class);
    assertThat(units.rows).isEmpty();
  }

  @Test
  void phienBanDaDongKhongDoVong() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    service.applyUnit(TENANT, unit(b, a, null, null, 1), UUID.randomUUID());
    // Lát lịch sử của a trực thuộc b: không quyết định cây hiện hành.
    assertThat(
            service.applyUnit(
                TENANT, unit(a, b, null, LocalDate.of(2026, 6, 1), 1), UUID.randomUUID()))
        .isTrue();
  }

  @Test
  void nhanSuUpsertTheoPhienBanNguon() {
    UUID id = UUID.randomUUID();
    PersonReplica v2 = new PersonReplica(id, "Nguyễn Văn A", true, "DANG_CONG_TAC", 2);
    assertThat(service.applyPerson(TENANT, v2, UUID.randomUUID())).isTrue();
    PersonReplica v1 = new PersonReplica(id, "Nguyễn Văn A", false, "THOI_VIEC", 1);
    assertThat(service.applyPerson(TENANT, v1, UUID.randomUUID())).isFalse();
    assertThat(persons.rows.get(id)).isEqualTo(v2);
  }

  @Test
  void thamSoBatBuoc() {
    OrgUnitReplica u = unit(UUID.randomUUID(), null, null, null, 1);
    PersonReplica p = new PersonReplica(UUID.randomUUID(), "A", false, "DANG_CONG_TAC", 1);
    assertThatThrownBy(() -> service.applyUnit(null, u, UUID.randomUUID()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.applyUnit(TENANT, null, UUID.randomUUID()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.applyUnit(TENANT, u, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.applyPerson(null, p, UUID.randomUUID()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.applyPerson(TENANT, null, UUID.randomUUID()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.applyPerson(TENANT, p, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new OrgReplicaService(null, persons))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new OrgReplicaService(units, null))
        .isInstanceOf(NullPointerException.class);
  }
}
