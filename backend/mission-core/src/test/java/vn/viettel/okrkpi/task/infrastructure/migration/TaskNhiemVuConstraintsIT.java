package vn.viettel.okrkpi.task.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;

/**
 * Ràng buộc nghiệp vụ và kế hoạch truy vấn của phần nhiệm vụ (CORE-02-02) ở CSDL pool. Kế thừa
 * {@link TaskTenantIsolationIT} để dùng chung container, migration và dòng mẫu; ba kiểm thử cô lập
 * của lớp cơ sở vì thế chạy lại một lần ở lớp này.
 */
class TaskNhiemVuConstraintsIT extends TaskTenantIsolationIT {

  private static final int PERF_BUCKET = 20272;
  private static final String ACTIVE = "('DA_GIAO', 'DANG_THUC_HIEN', 'CHO_NGHIEM_THU')";

  @Test
  void chuTriThuHaiConHieuLucBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID task = insertTask(st, t, "'DA_GIAO'", "NULL");
      UUID first = UUID.randomUUID();
      st.execute(assignee(first, t, task, UUID.randomUUID(), "CHU_TRI"));
      assertThatThrownBy(
              () -> st.execute(assignee(UUID.randomUUID(), t, task, UUID.randomUUID(), "CHU_TRI")))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
      st.execute(assignee(UUID.randomUUID(), t, task, UUID.randomUUID(), "PHOI_HOP"));
      // Giao lại: chủ trì cũ rút khỏi nhiệm vụ thì chủ trì mới được thêm.
      st.execute("UPDATE task.task_assignee SET removed_at = now() WHERE id = '" + first + "'");
      st.execute(assignee(UUID.randomUUID(), t, task, UUID.randomUUID(), "CHU_TRI"));
    }
  }

  @Test
  void cungNguoiCungVaiTroTrungKhoangHieuLucBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID task = insertTask(st, t, "'DA_GIAO'", "NULL");
      UUID person = UUID.randomUUID();
      st.execute(assignee(UUID.randomUUID(), t, task, person, "PHOI_HOP"));
      assertThatThrownBy(() -> st.execute(assignee(UUID.randomUUID(), t, task, person, "PHOI_HOP")))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23P01"));
    }
  }

  @Test
  void hoanThanhThieuThoiDiemHoanThanhBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      assertThatThrownBy(() -> insertTask(st, t, "'HOAN_THANH'", "NULL"))
          .hasMessageContaining("ck_task__done");
      assertThatThrownBy(() -> insertTask(st, t, "'DA_XONG'", "NULL"))
          .hasMessageContaining("ck_task__status");
      insertTask(st, t, "'HOAN_THANH'", "now()");
    }
  }

  @Test
  void nguoiDuyetTrungNguoiDeNghiBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID task = insertTask(st, t, "'DANG_THUC_HIEN'", "NULL");
      UUID requester = UUID.randomUUID();
      assertThatThrownBy(() -> st.execute(changeRequest(t, task, requester, requester)))
          .hasMessageContaining("ck_task_change_request__four_eyes");
      st.execute(changeRequest(t, task, requester, UUID.randomUUID()));
    }
  }

  @Test
  void vaiTroUngDungKhongSuaXoaDuocNhatKyTrangThai() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID task = insertTask(st, t, "'DA_GIAO'", "NULL");
      st.execute(
          "INSERT INTO task.task_state_log (id, tenant_id, period_id, period_bucket, task_id,"
              + " from_status, to_status, transition, actor_id, before_after,"
              + " state_machine_version) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + t
              + "', '"
              + t
              + "', "
              + SAMPLE_PERIOD_BUCKET
              + ", '"
              + task
              + "', 'NHAP', 'DA_GIAO', 'GIAO', '"
              + t
              + "', '{}', 'task-v1')");
    }
    try (Connection svc = svc(t);
        Statement st = svc.createStatement()) {
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM task.task_state_log")) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
      assertThatThrownBy(() -> st.execute("UPDATE task.task_state_log SET reason = 'sửa'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(() -> st.execute("DELETE FROM task.task_state_log"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(() -> st.execute("DELETE FROM task.task_progress"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
  }

  /**
   * 1 triệu nhiệm vụ và 1 triệu dòng chủ trì ở kỳ riêng (20272), 10 tenant: kế hoạch "Việc của tôi"
   * và "Nhiệm vụ quá hạn" chỉ chạm phân vùng của kỳ và tenant, dùng chỉ mục; p95 "Việc của tôi" qua
   * vai trò ứng dụng (có RLS) không quá 20 ms (ngưỡng suy luận của CORE-02-02).
   */
  @Test
  void viecCuaToiVaQuaHanChamDungPhanVungDungChiMucVaDuNhanh() throws SQLException {
    List<UUID> tenants = new ArrayList<>();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      createPartitions(PERF_BUCKET);
      // Id sinh tất định nên khoá ngoại khớp; bỏ trigger kiểm khoá ngoại khi nạp hàng loạt.
      st.execute("SET session_replication_role = replica");
      for (int i = 0; i < 10; i++) {
        tenants.add(UUID.nameUUIDFromBytes(("perf-tenant-" + i).getBytes()));
      }
      for (UUID t : tenants) {
        // 100.000 nhiệm vụ mỗi tenant, 2.000 người, mỗi người 50 nhiệm vụ; khoảng 2% quá hạn.
        st.execute(
            "INSERT INTO task.task (id, tenant_id, created_by, updated_by, period_id, period_bucket,"
                + " code, title, task_group, source, assigner_id, due_date, unit, status,"
                + " completed_at, is_overdue, dup_fingerprint, state_machine_version) SELECT"
                + " md5('"
                + t
                + "' || g)::uuid, '"
                + t
                + "', '"
                + t
                + "', '"
                + t
                + "', '"
                + t
                + "', "
                + PERF_BUCKET
                + ", 'NV-' || g, 'Nhiệm vụ ' || g, 'THUONG_XUYEN', 'KE_HOACH', '"
                + t
                + "', DATE '2027-04-01' + (g % 90), 'Hồ sơ', (ARRAY['NHAP', 'DA_GIAO',"
                + " 'DANG_THUC_HIEN', 'CHO_NGHIEM_THU', 'HOAN_THANH'])[1 + g % 5],"
                + " CASE WHEN g % 5 = 4 THEN now() END, g % 50 = 0, '\\x01'::bytea, 'task-v1'"
                + " FROM generate_series(1, 100000) g");
        st.execute(
            "INSERT INTO task.task_assignee (id, tenant_id, created_by, updated_by, period_id,"
                + " period_bucket, task_id, assignee_type, person_id, role, valid_from) SELECT"
                + " md5('a' || '"
                + t
                + "' || g)::uuid, '"
                + t
                + "', '"
                + t
                + "', '"
                + t
                + "', '"
                + t
                + "', "
                + PERF_BUCKET
                + ", md5('"
                + t
                + "' || g)::uuid, 'CA_NHAN', md5('p' || '"
                + t
                + "' || (g % 2000))::uuid, 'CHU_TRI', DATE '2027-04-01'"
                + " FROM generate_series(1, 100000) g");
      }
      st.execute("SET session_replication_role = DEFAULT");
      st.execute("ANALYZE task.task");
      st.execute("ANALYZE task.task_assignee");
    }

    UUID t = tenants.get(3);
    try (Connection svc = svc(t);
        Statement st = svc.createStatement()) {
      String mine = explain(st, myTasks(t, person(t, 7)));
      assertThat(mine).contains("task_p" + PERF_BUCKET + "_h").doesNotContain("p20271");
      assertThat(mine).doesNotContain("Seq Scan");
      assertThat(countHashPartitions(mine, "task_assignee_p" + PERF_BUCKET)).isEqualTo(1);

      String overdue = explain(st, overdue(t));
      assertThat(overdue).contains("task_p" + PERF_BUCKET + "_h").doesNotContain("p20271");
      assertThat(overdue).doesNotContain("Seq Scan");
      assertThat(countHashPartitions(overdue, "task_p" + PERF_BUCKET)).isEqualTo(1);

      List<Long> nanos = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
        // Chỉ người có số thứ tự chia 5 dư 1, 2, 3 mới có nhiệm vụ đang thực hiện.
        String sql = myTasks(t, person(t, (i * 5 + 1 + i % 3) % 2000));
        long start = System.nanoTime();
        try (ResultSet rs = st.executeQuery(sql)) {
          int n = 0;
          while (rs.next()) {
            n++;
          }
          assertThat(n).isPositive();
        }
        if (i >= 50) {
          nanos.add(System.nanoTime() - start);
        }
      }
      nanos.sort(Long::compare);
      long p95Ms = nanos.get((int) Math.ceil(nanos.size() * 0.95) - 1) / 1_000_000;
      System.out.println("CORE-02-02 p95 Việc của tôi (1 triệu nhiệm vụ): " + p95Ms + " ms");
      assertThat(p95Ms).isLessThanOrEqualTo(20);
    }
  }

  private static String myTasks(UUID t, UUID person) {
    return "SELECT t.id, t.code, t.title, t.due_date, t.status, t.progress_pct FROM"
        + " task.task_assignee a JOIN task.task t ON t.period_bucket = a.period_bucket AND"
        + " t.tenant_id = a.tenant_id AND t.id = a.task_id WHERE a.tenant_id = '"
        + t
        + "' AND a.person_id = '"
        + person
        + "' AND a.period_bucket = "
        + PERF_BUCKET
        + " AND a.removed_at IS NULL AND t.tenant_id = '"
        + t
        + "' AND t.period_bucket = "
        + PERF_BUCKET
        + " AND t.status IN "
        + ACTIVE
        + " ORDER BY t.due_date LIMIT 50";
  }

  private static String overdue(UUID t) {
    return "SELECT id, code, title, due_date FROM task.task WHERE tenant_id = '"
        + t
        + "' AND period_bucket = "
        + PERF_BUCKET
        + " AND is_overdue ORDER BY due_date LIMIT 200";
  }

  private static UUID person(UUID t, int n) {
    return md5Uuid("p" + t + n);
  }

  private static UUID md5Uuid(String s) {
    try {
      byte[] d = java.security.MessageDigest.getInstance("MD5").digest(s.getBytes());
      StringBuilder hex = new StringBuilder();
      for (byte b : d) {
        hex.append(String.format("%02x", b));
      }
      String h = hex.toString();
      return UUID.fromString(
          h.substring(0, 8)
              + "-"
              + h.substring(8, 12)
              + "-"
              + h.substring(12, 16)
              + "-"
              + h.substring(16, 20)
              + "-"
              + h.substring(20));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String explain(Statement st, String sql) throws SQLException {
    StringBuilder plan = new StringBuilder();
    try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
      while (rs.next()) {
        plan.append(rs.getString(1)).append('\n');
      }
    }
    return plan.toString();
  }

  private static long countHashPartitions(String plan, String prefix) {
    return java.util.regex.Pattern.compile(prefix + "_h\\d+")
        .matcher(plan)
        .results()
        .map(java.util.regex.MatchResult::group)
        .distinct()
        .count();
  }

  private void createPartitions(int bucket) throws SQLException {
    try (Connection svc = DriverManager.getConnection(jdbcUrl(), "svc_task", TEST_PASSWORD)) {
      for (String table :
          List.of(
              "task",
              "task_assignee",
              "task_progress",
              "task_blocker",
              "task_change_request",
              "task_source_doc",
              "task_state_log")) {
        try (PreparedStatement ps =
            svc.prepareStatement(
                "SELECT task.fn_create_period_partitions('task." + table + "', ?)")) {
          ps.setInt(1, bucket);
          ps.executeQuery().close();
        }
      }
    }
  }

  private Connection admin() throws SQLException {
    Connection c =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    ensurePeriodPartitionsCreated(jdbcUrl(), c, schemas());
    return c;
  }

  private Connection svc(UUID tenant) throws SQLException {
    Connection c = TestPostgres.connectAsTenant(jdbcUrl(), "svc_task", TEST_PASSWORD, tenant);
    return c;
  }

  private static UUID insertTask(Statement st, UUID t, String status, String completedAt)
      throws SQLException {
    UUID id = UUID.randomUUID();
    st.execute(
        "INSERT INTO task.task (id, tenant_id, created_by, updated_by, period_id, period_bucket,"
            + " code, title, task_group, source, assigner_id, due_date, unit, status, completed_at,"
            + " dup_fingerprint, state_machine_version) VALUES ('"
            + id
            + "', '"
            + t
            + "', '"
            + t
            + "', '"
            + t
            + "', '"
            + t
            + "', "
            + SAMPLE_PERIOD_BUCKET
            + ", 'NV-"
            + id.toString().substring(0, 8)
            + "', 'Tổng hợp báo cáo', 'THEN_CHOT', 'DOT_XUAT', '"
            + t
            + "', DATE '2027-03-31', 'Báo cáo', "
            + status
            + ", "
            + completedAt
            + ", '\\x01'::bytea, 'task-v1')");
    return id;
  }

  private static String assignee(UUID id, UUID t, UUID task, UUID person, String role) {
    return "INSERT INTO task.task_assignee (id, tenant_id, created_by, updated_by, period_id,"
        + " period_bucket, task_id, assignee_type, person_id, role, valid_from) VALUES ('"
        + id
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + t
        + "', "
        + SAMPLE_PERIOD_BUCKET
        + ", '"
        + task
        + "', 'CA_NHAN', '"
        + person
        + "', '"
        + role
        + "', DATE '2027-01-01')";
  }

  private static String changeRequest(UUID t, UUID task, UUID requester, UUID decider) {
    return "INSERT INTO task.task_change_request (id, tenant_id, created_by, updated_by, period_id,"
        + " period_bucket, task_id, request_type, proposed_due_date, reason, status, decided_by,"
        + " decided_at, cause_decided) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + t
        + "', '"
        + requester
        + "', '"
        + requester
        + "', '"
        + t
        + "', "
        + SAMPLE_PERIOD_BUCKET
        + ", '"
        + task
        + "', 'GIA_HAN', DATE '2027-04-30', 'Chờ số liệu của đơn vị phối hợp', 'DA_DUYET', '"
        + decider
        + "', now(), 'KHACH_QUAN')";
  }
}
