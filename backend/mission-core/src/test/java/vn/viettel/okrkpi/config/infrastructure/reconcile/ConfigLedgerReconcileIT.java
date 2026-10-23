package vn.viettel.okrkpi.config.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerReconcileHarness;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.sys.v1.AuditEvent;

/** R2 (DL-12) với nguồn MOD-08 trên PostgreSQL thật: kỳ đã khoá và sự kiện kiểm toán. */
class ConfigLedgerReconcileIT {

  private static final int BUCKET = 20_382;
  private static final UUID ACTOR = UUID.randomUUID();
  private static final Instant LOCKED_AT = Instant.parse("2026-10-22T05:00:00.250001Z");
  private static LedgerReconcileHarness h;

  private TenantId tenant;

  @BeforeAll
  static void setUp() {
    h = new LedgerReconcileHarness("config").source(x -> new ConfigLedgerSource(x.jdbc, x.tm));
  }

  @BeforeEach
  void freshTenant() {
    tenant = TenantId.of(UUID.randomUUID());
  }

  @Test
  void kyDaKhoaKhopRoiNhatKyBiSuaThiLech() {
    UUID saga = UUID.randomUUID();
    UUID period = lockedPeriod(saga);
    KyDaKhoa payload =
        KyDaKhoa.newBuilder()
            .setPeriodId(period)
            .setPeriodBucket(BUCKET)
            .setLockedAt(LOCKED_AT)
            .setSagaId(saga)
            .setPolicyPackVersionIds(List.of())
            .setAggregateVersion(2)
            .build();
    h.record(tenant, ConfigLedgerSource.LOCKED, period, payload, true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper(
        "UPDATE config.period_status_log SET cause_ref = '"
            + UUID.randomUUID()
            + "' WHERE period_id = '"
            + period
            + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("config.period.locked.v1.sagaId");

    h.tamper(
        "UPDATE config.period_status_log SET changed_at = changed_at + interval '1 second'"
            + " WHERE period_id = '"
            + period
            + "'");
    assertThat(h.run(tenant).detail()).contains("config.period.locked.v1.lockedAt");
  }

  @Test
  void suKienKiemToanChiDoiSoatBam() {
    AuditEvent audit =
        AuditEvent.newBuilder()
            .setAction("CAU_HINH_CAP_NHAT")
            .setObjectType("TENANT_SETTING")
            .setObjectId("x")
            .setOutcome("THANH_CONG")
            .setPurpose(null)
            .setActorAssignmentId(null)
            .setActorRole(null)
            .setSystem(false)
            .setTargets(null)
            .build();
    UUID aggregate = UUID.randomUUID();
    UUID id = h.record(tenant, ConfigLedgerSource.AUDIT, aggregate, audit, true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    // Byte 1 là ký tự đầu của action: đổi nó giữ thân Avro hợp lệ nhưng lệch băm với sổ cái.
    h.tamper(
        "UPDATE config.outbox_event SET payload = set_byte(payload, 1, 90) WHERE id = '"
            + id
            + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("LECH_BAM_PAYLOAD");
  }

  private UUID lockedPeriod(UUID saga) {
    UUID id = UUID.randomUUID();
    h.inTenant(
        tenant,
        () -> {
          h.jdbc.update(
              "INSERT INTO config.period (id, created_by, updated_by, row_version, code, name,"
                  + " kind, flow, start_date, end_date, period_bucket, status, locked_at,"
                  + " locked_by, lock_saga_id) VALUES (?, ?, ?, 2, ?, 'Quý 2', 'QUY', 'NHA_NUOC',"
                  + " DATE '2038-04-01', DATE '2038-06-30', ?, 'DA_KHOA', ?, ?, ?)",
              id,
              ACTOR,
              ACTOR,
              "K-" + id.toString().substring(0, 8),
              BUCKET,
              Timestamp.from(LOCKED_AT),
              ACTOR,
              saga);
          return h.jdbc.update(
              "INSERT INTO config.period_status_log (id, period_id, from_status, to_status,"
                  + " cause_type, cause_ref, actor_id, changed_at) VALUES (?, ?, 'DANG_KHOA',"
                  + " 'DA_KHOA', 'SAGA_CHOT_KY', ?, ?, ?)",
              UUID.randomUUID(),
              id,
              saga,
              ACTOR,
              Timestamp.from(LOCKED_AT));
        });
    return id;
  }
}
