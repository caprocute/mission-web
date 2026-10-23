package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.reconcile.OrgReplicaReconciler;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.workflow.infrastructure.reconcile.OrgReplicaReconcileJob;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;

/**
 * DV-06-10 · DL-12 R1: hàm SECURITY DEFINER {@code org_replica.fn_reconcile_pending_tenants} (V14)
 * chạy bằng chủ sở hữu thật của bảng. Các IT khác chạy Flyway bằng {@code postgres} (siêu người
 * dùng bỏ qua RLS) nên không kiểm được chính sách {@code rls_reconcile_run__cho_so}; ở đây toàn bộ
 * migration flowable chạy trên một CSDL riêng bằng vai trò di trú {@code mig_flowable}
 * (NOSUPERUSER, NOBYPASSRLS, như secret {@code flowable-db-mig-flowable} của chart), rồi kiểm chủ
 * sở hữu chỉ đọc được dòng {@code DANG_CHAY}, không ghi được, và hàm chỉ trả mã tenant có lần chạy
 * đến hạn.
 */
class OrgReplicaReconcileOwnerRlsIT {

  private static final String DB = "mission_wf_mig";
  private static final String MIG = "mig_flowable";

  /** Mật khẩu chỉ dùng trong container kiểm thử. */
  private static final String MIG_PASSWORD = "mig-kiem-thu";

  private static final String JOB = OrgReplicaReconciler.JOB;
  private static final UUID DUE = UUID.randomUUID();
  private static final UUID DONE = UUID.randomUUID();
  private static final UUID FRESH = UUID.randomUUID();
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  private static JdbcTemplate owner;
  private static JdbcTemplate svc;
  private static DriverManagerDataSource svcDs;

