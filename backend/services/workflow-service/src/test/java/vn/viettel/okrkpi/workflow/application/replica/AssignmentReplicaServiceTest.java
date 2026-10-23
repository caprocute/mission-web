package vn.viettel.okrkpi.workflow.application.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.port.AssignmentReplicaStore;
import vn.viettel.okrkpi.workflow.domain.replica.AssignmentReplica;

/** Áp EVT-15 vào bản sao phân công: upsert theo {@code source_version} (ECST, DV-06-10). */
class AssignmentReplicaServiceTest {

  private static final UUID TENANT = UUID.randomUUID();

  /** Bản giả giữ đúng ngữ nghĩa upsert có điều kiện của bảng. */
  static final class Store implements AssignmentReplicaStore {
    final Map<String, AssignmentReplica> rows = new HashMap<>();
    final Map<String, UUID> events = new HashMap<>();

    @Override
    public boolean upsert(UUID tenantId, AssignmentReplica a, UUID eventId) {
      String key = tenantId + "|" + a.id() + "|" + a.validFrom();
      AssignmentReplica current = rows.get(key);
      if (current != null && current.sourceVersion() >= a.sourceVersion()) {
        return false;
      }
      rows.put(key, a);
      events.put(key, eventId);
      return true;
    }
  }

  private final Store store = new Store();
  private final AssignmentReplicaService service = new AssignmentReplicaService(store);

  static AssignmentReplica version(UUID id, long v, LocalDate to) {
    return new AssignmentReplica(
        id,
        UUID.fromString("0192f1a2-0000-7000-8000-0000000000a1"),
        UUID.fromString("0192f1a2-0000-7000-8000-0000000000b1"),
        null,
        null,
        "CHINH",
        true,
        false,
        LocalDate.of(2026, 1, 1),
        to,
        v);
  }

  @Test
  void banMoiGhiDeBanCuKhongDeBanMoi() {
    UUID id = UUID.randomUUID();
    UUID e1 = UUID.randomUUID();
    assertThat(service.apply(TENANT, version(id, 5, null), e1)).isTrue();
    LocalDate end = LocalDate.of(2026, 6, 30);
    UUID e2 = UUID.randomUUID();
    assertThat(service.apply(TENANT, version(id, 7, end), e2)).isTrue();
    assertThat(service.apply(TENANT, version(id, 6, null), UUID.randomUUID())).isFalse();
    assertThat(service.apply(TENANT, version(id, 7, null), UUID.randomUUID())).isFalse();
    AssignmentReplica kept = store.rows.values().iterator().next();
    assertThat(kept.sourceVersion()).isEqualTo(7);
    assertThat(kept.validTo()).isEqualTo(end);
    assertThat(store.events.values()).containsExactly(e2);
  }

  @Test
  void thieuThamSoBiTuChoi() {
    AssignmentReplica a = version(UUID.randomUUID(), 1, null);
    assertThatThrownBy(() -> service.apply(null, a, UUID.randomUUID()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.apply(TENANT, null, UUID.randomUUID()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.apply(TENANT, a, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AssignmentReplicaService(null))
        .isInstanceOf(NullPointerException.class);
  }
}
