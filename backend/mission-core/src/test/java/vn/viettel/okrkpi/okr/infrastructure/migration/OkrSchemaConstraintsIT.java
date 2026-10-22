package vn.viettel.okrkpi.okr.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;

/**
 * Ràng buộc toàn vẹn của schema {@code okr} (CORE-01-01, TL-03 "Schema okr"): tổng trọng số KR ≤ 1
 * kiểm khi commit, check-in đã gửi không sửa, xoá được, nhập khác nguồn phải có lý do, hai tenant
 * không đọc được dòng của nhau, và truy vấn mục tiêu có {@code period_bucket} chỉ chạm một phân
 * vùng. Mọi thao tác chạy bằng vai trò {@code svc_okr} như ứng dụng thật.
 */
class OkrSchemaConstraintsIT {

  private static final UUID TENANT_A = UUID.fromString("01927a01-0000-7000-8000-00000000a001");
  private static final UUID TENANT_B = UUID.fromString("01927a01-0000-7000-8000-00000000b001");
  private static final UUID ACTOR = UUID.fromString("01927a01-0000-7000-8000-0000000000ac");
  private static final UUID PERIOD = UUID.fromString("01927a01-0000-7000-8000-0000000000e1");
  private static final UUID UNIT = UUID.fromString("01927a01-0000-7000-8000-0000000000f1");
  private static final int BUCKET = 20271;
  private static final List<String> PERIODIC_TABLES =
      List.of("objective", "objective_basis", "objective_review", "key_result", "kr_checkin");

  private static final PostgreSQLContainer<?> PG = MissionCoreTestDatabase.container();

  @FunctionalInterface
  private interface Work {
    void run(Connection c) throws SQLException;
  }

  @BeforeAll
  static void createPartitions() throws SQLException {
    try (Connection c = svc()) {
      for (String table : PERIODIC_TABLES) {
        for (int bucket : List.of(BUCKET, BUCKET + 1)) {
          query(
              c, "SELECT okr.fn_create_period_partitions(?::regclass, ?)", "okr." + table, bucket);
        }
      }
    }
  }

  @Test
  void tongTrongSoKrVuotMotBiTuChoiKhiCommit() throws SQLException {
    assertSqlState(
        () ->
            committed(
                TENANT_A,
                c -> {
                  UUID objective = insertObjective(c, BUCKET);
                  insertKeyResult(c, objective, "0.4");
                  insertKeyResult(c, objective, "0.4");
                  insertKeyResult(c, objective, "0.4");
                }),
        "23514");
    // Trong một giao dịch được vượt tạm thời, miễn đến lúc commit tổng ≤ 1.
    committed(
        TENANT_A,
        c -> {
          UUID objective = insertObjective(c, BUCKET);
          UUID first = insertKeyResult(c, objective, "0.6");
          insertKeyResult(c, objective, "0.6");
          exec(c, "UPDATE okr.key_result SET weight = 0.4 WHERE id = ?", first);
        });
  }

  @Test
  void checkInDaGuiKhongSuaXoaDuocChiGhiXacNhan() throws SQLException {
    UUID checkin = UUID.randomUUID();
    committed(TENANT_A, c -> insertCheckin(c, checkin, "5", null, null, "DA_GUI"));
    committed(
        TENANT_A,
        c ->
            assertThat(
                    exec(
                        c,
                        "UPDATE okr.kr_checkin SET verified_by = ?, verified_at = now() WHERE id = ?",
                        ACTOR,
                        checkin))
                .isEqualTo(1));
    assertSqlState(
        () ->
            committed(
                TENANT_A,
                c -> exec(c, "UPDATE okr.kr_checkin SET status = 'NHAP' WHERE id = ?", checkin)),
        "23000");
    assertSqlState(
        () ->
            committed(
                TENANT_A,
                c -> exec(c, "UPDATE okr.kr_checkin SET value = 9 WHERE id = ?", checkin)),
        "42501");
    assertSqlState(
        () -> committed(TENANT_A, c -> exec(c, "DELETE FROM okr.kr_checkin WHERE id = ?", checkin)),
        "42501");
    // Check-in nháp được gửi đi.
    UUID draft = UUID.randomUUID();
    committed(TENANT_A, c -> insertCheckin(c, draft, "5", null, null, "NHAP"));
    committed(
        TENANT_A,
        c ->
            assertThat(exec(c, "UPDATE okr.kr_checkin SET status = 'DA_GUI' WHERE id = ?", draft))
                .isEqualTo(1));
  }

  @Test
  void nhapKhacNguonPhaiCoLyDo() throws SQLException {
    assertSqlState(
        () -> committed(TENANT_A, c -> insertCheckin(c, UUID.randomUUID(), "7", "5", null, "NHAP")),
        "23514");
    assertSqlState(
        () -> committed(TENANT_A, c -> insertCheckin(c, UUID.randomUUID(), "7", "5", " ", "NHAP")),
        "23514");
    committed(
        TENANT_A,
        c -> {
          insertCheckin(c, UUID.randomUUID(), "7", "5", "Số liệu nguồn chưa cập nhật", "NHAP");
          insertCheckin(c, UUID.randomUUID(), "5", "5", null, "NHAP");
        });
  }