  @BeforeAll
  static void migrateAsOwner() {
    PostgreSQLContainer<?> pg = WorkflowTestDatabase.start();
    JdbcTemplate superuser = WorkflowTestDatabase.owner();
    superuser.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
            + MIG
            + "') THEN CREATE ROLE "
            + MIG
            + " LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB PASSWORD '"
            + MIG_PASSWORD
            + "'; END IF; END $$");
    Integer exists =
        superuser.queryForObject(
            "SELECT count(*) FROM pg_database WHERE datname = ?", Integer.class, DB);
    if (exists == null || exists == 0) {
      superuser.execute("CREATE DATABASE " + DB + " OWNER " + MIG);
    }
    String url = pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + DB);
    DriverManagerDataSource migDs = new DriverManagerDataSource(url, MIG, MIG_PASSWORD);
    Flyway.configure()
        .dataSource(migDs)
        .schemas("flowable")
        .defaultSchema("flowable")
        .locations("classpath:db/migration/flowable")
        .load()
        .migrate();
    owner = new JdbcTemplate(migDs);
    svcDs = new DriverManagerDataSource(url, "svc_flowable", WorkflowTestDatabase.SVC_PASSWORD);
    svc = new JdbcTemplate(svcDs);

    insertAs(DUE, "DANG_CHAY", NOW.minus(Duration.ofHours(1)));
    insertAs(DONE, "KHOP", NOW.minus(Duration.ofHours(2)));
    insertAs(FRESH, "DANG_CHAY", NOW.minus(Duration.ofMinutes(1)));
  }

  @Test
  void chuSoHuuLaVaiTroDiTruKhongBoQuaRls() {
    assertThat(
            owner.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                Boolean.class))
        .isFalse();
    assertThat(
            owner.queryForObject(
                "SELECT pg_get_userbyid(relowner) FROM pg_class"
                    + " WHERE oid = 'org_replica.reconcile_run'::regclass",
                String.class))
        .isEqualTo(MIG);
    assertThat(
            owner.queryForObject(
                "SELECT pg_get_userbyid(proowner) || ':' || prosecdef FROM pg_proc"
                    + " WHERE oid = 'org_replica.fn_reconcile_pending_tenants(varchar,"
                    + " timestamptz, int)'::regprocedure",
                String.class))
        .isEqualTo(MIG + ":true");
  }

  @Test
  void hamChiTraTenantCoLanChayDenHan() {
    List<UUID> pending =
        svc.queryForList(
            "SELECT org_replica.fn_reconcile_pending_tenants(?, ?, ?)",
            UUID.class,
            JOB,
            Timestamp.from(NOW.minus(Duration.ofMinutes(15))),
            500);
    assertThat(pending).contains(DUE).doesNotContain(DONE, FRESH);
    assertThat(
            svc.queryForList(
                "SELECT org_replica.fn_reconcile_pending_tenants(?, ?, ?)",
                UUID.class,
                "R9_KHONG_CO",
                Timestamp.from(NOW),
                500))
        .isEmpty();
  }

  @Test
  void jobLayTenantQuaHamBangTaiKhoanUngDung() {
    Clock clock = Clock.systemUTC();
    OrgReplicaReconciler reconciler =
        new OrgReplicaReconciler(
            svc,
            OrgReplicaReconciler.DEFAULT_SCHEMA,
            new ReconcileMetrics(new SimpleMeterRegistry(), clock),
            new ObjectMapper(),
            clock);
    OrgReplicaReconcileJob job =
        new OrgReplicaReconcileJob(
            reconciler,
            svc,
            new DataSourceTransactionManager(svcDs),
            Duration.ofMinutes(15),
            clock);
    assertThat(job.pendingTenants()).contains(DUE).doesNotContain(DONE, FRESH);
  }

  @Test
  void chuSoHuuChiDocDongDangChayVaKhongGhiDuoc() {
    List<String> seen =
        owner.queryForList(
            "SELECT DISTINCT status FROM org_replica.reconcile_run WHERE tenant_id IN (?, ?, ?)",
            String.class,
            DUE,
            DONE,
            FRESH);
    assertThat(seen).containsExactly("DANG_CHAY");
    assertThat(
            owner.update(
                "UPDATE org_replica.reconcile_run SET status = 'LOI' WHERE tenant_id = ?", DUE))
        .isZero();
    assertThat(owner.update("DELETE FROM org_replica.reconcile_run WHERE tenant_id = ?", DUE))
        .isZero();
    assertThatThrownBy(
            () ->
                owner.update(
                    "INSERT INTO org_replica.reconcile_run (id, tenant_id, entity_type, status)"
                        + " VALUES (?, ?, 'PERSON', 'DANG_CHAY')",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .rootCause()
        .hasMessageContaining("row-level security");
    assertThat(statusOf(DUE)).isEqualTo("DANG_CHAY");
  }

  @Test
  void taiKhoanUngDungKhongCoNguCanhTenantKhongThayDong() {
    assertThat(svc.queryForObject("SELECT count(*) FROM org_replica.reconcile_run", Long.class))
        .isZero();
  }

  private static String statusOf(UUID tenant) {
    return new TransactionTemplate(new DataSourceTransactionManager(svcDs))
        .execute(
            s -> {
              svc.queryForObject(
                  "SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
              return svc.queryForObject(
                  "SELECT status FROM org_replica.reconcile_run WHERE tenant_id = ?",
                  String.class,
                  tenant);
            });
  }

  private static void insertAs(UUID tenant, String status, Instant startedAt) {
    new TransactionTemplate(new DataSourceTransactionManager(svcDs))
        .executeWithoutResult(
            s -> {
              svc.queryForObject(
                  "SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
              svc.update(
                  "INSERT INTO org_replica.reconcile_run"
                      + " (id, tenant_id, job, entity_type, started_at, finished_at, status)"
                      + " VALUES (?, ?, ?, 'TAT_CA', ?, ?, ?)",
                  UUID.randomUUID(),
                  tenant,
                  JOB,
                  Timestamp.from(startedAt),
                  "DANG_CHAY".equals(status) ? null : Timestamp.from(startedAt.plusSeconds(60)),
                  status);
            });
  }
}
