package vn.viettel.okrkpi.oversight.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerReconcileHarness;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.oversight.v1.KhieuNaiDaGui;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/** R2 (DL-12) với nguồn MOD-07 trên PostgreSQL thật: mở lại kỳ hậu kiểm và khiếu nại đã gửi. */
class OversightLedgerReconcileIT {

  private static final int BUCKET = 20_384;
  private static final UUID PERIOD = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final Instant FIRST = Instant.parse("2026-10-22T02:00:00.100000Z");
  private static final Instant SECOND = Instant.parse("2026-10-22T03:00:00.200000Z");
  private static LedgerReconcileHarness h;

  private TenantId tenant;

  @BeforeAll
  static void setUp() {
    h =
        new LedgerReconcileHarness("oversight")
            .source(x -> new OversightLedgerSource(x.jdbc, x.tm));
    h.partitions(BUCKET, "complaint", "reopen_request", "reopen_approval");
  }

  @BeforeEach
  void freshTenant() {
    tenant = TenantId.of(UUID.randomUUID());
  }

  @Test
  void moLaiKhopRoiPhieuDuyetBiXoaThiLech() {
    UUID unit = UUID.randomUUID();
    UUID decider = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID request = reopen(unit);
    approval(request, decider, "decider", FIRST);
    approval(request, second, "second_approver", SECOND);
    KyDaMoLaiHauKiem payload =
        KyDaMoLaiHauKiem.newBuilder()
            .setPeriodId(PERIOD)
            .setReopenRequestId(request)
            .setScope("DON_VI")
            .setScopeIds(List.of(unit))
            .setApproverIds(List.of(decider, second))
            .setExecutedAt(SECOND)
            .setAggregateVersion(3)
            .build();
    h.record(tenant, OversightLedgerSource.REOPENED, request, payload, true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper("DELETE FROM oversight.reopen_approval WHERE approver_id = '" + second + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail())
        .contains("oversight.period.reopened.v1.approverIds")
        .contains("oversight.period.reopened.v1.executedAt");
  }

  @Test
  void khieuNaiDaGuiBiDoiNguoiGuiThiLech() {
    UUID profile = UUID.randomUUID();
    UUID complaint = complaint(profile);
    KhieuNaiDaGui payload =
        KhieuNaiDaGui.newBuilder()
            .setComplaintId(complaint)
            .setProfileId(profile)
            .setTargetStage("QUYET_DINH")
            .setComplainantId(ACTOR)
            .setFiledAt(FIRST)
            .setResponseDeadline(LocalDate.of(2026, 11, 5))
            .setAggregateVersion(1)
            .build();
    h.record(tenant, OversightLedgerSource.COMPLAINT, complaint, payload, true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper(
        "UPDATE oversight.complaint SET complainant_person_id = '"
            + UUID.randomUUID()
            + "' WHERE id = '"
            + complaint
            + "'");
    assertThat(h.run(tenant).detail()).contains("oversight.complaint.filed.v1.complainantId");
  }

  private UUID reopen(UUID unit) {
    UUID id = UUID.randomUUID();
    h.inTenant(
        tenant,
        () ->
            h.jdbc.update(
                "INSERT INTO oversight.reopen_request (id, created_by, updated_by, row_version,"
                    + " period_id, period_bucket, code, scope, scope_ids, reason, basis_ref,"
                    + " audit_kind, requested_by, status, open_until) VALUES (?, ?, ?, 3, ?, ?, ?,"
                    + " 'DON_VI', ARRAY[?]::uuid[], 'Hậu kiểm theo phản ánh', 'QĐ-01', 'DOT_XUAT',"
                    + " ?, 'DA_DUYET', now() + interval '7 days')",
                id,
                ACTOR,
                ACTOR,
                PERIOD,
                BUCKET,
                "ML-" + id.toString().substring(0, 8),
                unit,
                ACTOR));
    return id;
  }

  private void approval(UUID request, UUID approver, String role, Instant at) {
    h.inTenant(
        tenant,
        () ->
            h.jdbc.update(
                "INSERT INTO oversight.reopen_approval (id, created_by, updated_by, period_id,"
                    + " period_bucket, reopen_request_id, approver_id, approver_role, decision,"
                    + " mfa_acr, decided_at, sign_request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                    + " 'DONG_Y', 'mfa', ?, ?)",
                UUID.randomUUID(),
                approver,
                approver,
                PERIOD,
                BUCKET,
                request,
                approver,
                role,
                Timestamp.from(at),
                UUID.randomUUID()));
  }

  private UUID complaint(UUID profile) {
    UUID id = UUID.randomUUID();
    h.inTenant(
        tenant,
        () ->
            h.jdbc.update(
                "INSERT INTO oversight.complaint (id, created_by, updated_by, row_version,"
                    + " period_id, period_bucket, complainant_person_id, profile_id, kind,"
                    + " target_stage, subject_line, content_enc, dek_id, filed_at,"
                    + " response_deadline, status, handler_unit_id) VALUES (?, ?, ?, 1, ?, ?, ?, ?,"
                    + " 'KHIEU_NAI', 'QUYET_DINH', 'Khiếu nại', '\\x00'::bytea, ?, ?,"
                    + " DATE '2026-11-05', 'MOI', ?)",
                id,
                ACTOR,
                ACTOR,
                PERIOD,
                BUCKET,
                ACTOR,
                profile,
                UUID.randomUUID(),
                Timestamp.from(FIRST),
                UUID.randomUUID()));
    return id;
  }
}