  @Test
  void haiTenantKhongDocDuocDongCuaNhau() throws SQLException {
    UUID[] objective = new UUID[1];
    committed(TENANT_A, c -> objective[0] = insertObjective(c, BUCKET));
    committed(
        TENANT_B,
        c -> {
          assertThat(count(c, "SELECT count(*) FROM okr.objective WHERE id = ?", objective[0]))
              .isZero();
          assertThat(exec(c, "UPDATE okr.objective SET title = 'x' WHERE id = ?", objective[0]))
              .isZero();
        });
    committed(
        TENANT_A,
        c ->
            assertThat(count(c, "SELECT count(*) FROM okr.objective WHERE id = ?", objective[0]))
                .isEqualTo(1));
  }

  @Test
  void truyVanMucTieuTheoKyChiChamMotPhanVung() throws SQLException {
    committed(
        TENANT_A,
        c -> {
          insertObjective(c, BUCKET);
          insertObjective(c, BUCKET + 1);
          StringBuilder plan = new StringBuilder();
          try (PreparedStatement st =
              c.prepareStatement(
                  "EXPLAIN (FORMAT TEXT) SELECT * FROM okr.objective WHERE tenant_id = ? AND"
                      + " owner_unit_id = ? AND period_id = ? AND period_bucket = ?")) {
            st.setObject(1, TENANT_A);
            st.setObject(2, UNIT);
            st.setObject(3, PERIOD);
            st.setInt(4, BUCKET);
            try (ResultSet rs = st.executeQuery()) {
              while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
              }
            }
          }
          assertThat(plan.toString())
              .contains("objective_p" + BUCKET)
              .doesNotContain("objective_p" + (BUCKET + 1));
        });
  }

  private static UUID insertObjective(Connection c, int bucket) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type, owner_unit_id, code,"
            + " title, tier, created_by, updated_by) VALUES (?, ?, ?, 'TAP_THE', ?, 'O1',"
            + " 'Nâng cao chất lượng sinh hoạt chi bộ', 1, ?, ?)",
        id,
        PERIOD,
        bucket,
        UNIT,
        ACTOR,
        ACTOR);
    return id;
  }

  private static UUID insertKeyResult(Connection c, UUID objective, String weight)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO okr.key_result (id, period_id, period_bucket, objective_id, code, title,"
            + " metric_type, unit, target_value, direction, weight, due_date, verifier_person_id,"
            + " checkin_frequency, created_by, updated_by) VALUES (?, ?, ?, ?, 'KR 1.1',"
            + " 'Tỷ lệ đảng viên dự sinh hoạt', 'TY_LE', '%', 95, 'TANG', ?, ?, ?, 'THANG', ?, ?)",
        id,
        PERIOD,
        BUCKET,
        objective,
        new BigDecimal(weight),
        LocalDate.of(2027, 3, 31),
        ACTOR,
        ACTOR,
        ACTOR);
    return id;
  }

  private static void insertCheckin(
      Connection c, UUID id, String value, String sourceValue, String reason, String status)
      throws SQLException {
    exec(
        c,
        "INSERT INTO okr.kr_checkin (id, period_id, period_bucket, key_result_id, value,"
            + " confidence, channel, idempotency_key, status, source_value, override_reason,"
            + " created_by, updated_by) VALUES (?, ?, ?, ?, ?, 'CAO', 'WEB', ?, ?, ?, ?, ?, ?)",
        id,
        PERIOD,
        BUCKET,
        UUID.randomUUID(),
        new BigDecimal(value),
        id.toString(),
        status,
        sourceValue == null ? null : new BigDecimal(sourceValue),
        reason,
        ACTOR,
        ACTOR);
  }

  /** Chạy {@code work} trong một giao dịch của tenant và commit, để trigger hoãn được kiểm. */
  private static void committed(UUID tenant, Work work) throws SQLException {
    try (Connection c = svc()) {
      c.setAutoCommit(false);
      try {
        query(c, "SELECT set_config('app.tenant_id', ?, true)", tenant.toString());
        work.run(c);
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private static Connection svc() throws SQLException {
    return DriverManager.getConnection(
        PG.getJdbcUrl(), "svc_okr", MissionCoreTestDatabase.TEST_DB_PASSWORD);
  }

  private static int exec(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement st = c.prepareStatement(sql)) {
      bind(st, params);
      return st.executeUpdate();
    }
  }

  private static void query(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement st = c.prepareStatement(sql)) {
      bind(st, params);
      st.executeQuery().close();
    }
  }

  private static long count(Connection c, String sql, Object param) throws SQLException {
    try (PreparedStatement st = c.prepareStatement(sql)) {
      st.setObject(1, param);
      try (ResultSet rs = st.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void bind(PreparedStatement st, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      st.setObject(i + 1, params[i]);
    }
  }

  private static void assertSqlState(ThrowingCallable call, String sqlState) {
    assertThatThrownBy(call)
        .isInstanceOf(SQLException.class)
        .extracting(e -> ((SQLException) e).getSQLState())
        .isEqualTo(sqlState);
  }
}
