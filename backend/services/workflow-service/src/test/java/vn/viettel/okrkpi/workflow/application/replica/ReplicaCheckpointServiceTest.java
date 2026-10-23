package vn.viettel.okrkpi.workflow.application.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.port.ReplicaCheckpointStore;
import vn.viettel.okrkpi.workflow.domain.replica.ReplicaEntityType;

/** Độ trễ áp dụng bản sao tổ chức ({@code org_replica_lag_ms}, DV-06-10). */
class ReplicaCheckpointServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
  private static final UUID TENANT = UUID.randomUUID();

  record Mark(
      UUID tenant,
      ReplicaEntityType type,
      UUID event,
      Instant occurred,
      Instant applied,
      long lag) {}

  private final List<Mark> marks = new ArrayList<>();
  private final ReplicaCheckpointStore store =
      (tenant, type, event, occurred, applied, lag) ->
          marks.add(new Mark(tenant, type, event, occurred, applied, lag));
  private final ReplicaCheckpointService service =
      new ReplicaCheckpointService(store, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void doTreLaKhoangTuLucPhatToiLucAp() {
    UUID event = UUID.randomUUID();
    long lag = service.record(TENANT, ReplicaEntityType.PERSON, event, NOW.minusMillis(1_250));
    assertThat(lag).isEqualTo(1_250);
    assertThat(marks)
        .containsExactly(
            new Mark(TENANT, ReplicaEntityType.PERSON, event, NOW.minusMillis(1_250), NOW, 1_250));
  }

  @Test
  void dongHoLechHoacThieuThoiDiemPhatThiDoTreBangKhong() {
    assertThat(
            service.record(
                TENANT, ReplicaEntityType.ORG_UNIT, UUID.randomUUID(), NOW.plusSeconds(3)))
        .isZero();
    assertThat(service.record(TENANT, ReplicaEntityType.ASSIGNMENT, UUID.randomUUID(), null))
        .isZero();
    assertThat(marks.get(1).occurred()).isEqualTo(NOW);
  }

  @Test
  void thieuThamSoBatBuocBiTuChoi() {
    assertThatThrownBy(() -> service.record(null, ReplicaEntityType.PERSON, UUID.randomUUID(), NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.record(TENANT, null, UUID.randomUUID(), NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.record(TENANT, ReplicaEntityType.PERSON, null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThat(marks).isEmpty();
  }
}
