package vn.viettel.okrkpi.appraisal.infrastructure.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.config.v1.KyDaHuyKhoa;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * {@link PeriodStateListener} ghi {@code period_state} bằng vai trò {@code svc_<schema>} trong RLS
 * của tenant: EVT-09 khoá, EVT-22 mở lại, EVT-26 huỷ khoá; sự kiện cũ hơn không ghi đè.
 */
class PeriodStateListenerIT {

  private static final Instant T0 = Instant.parse("2027-04-05T01:00:00Z");

  @Test
  void suKienCapNhatTrangThaiKyTheoThuTuThoiDiem() throws SQLException {
    PostgreSQLContainer<?> pg = MissionCoreTestDatabase.container();
    UUID tenant = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    for (String schema : List.of("okr", "task", "product")) {
      try (Connection c =
          DriverManager.getConnection(
              pg.getJdbcUrl(), "svc_" + schema, MissionCoreTestDatabase.TEST_DB_PASSWORD)) {
        try (Statement st = c.createStatement()) {
          st.execute("SET app.tenant_id = '" + tenant + "'");
        }
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
        PeriodStateListener listener = new PeriodStateListener(schema, jdbc);
        assertThat(listener.consumer()).isEqualTo(schema + ".period-state");

        listener.onLocked(locked(period, T0), envelope(tenant, EventTopics.EVT_09_KY_DA_KHOA));
        assertThat(status(jdbc, schema, period)).isEqualTo("DA_KHOA");

        listener.onReopened(
            reopened(period, T0.plusSeconds(3600)),
            envelope(tenant, EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM));
        assertThat(status(jdbc, schema, period)).isEqualTo("MO_LAI_HAU_KIEM");

        listener.onLocked(
            locked(period, T0.plusSeconds(7200)), envelope(tenant, EventTopics.EVT_09_KY_DA_KHOA));
        // EVT-22 đến trễ (xảy ra trước lần khoá lại) không mở lại kỳ.
        listener.onReopened(
            reopened(period, T0.plusSeconds(3600)),
            envelope(tenant, EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM));
        assertThat(status(jdbc, schema, period)).isEqualTo("DA_KHOA");

        listener.onUnlocked(
            unlocked(period, T0.plusSeconds(9000)),
            envelope(tenant, EventTopics.EVT_26_KY_DA_HUY_KHOA));
        assertThat(status(jdbc, schema, period)).isEqualTo("MO");
      }
    }
  }

  @Test
  void chiNhanBaSchemaCoPeriodState() {
    assertThatThrownBy(() -> new PeriodStateListener("appraisal", new JdbcTemplate()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String status(JdbcTemplate jdbc, String schema, UUID period) {
    return jdbc.queryForObject(
        "SELECT status FROM " + schema + ".period_state WHERE period_id = ?", String.class, period);
  }

  private static KyDaKhoa locked(UUID period, Instant at) {
    return KyDaKhoa.newBuilder()
        .setPeriodId(period)
        .setPeriodBucket(20271)
        .setLockedAt(at)
        .setSagaId(UUID.randomUUID())
        .setPolicyPackVersionIds(List.of())
        .setAggregateVersion(1)
        .build();
  }

  private static KyDaHuyKhoa unlocked(UUID period, Instant at) {
    return KyDaHuyKhoa.newBuilder()
        .setPeriodId(period)
        .setPeriodBucket(20271)
        .setLockOrderId(UUID.randomUUID())
        .setSagaId(UUID.randomUUID())
        .setReason("Huỷ khoá thử")
        .setUnlockedAt(at)
        .setAggregateVersion(2)
        .build();
  }

  private static KyDaMoLaiHauKiem reopened(UUID period, Instant at) {
    return KyDaMoLaiHauKiem.newBuilder()
        .setPeriodId(period)
        .setReopenRequestId(UUID.randomUUID())
        .setScope("KY")
        .setScopeIds(List.of())
        .setApproverIds(List.of(UUID.randomUUID(), UUID.randomUUID()))
        .setExecutedAt(at)
        .setAggregateVersion(1)
        .build();
  }

  private static InboxEnvelope<SpecificRecord> envelope(UUID tenant, String topic) {
    return new InboxEnvelope<>(
        UUID.randomUUID(),
        topic,
        null,
        new TenantId(tenant),
        null,
        1,
        null,
        Classification.C1,
        null,
        null,
        null,
        null,
        0,
        0,
        KyDaKhoa.newBuilder()
            .setPeriodId(UUID.randomUUID())
            .setPeriodBucket(20271)
            .setLockedAt(T0)
            .setSagaId(UUID.randomUUID())
            .setPolicyPackVersionIds(List.of())
            .setAggregateVersion(1)
            .build());
  }
}
