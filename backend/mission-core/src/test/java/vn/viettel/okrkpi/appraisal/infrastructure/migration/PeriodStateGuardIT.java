package vn.viettel.okrkpi.appraisal.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;

/**
 * Lớp bảo vệ thứ hai ở CSDL cho dữ liệu kỳ đã khoá (CORE-04-01, TL-03 "Bảo vệ dữ liệu kỳ đã khoá ở
 * tầng CSDL"): bảng {@code period_state} và trigger {@code fn_guard_period_write} trên mọi bảng
 * theo kỳ của {@code okr}, {@code task}, {@code product}; đo chi phí trigger trên 10.000 dòng ghi.
 */
class PeriodStateGuardIT {

  private static final int BUCKET = 20271;
  private static PostgreSQLContainer<?> pg;

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute("SELECT task.fn_create_period_partitions('task.task', " + BUCKET + ")");
    }
  }

  @Test
  void moiBangTheoKyDeuCoTriggerChanGhi() throws SQLException {
    List<String> missing = new ArrayList<>();
    try (Connection c = admin();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                """
                SELECT n.nspname || '.' || c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'period_id'
                 WHERE n.nspname IN ('okr', 'task', 'product')
                   AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                   AND c.relname <> 'period_state'
                   AND NOT EXISTS (SELECT 1 FROM pg_trigger t
                                    WHERE t.tgrelid = c.oid
                                      AND t.tgname = 'tg_' || c.relname || '__period_guard')
                """)) {
      while (rs.next()) {
        missing.add(rs.getString(1));
      }
    }
    assertThat(missing).as("bảng theo kỳ thiếu trigger chặn ghi").isEmpty();
  }

  @Test
  void kyDaKhoaHoacDangKhoaChanGhiNhiemVu() throws SQLException {
    UUID tenant = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    try (Connection svc = svc(tenant);
        Statement st = svc.createStatement()) {
      UUID task = UUID.randomUUID();
      st.execute(insertTask(task, tenant, period));
      for (String status : List.of("DANG_KHOA", "DA_KHOA")) {
        setState(st, period, status);
        assertThatThrownBy(() -> st.execute(insertTask(UUID.randomUUID(), tenant, period)))
            .isInstanceOfSatisfying(
                SQLException.class,
                e -> {
                  assertThat(e.getSQLState()).isEqualTo("55000");
                  assertThat(e.getMessage()).contains("đang khoá hoặc đã khoá");
                });
        assertThatThrownBy(
                () ->
                    st.executeUpdate(
                        "UPDATE task.task SET title = 'Sửa sau khoá' WHERE id = '" + task + "'"))
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
      }
    }
  }

  @Test
  void moLaiHauKiemChiGhiKhiCoMaYeuCauMoLai() throws SQLException {
    UUID tenant = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    try (Connection svc = svc(tenant);
        Statement st = svc.createStatement()) {
      setState(st, period, "MO_LAI_HAU_KIEM");
      assertThatThrownBy(() -> st.execute(insertTask(UUID.randomUUID(), tenant, period)))
          .hasMessageContaining("mã yêu cầu mở lại");
      svc.setAutoCommit(false);
      st.execute("SET LOCAL app.reopen_request_id = '" + UUID.randomUUID() + "'");
      st.execute(insertTask(UUID.randomUUID(), tenant, period));
      svc.commit();
      // Sau giao dịch biến về chuỗi rỗng: lại bị chặn.
      assertThatThrownBy(() -> st.execute(insertTask(UUID.randomUUID(), tenant, period)))
          .hasMessageContaining("mã yêu cầu mở lại");
      svc.rollback();
      svc.setAutoCommit(true);
      setState(st, period, "MO");
      st.execute(insertTask(UUID.randomUUID(), tenant, period));
    }
  }

  /**
   * Chi phí trigger: chèn 10.000 nhiệm vụ có và không có trigger (session_replication_role =
   * replica tắt trigger), lấy hiệu số chia số dòng. TL-03 ước tính dưới 20 µs/dòng; ngưỡng kiểm thử
   * nới rộng để không chập chờn trên máy CI, con số đo được in ra để ghi vào báo cáo.
   */
  @Test
  void chiPhiTriggerTrenMuoiNghinDong() throws SQLException {
    UUID tenant = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    try (Connection c =
            TestPostgres.connectAsTenant(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), tenant);
        Statement st = c.createStatement()) {
      setState(st, period, "MO");
      bulkInsert(st, tenant, period, "w", 2_000); // làm nóng
      long best = Long.MAX_VALUE;
      long bestBaseline = Long.MAX_VALUE;
      for (int round = 0; round < 3; round++) {
        st.execute("SET session_replication_role = replica");
        bestBaseline = Math.min(bestBaseline, bulkInsert(st, tenant, period, "b" + round, 10_000));
        st.execute("SET session_replication_role = origin");
        best = Math.min(best, bulkInsert(st, tenant, period, "g" + round, 10_000));
      }
      double microsPerRow = Math.max(0, best - bestBaseline) / 1_000.0 / 10_000;
      System.out.printf(
          "CORE-04-01 chi phí trigger period_guard: %.2f µs/dòng (có trigger %d ms, không %d ms)%n",
          microsPerRow, best / 1_000_000, bestBaseline / 1_000_000);
      assertThat(microsPerRow).isLessThan(200.0);
    }
  }

  // ---------------------------------------------------------------- hỗ trợ

  private static long bulkInsert(Statement st, UUID tenant, UUID period, String prefix, int rows)
      throws SQLException {
    long start = System.nanoTime();
    st.execute(
        "INSERT INTO task.task (id, tenant_id, created_by, updated_by, period_id, period_bucket,"
            + " code, title, task_group, source, assigner_id, due_date, unit, dup_fingerprint,"
            + " state_machine_version) SELECT gen_random_uuid(), '"
            + tenant
            + "', gen_random_uuid(), gen_random_uuid(), '"
            + period
            + "', "
            + BUCKET
            + ", '"
            + prefix
            + "-' || g, 'Nhiệm vụ đo', 'THUONG_XUYEN', 'DOT_XUAT', gen_random_uuid(),"
            + " date '2027-03-31', 'Việc', '\\x01', 'v1' FROM generate_series(1, "
            + rows
            + ") g");
    return System.nanoTime() - start;
  }

  private static void setState(Statement st, UUID period, String status) throws SQLException {
    st.execute(
        "INSERT INTO task.period_state (period_id, status, source_event_id) VALUES ('"
            + period
            + "', '"
            + status
            + "', gen_random_uuid()) ON CONFLICT (tenant_id, period_id) DO UPDATE SET status ="
            + " excluded.status, source_event_id = excluded.source_event_id, updated_at = now()");
  }

  private static String insertTask(UUID id, UUID tenant, UUID period) {
    return "INSERT INTO task.task (id, tenant_id, created_by, updated_by, period_id, period_bucket,"
        + " code, title, task_group, source, assigner_id, due_date, unit, dup_fingerprint,"
        + " state_machine_version) VALUES ('"
        + id
        + "', '"
        + tenant
        + "', gen_random_uuid(), gen_random_uuid(), '"
        + period
        + "', "
        + BUCKET
        + ", '"
        + id.toString().substring(0, 30)
        + "', 'Nhiệm vụ thử', 'THUONG_XUYEN', 'DOT_XUAT', gen_random_uuid(), date '2027-03-31',"
        + " 'Việc', '\\x01', 'v1')";
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private static Connection svc(UUID tenant) throws SQLException {
    Connection c =
        TestPostgres.connectAsTenant(
            pg.getJdbcUrl(), "svc_task", MissionCoreTestDatabase.TEST_DB_PASSWORD, tenant);
    return c;
  }
}
