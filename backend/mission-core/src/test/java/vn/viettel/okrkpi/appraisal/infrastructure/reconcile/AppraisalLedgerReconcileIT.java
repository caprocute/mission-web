package vn.viettel.okrkpi.appraisal.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerReconcileHarness;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.appraisal.v1.HoSoDanhGiaChuyenBuoc;
import vn.vtokr.appraisal.v1.XepLoaiDaQuyetDinh;

/** R2 (DL-12) với nguồn MOD-04 trên PostgreSQL thật: chuyển bước và quyết định xếp loại. */
class AppraisalLedgerReconcileIT {

  private static final int BUCKET = 20_383;
  private static final UUID ACTOR = UUID.randomUUID();
  private static final UUID DECIDER = UUID.randomUUID();
  private static final UUID SNAPSHOT = UUID.randomUUID();
  private static LedgerReconcileHarness h;

  private TenantId tenant;
  private UUID person;
  private UUID profile;

  @BeforeAll
  static void setUp() {
    h =
        new LedgerReconcileHarness("appraisal")
            .source(x -> new AppraisalLedgerSource(x.jdbc, x.tm));
    h.partitions(BUCKET, "appraisal_profile");
  }

  @BeforeEach
  void freshProfile() {
    tenant = TenantId.of(UUID.randomUUID());
    person = UUID.randomUUID();
    profile = UUID.randomUUID();
    h.inTenant(
        tenant,
        () ->
            h.jdbc.update(
                "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, row_version,"
                    + " period_id, period_bucket, person_id, flow, org_unit_id, assignment_id,"
                    + " job_title_id, policy_pack_version_id, status, current_snapshot_id,"
                    + " final_rating_code, decider_id) VALUES (?, ?, ?, 5, ?, ?, ?, 'NHA_NUOC', ?,"
                    + " ?, ?, ?, 'DA_KY_SO', ?, 'HTTNV', ?)",
                profile,
                ACTOR,
                ACTOR,
                UUID.randomUUID(),
                BUCKET,
                person,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SNAPSHOT,
                DECIDER));
  }

  @Test
  void xepLoaiKhopRoiBiSuaThiLech() {
    XepLoaiDaQuyetDinh payload =
        XepLoaiDaQuyetDinh.newBuilder()
            .setProfileId(profile)
            .setPersonId(person)
            .setFlow("NHA_NUOC")
            .setRatingCode("HTTNV")
            .setScoreSnapshotId(SNAPSHOT)
            .setDeciderId(DECIDER)
            .setSignRequestId(null)
            .setAggregateVersion(5)
            .build();
    h.record(tenant, AppraisalLedgerSource.DECIDED, profile, payload, true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper(
        "UPDATE appraisal.appraisal_profile SET final_rating_code = 'HTXSNV' WHERE id = '"
            + profile
            + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("appraisal.rating.decided.v1.ratingCode");

    // Người được đánh giá luôn so, kể cả khi hồ sơ đã sang phiên bản sau.
    h.tamper(
        "UPDATE appraisal.appraisal_profile SET row_version = 6, person_id = '"
            + UUID.randomUUID()
            + "' WHERE id = '"
            + profile
            + "'");
    assertThat(h.run(tenant).detail())
        .contains("appraisal.rating.decided.v1.personId")
        .doesNotContain("appraisal.rating.decided.v1.ratingCode");
  }

  @Test
  void chuyenBuocCuKhongBaoLechChuyenBuocHienHanhThiSo() {
    h.record(
        tenant, AppraisalLedgerSource.STEP, profile, step("TU_DANH_GIA", "CHO_THAM_DINH", 3), true);
    h.record(
        tenant, AppraisalLedgerSource.STEP, profile, step("DA_QUYET_DINH", "DA_KY_SO", 5), true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper(
        "UPDATE appraisal.appraisal_profile SET status = 'KHIEU_NAI' WHERE id = '" + profile + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("appraisal.profile.stepcompleted.v1.toStatus");
  }

  private HoSoDanhGiaChuyenBuoc step(String from, String to, int version) {
    return HoSoDanhGiaChuyenBuoc.newBuilder()
        .setProfileId(profile)
        .setFromStatus(from)
        .setToStatus(to)
        .setStage("THAM_DINH")
        .setActorId(ACTOR)
        .setAggregateVersion(version)
        .build();
  }
}
