package vn.viettel.okrkpi.okr.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import vn.vtokr.okr.v1.KrDaCheckIn;
import vn.vtokr.okr.v1.MucTieuDaDuyet;

/** R2 (DL-12) với nguồn MOD-01 trên PostgreSQL thật: mục tiêu đã duyệt và check-in đã gửi. */
class OkrLedgerReconcileIT {

  private static final int BUCKET = 20_374;
  private static final UUID PERIOD = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final Instant CHECKIN_AT = Instant.parse("2026-10-22T03:15:30.123456Z");
  private static LedgerReconcileHarness h;

  private TenantId tenant;

  @BeforeAll
  static void setUp() {
    h = new LedgerReconcileHarness("okr").source(x -> new OkrLedgerSource(x.jdbc, x.tm));
    h.partitions(BUCKET, "objective", "objective_basis", "key_result", "kr_checkin");
  }

  @BeforeEach
  void freshTenant() {
    tenant = TenantId.of(UUID.randomUUID());
  }

  @Test
  void mucTieuDaDuyetKhopRoiCanCuBiXoaThiLech() {
    UUID unit = UUID.randomUUID();
    UUID objective = objective(unit, 3);
    UUID basis = basis(objective);
    h.record(
        tenant, OkrLedgerSource.APPROVED, objective, approved(objective, unit, basis, 3), true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper("DELETE FROM okr.objective_basis WHERE objective_id = '" + objective + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("okr.objective.approved.v1.basisIds");
  }

  @Test
  void mucTieuDaDieuChinhThiChiSoKyVaTang() {
    UUID objective = objective(UUID.randomUUID(), 7);
    // Sự kiện duyệt ở phiên bản 3; sau đó đổi đơn vị sở hữu hợp lệ (phiên bản 7).
    h.record(
        tenant,
        OkrLedgerSource.APPROVED,
        objective,
        approved(objective, UUID.randomUUID(), UUID.randomUUID(), 3),
        true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper("UPDATE okr.objective SET tier = 1 WHERE id = '" + objective + "'");
    assertThat(h.run(tenant).detail()).contains("okr.objective.approved.v1.level");
  }

  @Test
  void checkInDaGuiBiSuaGiaTriThiLech() {
    UUID objective = objective(UUID.randomUUID(), 1);
    UUID kr = keyResult(objective);
    checkin(kr, new BigDecimal("12.5000"));
    KrDaCheckIn payload =
        KrDaCheckIn.newBuilder()
            .setKeyResultId(kr)
            .setObjectiveId(objective)
            .setValue(new BigDecimal("12.5000"))
            .setConfidence("CAO")
            .setCheckinAt(CHECKIN_AT)
            .setAggregateVersion(2)
            .build();
    h.record(tenant, OkrLedgerSource.CHECKED_IN, kr, payload, true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper("UPDATE okr.kr_checkin SET value = 99 WHERE key_result_id = '" + kr + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("okr.keyresult.checkedin.v1.value");
  }

  // ---------------------------------------------------------------- hỗ trợ

  private static MucTieuDaDuyet approved(UUID objective, UUID unit, UUID basis, int version) {
    return MucTieuDaDuyet.newBuilder()
        .setObjectiveId(objective)
        .setPeriodId(PERIOD)
        .setLevel(2)
        .setOwnerUnitId(unit)
        .setParentObjectiveId(null)
        .setBasisIds(List.of(basis))
        .setApprovedBy(ACTOR)
        .setAggregateVersion(version)
        .setAiContent(List.of())
        .build();
  }

  private UUID objective(UUID unit, int version) {
    UUID id = UUID.randomUUID();
    h.inTenant(
        tenant,
        () ->
            h.jdbc.update(
                "INSERT INTO okr.objective (id, created_by, updated_by, row_version, period_id,"
                    + " period_bucket, owner_type, owner_unit_id, code, title, tier, status,"
                    + " approved_by) VALUES (?, ?, ?, ?, ?, ?, 'TAP_THE', ?, ?, 'Mục tiêu', 2,"
                    + " 'DA_DUYET', ?)",
                id,
                ACTOR,
                ACTOR,
                version,
                PERIOD,
                BUCKET,
                unit,
                "MT-" + id.toString().substring(0, 8),
                ACTOR));
    return id;
  }

  private UUID basis(UUID objective) {
    UUID id = UUID.randomUUID();
    h.inTenant(
        tenant,
        () -> {
          h.jdbc.update(
              "INSERT INTO okr.basis (id, created_by, updated_by, basis_kind, title)"
                  + " VALUES (?, ?, ?, 'NGHI_QUYET', 'Nghị quyết')",
              id,
              ACTOR,
              ACTOR);
          return h.jdbc.update(
              "INSERT INTO okr.objective_basis (period_bucket, objective_id, basis_id, created_by)"
                  + " VALUES (?, ?, ?, ?)",
              BUCKET,
              objective,
              id,
              ACTOR);
        });
    return id;
  }

  private UUID keyResult(UUID objective) {
    UUID id = UUID.randomUUID();
    h.inTenant(
        tenant,
        () ->
            h.jdbc.update(
                "INSERT INTO okr.key_result (id, created_by, updated_by, period_id, period_bucket,"
                    + " objective_id, code, title, metric_type, unit, target_value, direction,"
                    + " weight, due_date, verifier_person_id, checkin_frequency)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, 'KR', 'SO_LUONG', 'việc', 20, 'TANG', 1,"
                    + " DATE '2026-12-31', ?, 'TUAN')",
                id,
                ACTOR,
                ACTOR,
                PERIOD,
                BUCKET,
                objective,
                "KR-" + id.toString().substring(0, 8),
                ACTOR));
    return id;
  }

  private void checkin(UUID kr, BigDecimal value) {
    h.inTenant(
        tenant,
        () ->
            h.jdbc.update(
                "INSERT INTO okr.kr_checkin (id, created_by, updated_by, period_id, period_bucket,"
                    + " key_result_id, checkin_at, value, confidence, channel, idempotency_key,"
                    + " status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CAO', 'WEB', ?, 'DA_GUI')",
                UUID.randomUUID(),
                ACTOR,
                ACTOR,
                PERIOD,
                BUCKET,
                kr,
                Timestamp.from(CHECKIN_AT),
                value,
                UUID.randomUUID().toString()));
  }
}
