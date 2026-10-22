package vn.viettel.okrkpi.appraisal.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;

/**
 * Lớp bảo vệ thứ hai ở CSDL cho dữ liệu kỳ đã khoá (CORE-04-01, TL-03 "Bảo vệ dữ liệu kỳ đã khoá ở
 * tầng CSDL"): {@code period_state} và {@code fn_guard_period_write} của {@code task}, {@code
 * product}, {@code okr}, chạy bằng vai trò ứng dụng như đường ghi thật, cộng phép đo chi phí
 * trigger.
 */
class PeriodGuardIT {

  private static final int BUCKET = 20271;
  private static final String PWD = MissionCoreTestDatabase.TEST_DB_PASSWORD;
  private static PostgreSQLContainer<?> pg;

  @BeforeAll
  static void moPhanVung() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    for (String[] t :
        new String[][] {
          {"task", "task"}, {"product", "deliverable"}, {"okr", "objective"}, {"okr", "key_result"}
        }) {
      try (Connection c = svc(t[0], null);
          Statement st = c.createStatement()) {
        st.execute(
            "SELECT "
                + t[0]
                + ".fn_create_period_partitions('"
                + t[0]
                + "."
                + t[1]
                + "', "
                + BUCKET
                + ")");
      }
    }
  }

  @Test
  void ghiNhiemVuKhiKyDaKhoaBiChan() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    try (Connection c = svc("task", t);
        Statement st = c.createStatement()) {
      UUID task = UUID.randomUUID();
      st.execute(insertTask(t, period, task, "NV-1"));
      setState(st, "task", t, period, "DA_KHOA");
      assertThatThrownBy(() -> st.execute(insertTask(t, period, UUID.randomUUID(), "NV-2")))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getSQLState()).isEqualTo("55000");
                assertThat(e.getMessage()).contains("đã khoá");
              });
      assertThatThrownBy(
              () -> st.execute("UPDATE task.task SET title = 'Sửa' WHERE id = '" + task + "'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
      setState(st, "task", t, period, "DANG_KHOA");
      assertThatThrownBy(() -> st.execute(insertTask(t, period, UUID.randomUUID(), "NV-3")))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
      setState(st, "task", t, period, "MO");
      st.execute(insertTask(t, period, UUID.randomUUID(), "NV-4"));
    }
  }

  @Test
  void moLaiHauKiemChiGhiKhiCoMaYeuCauMoLai() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    try (Connection c = svc("task", t);
        Statement st = c.createStatement()) {
      setState(st, "task", t, period, "MO_LAI_HAU_KIEM");
      assertThatThrownBy(() -> st.execute(insertTask(t, period, UUID.randomUUID(), "NV-1")))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getMessage()).contains("mở lại hậu kiểm"));
      c.setAutoCommit(false);
      st.execute("SET LOCAL app.reopen_request_id = '" + UUID.randomUUID() + "'");
      st.execute(insertTask(t, period, UUID.randomUUID(), "NV-2"));
      c.commit();
      c.setAutoCommit(true);
      // SET LOCAL hết hiệu lực sau giao dịch: biến về chuỗi rỗng và lại bị chặn.
      assertThatThrownBy(() -> st.execute(insertTask(t, period, UUID.randomUUID(), "NV-3")))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
    }
  }

  @Test
  void kyKhoaChiChanDungTenantVaDungKy() throws SQLException {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    try (Connection c = svc("task", a);
        Statement st = c.createStatement()) {
      setState(st, "task", a, period, "DA_KHOA");
      st.execute(insertTask(a, UUID.randomUUID(), UUID.randomUUID(), "NV-1"));
    }
    try (Connection c = svc("task", b);
        Statement st = c.createStatement()) {
      st.execute(insertTask(b, period, UUID.randomUUID(), "NV-1"));
    }
  }

  @Test
  void sanPhamVaMucTieuCungBiChan() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    try (Connection c = svc("product", t);
        Statement st = c.createStatement()) {
      setState(st, "product", t, period, "DA_KHOA");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "INSERT INTO product.deliverable (id, created_by, updated_by, period_id,"
                          + " period_bucket, task_id, name, deliverable_type, unit,"
                          + " quantity_planned) VALUES (gen_random_uuid(), '"
                          + t
                          + "', '"
                          + t
                          + "', '"
                          + period
                          + "', "
                          + BUCKET
                          + ", gen_random_uuid(), 'Báo cáo', 'BAO_CAO', 'Báo cáo', 1)"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
    }
    try (Connection c = svc("okr", t);
        Statement st = c.createStatement()) {
      setState(st, "okr", t, period, "DA_KHOA");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "INSERT INTO okr.objective (id, created_by, updated_by, period_id,"
                          + " period_bucket, owner_type, owner_unit_id, code, title, tier) VALUES"
                          + " (gen_random_uuid(), '"
                          + t
                          + "', '"
                          + t
                          + "', '"
                          + period
                          + "', "
                          + BUCKET
                          + ", 'TAP_THE', gen_random_uuid(), 'O1', 'Mục tiêu', 1)"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
    }
  }

  /**
   * Đo chi phí trigger trên 10.000 dòng ghi {@code task.task}: cùng một lệnh chèn hàng loạt chạy
   * với trigger bật và tắt, lấy trung vị của năm lần; kết quả in ra để ghi vào báo cáo (TL-03 ước
   * tính dưới 20 µs mỗi dòng). Ngưỡng kiểm thử nới rộng để không phụ thuộc máy chạy.
   */
  @Test
  void chiPhiTriggerTrenMuoiNghinDong() throws SQLException {
    UUID t = UUID.randomUUID();
    long[] on = new long[5];
    long[] off = new long[5];
    try (Connection admin =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement adm = admin.createStatement();
        Connection c = svc("task", t);
        Statement st = c.createStatement()) {
      UUID period = UUID.randomUUID();
      setState(st, "task", t, period, "MO");
      for (int i = 0; i < on.length; i++) {
        on[i] = timedBulkInsert(st, t, period, "B" + i);
        adm.execute("ALTER TABLE task.task DISABLE TRIGGER tg_task__period_guard");
        try {
          off[i] = timedBulkInsert(st, t, period, "K" + i);
        } finally {
          adm.execute("ALTER TABLE task.task ENABLE TRIGGER tg_task__period_guard");
        }
      }
    }
    java.util.Arrays.sort(on);
    java.util.Arrays.sort(off);
    double perRowMicros = (on[2] - off[2]) / 1000.0 / 10_000;
    System.out.printf(
        "CORE-04-01 chi phí trigger task.task: bật %d ms, tắt %d ms, %.2f µs/dòng%n",
        on[2] / 1_000_000, off[2] / 1_000_000, perRowMicros);
    assertThat(perRowMicros).isLessThan(100.0);
  }

  private static long timedBulkInsert(Statement st, UUID t, UUID period, String prefix)
      throws SQLException {
    long start = System.nanoTime();
    st.execute(
        "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket, code, title,"
            + " task_group, source, assigner_id, due_date, unit, dup_fingerprint,"
            + " state_machine_version) SELECT gen_random_uuid(), '"
            + t
            + "', '"
            + t
            + "', '"
            + period
            + "', "
            + BUCKET
            + ", '"
            + prefix
            + "-' || g, 'Nhiệm vụ đo', 'THUONG_XUYEN', 'KE_HOACH', '"
            + t
            + "', DATE '2027-03-31', 'Hồ sơ', '\\x0102', 'task-v1'"
            + " FROM generate_series(1, 10000) g");
    return System.nanoTime() - start;
  }

  private static String insertTask(UUID t, UUID period, UUID id, String code) {
    return "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket, code,"
        + " title, task_group, source, assigner_id, due_date, unit, dup_fingerprint,"
        + " state_machine_version) VALUES ('"
        + id
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + period
        + "', "
        + BUCKET
        + ", '"
        + code
        + "', 'Rà soát hồ sơ', 'THUONG_XUYEN', 'KE_HOACH', '"
        + t
        + "', DATE '2027-03-31', 'Hồ sơ', '\\x010203', 'task-v1')";
  }

  private static void setState(Statement st, String schema, UUID t, UUID period, String status)
      throws SQLException {
    st.execute(
        "INSERT INTO "
            + schema
            + ".period_state (tenant_id, period_id, status, source_event_id) VALUES ('"
            + t
            + "', '"
            + period
            + "', '"
            + status
            + "', gen_random_uuid()) ON CONFLICT (tenant_id, period_id) DO UPDATE SET status ="
            + " EXCLUDED.status");
  }

  private static Connection svc(String schema, UUID tenant) throws SQLException {
    Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "svc_" + schema, PWD);
    if (tenant != null) {
      try (Statement st = c.createStatement()) {
        st.execute("SET app.tenant_id = '" + tenant + "'");
      }
    }
    return c;
  }
}
