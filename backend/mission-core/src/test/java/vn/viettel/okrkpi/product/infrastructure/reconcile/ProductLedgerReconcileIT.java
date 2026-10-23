package vn.viettel.okrkpi.product.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerReconcileHarness;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.product.v1.MinhChungDaNop;
import vn.vtokr.product.v1.MinhChungDaXacNhan;

/** R2 (DL-12) với nguồn MOD-03 trên PostgreSQL thật: minh chứng đã nộp và đã xác nhận. */
class ProductLedgerReconcileIT {

  private static final int BUCKET = 20_381;
  private static final UUID PERIOD = UUID.randomUUID();
  private static final UUID SUBMITTER = UUID.randomUUID();
  private static final UUID REVIEWER = UUID.randomUUID();
  private static final Instant DECIDED_AT = Instant.parse("2026-10-22T04:20:10.654321Z");
  private static final byte[] SHA = sha((byte) 7);
  private static LedgerReconcileHarness h;

  private TenantId tenant;
  private UUID task;
  private UUID deliverable;
  private UUID evidence;

  @BeforeAll
  static void setUp() {
    h = new LedgerReconcileHarness("product").source(x -> new ProductLedgerSource(x.jdbc, x.tm));
    h.partitions(
        BUCKET,
        "deliverable",
        "evidence",
        "evidence_version",
        "evidence_version_file",
        "acceptance");
  }

  @BeforeEach
  void freshTenant() {
    tenant = TenantId.of(UUID.randomUUID());
    task = UUID.randomUUID();
    deliverable = UUID.randomUUID();
    evidence = UUID.randomUUID();
    h.inTenant(
        tenant,
        () -> {
          h.jdbc.update(
              "INSERT INTO product.deliverable (id, created_by, updated_by, period_id,"
                  + " period_bucket, task_id, name, deliverable_type, unit, quantity_planned)"
                  + " VALUES (?, ?, ?, ?, ?, ?, 'Báo cáo', 'BAO_CAO', 'bản', 1)",
              deliverable,
              SUBMITTER,
              SUBMITTER,
              PERIOD,
              BUCKET,
              task);
          return h.jdbc.update(
              "INSERT INTO product.evidence (id, created_by, updated_by, period_id, period_bucket,"
                  + " deliverable_id, task_id, title, source_type)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, 'Minh chứng', 'TAI_LEN')",
              evidence,
              SUBMITTER,
              SUBMITTER,
              PERIOD,
              BUCKET,
              deliverable,
              task);
        });
  }

  @Test
  void daNopKhopRoiTepBiDoiThiLech() {
    UUID version = version("DA_NOP");
    h.record(tenant, ProductLedgerSource.SUBMITTED, version, submitted(version), true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper(
        "UPDATE product.evidence_version_file SET content_sha256 = '\\x"
            + "00".repeat(32)
            + "' WHERE evidence_version_id = '"
            + version
            + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("product.evidence.submitted.v1.objectSha256");
  }

  @Test
  void daNopBiDoiNguoiNopThiLech() {
    UUID version = version("DA_NOP");
    h.record(tenant, ProductLedgerSource.SUBMITTED, version, submitted(version), true);
    h.tamper(
        "UPDATE product.evidence_version SET submitted_by = '"
            + UUID.randomUUID()
            + "' WHERE id = '"
            + version
            + "'");
    assertThat(h.run(tenant).detail()).contains("product.evidence.submitted.v1.submittedBy");
  }

  @Test
  void daXacNhanKhopRoiMucChatLuongBiSuaThiLech() {
    UUID version = version("DA_XAC_NHAN");
    h.inTenant(
        tenant,
        () ->
            h.jdbc.update(
                "INSERT INTO product.acceptance (id, created_by, updated_by, period_id,"
                    + " period_bucket, evidence_version_id, task_id, reviewer_id, decision,"
                    + " quality_level, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DA_XAC_NHAN',"
                    + " 0.75, ?)",
                UUID.randomUUID(),
                REVIEWER,
                REVIEWER,
                PERIOD,
                BUCKET,
                version,
                task,
                REVIEWER,
                Timestamp.from(DECIDED_AT)));
    MinhChungDaXacNhan payload =
        MinhChungDaXacNhan.newBuilder()
            .setEvidenceId(evidence)
            .setVersionId(version)
            .setTaskId(task)
            .setQualityLevel(new BigDecimal("0.75"))
            .setAcceptedBy(REVIEWER)
            .setAcceptedAt(DECIDED_AT)
            .setAggregateVersion(3)
            .build();
    h.record(tenant, ProductLedgerSource.ACCEPTED, version, payload, true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.tamper(
        "UPDATE product.acceptance SET quality_level = 1 WHERE evidence_version_id = '"
            + version
            + "'");
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("product.evidence.accepted.v1.qualityLevel");
  }

  // ---------------------------------------------------------------- hỗ trợ

  private MinhChungDaNop submitted(UUID version) {
    return MinhChungDaNop.newBuilder()
        .setEvidenceId(evidence)
        .setVersionId(version)
        .setDeliverableId(deliverable)
        .setTaskId(task)
        .setObjectSha256(ByteBuffer.wrap(SHA))
        .setAiConfirmed(true)
        .setSubmittedBy(SUBMITTER)
        .setAggregateVersion(2)
        .build();
  }

  private UUID version(String status) {
    UUID id = UUID.randomUUID();
    UUID object = UUID.randomUUID();
    h.inTenant(
        tenant,
        () -> {
          h.jdbc.update(
              "INSERT INTO product.evidence_version (id, created_by, updated_by, period_id,"
                  + " period_bucket, evidence_id, task_id, version_no, source, status, object_id,"
                  + " content_sha256, submitted_by, submitted_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1,"
                  + " 'TAI_LEN', 'NHAP', ?, ?, ?, now())",
              id,
              SUBMITTER,
              SUBMITTER,
              PERIOD,
              BUCKET,
              evidence,
              task,
              object,
              SHA,
              SUBMITTER);
          return h.jdbc.update(
              "INSERT INTO product.evidence_version_file (id, created_by, updated_by, period_id,"
                  + " period_bucket, evidence_version_id, task_id, ordinal, object_id,"
                  + " object_version, content_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 1, ?)",
              UUID.randomUUID(),
              SUBMITTER,
              SUBMITTER,
              PERIOD,
              BUCKET,
              id,
              task,
              object,
              SHA);
        });
    // Nộp hoặc xác nhận qua trigger đóng băng không phải việc của kiểm thử này: chuyển thẳng trạng
    // thái.
    h.tamper(
        "UPDATE product.evidence_version SET status = '"
            + status
            + "', scan_status = 'SACH' WHERE id = '"
            + id
            + "'");
    return id;
  }

  private static byte[] sha(byte b) {
    byte[] out = new byte[32];
    java.util.Arrays.fill(out, b);
    return out;
  }
}
