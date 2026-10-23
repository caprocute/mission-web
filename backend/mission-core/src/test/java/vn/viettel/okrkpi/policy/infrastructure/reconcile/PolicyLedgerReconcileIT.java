package vn.viettel.okrkpi.policy.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerReconcileHarness;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.sys.v1.AuditEvent;

/** R2 (DL-12) với nguồn MOD-05 trên PostgreSQL thật: sự kiện kiểm toán chỉ đối soát băm. */
class PolicyLedgerReconcileIT {

  private static LedgerReconcileHarness h;

  @BeforeAll
  static void setUp() {
    h = new LedgerReconcileHarness("policy").source(x -> new PolicyLedgerSource(x.jdbc, x.tm));
  }

  @Test
  void suKienKiemToanThieuSoCaiThiLech() {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    h.record(tenant, PolicyLedgerSource.AUDIT, UUID.randomUUID(), audit(), true);
    assertThat(h.run(tenant).status()).isEqualTo(RunStatus.KHOP);

    h.record(tenant, PolicyLedgerSource.AUDIT, UUID.randomUUID(), audit(), false);
    var run = h.run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail()).contains("THIEU_SO_CAI");
  }

  private static AuditEvent audit() {
    return AuditEvent.newBuilder()
        .setAction("BO_CHINH_SACH_PHAT_HANH")
        .setObjectType("POLICY_PACK")
        .setObjectId(UUID.randomUUID().toString())
        .setOutcome("THANH_CONG")
        .setPurpose(null)
        .setActorAssignmentId(null)
        .setActorRole(null)
        .setSystem(false)
        .setTargets(null)
        .build();
  }
}
