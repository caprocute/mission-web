package vn.viettel.okrkpi.config.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;

/**
 * Ràng buộc nghiệp vụ ở tầng CSDL của schema {@code config} (CORE-08-01, TL-03 "Schema config"):
 * năm chuyển trạng thái kỳ hợp lệ, kỳ không chồng lấn, bốn mắt ở {@code role_binding} và {@code
 * period_lock_order}, nhật ký trạng thái kỳ chỉ ghi thêm, cô lập tenant và migration chạy lại không
 * đổi gì. Mọi thao tác chạy bằng vai trò {@code svc_config} như ứng dụng thật.
 */
class ConfigSchemaConstraintsIT {

  private static final UUID TENANT_A = UUID.fromString("01927a08-0000-7000-8000-00000000a001");
  private static final UUID TENANT_B = UUID.fromString("01927a08-0000-7000-8000-00000000b001");
  private static final UUID ACTOR = UUID.fromString("01927a08-0000-7000-8000-0000000000ac");
  private static final UUID OTHER = UUID.fromString("01927a08-0000-7000-8000-0000000000ad");
  private static final List<String> STATUSES =
      List.of("MO", "DANG_KHOA", "DA_KHOA", "MO_LAI_HAU_KIEM");
  private static final Set<String> VALID =
      Set.of(
          "MO>DANG_KHOA",
          "DANG_KHOA>MO",
          "DANG_KHOA>DA_KHOA",
          "DA_KHOA>MO_LAI_HAU_KIEM",
          "MO_LAI_HAU_KIEM>DA_KHOA");

  private static final PostgreSQLContainer<?> PG = MissionCoreTestDatabase.container();

  @FunctionalInterface
  private interface Work {
    void run(Connection c) throws SQLException;
  }

  static List<Arguments> transitions() {
    return STATUSES.stream()
        .flatMap(
            from ->
                STATUSES.stream().filter(to -> !to.equals(from)).map(to -> Arguments.of(from, to)))
        .toList();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("transitions")
  void chiNamChuyenTrangThaiKyHopLeDuocQua(String from, String to) throws SQLException {
    Work change =
        c -> {
          UUID period = insertPeriod(c, "QUY", "DANG", "2027-01-01", "2027-03-31", from);
          try (PreparedStatement st =
              c.prepareStatement(
                  "UPDATE config.period SET status = ?, locked_at = coalesce(locked_at, now())"
                      + " WHERE id = ?")) {
            st.setString(1, to);
            st.setObject(2, period);
            assertThat(st.executeUpdate()).isEqualTo(1);
          }
        };
    if (VALID.contains(from + ">" + to)) {
      inTenant(TENANT_A, change);
    } else {
      assertSqlState(() -> inTenant(TENANT_A, change), "23514");
    }
  }

  @Test
  void kyCungLoaiCungLuongChongLanBiChanKhacLuongThiDuoc() throws SQLException {
    assertSqlState(
        () ->
            inTenant(
                TENANT_A,
                c -> {
                  insertPeriod(c, "QUY", "DANG", "2027-01-01", "2027-03-31", "MO");
                  insertPeriod(c, "QUY", "DANG", "2027-03-31", "2027-06-30", "MO");
                }),
        "23P01");
    inTenant(
        TENANT_A,
        c -> {
          insertPeriod(c, "QUY", "DANG", "2027-01-01", "2027-03-31", "MO");
          insertPeriod(c, "QUY", "NHA_NUOC", "2027-01-01", "2027-03-31", "MO");
          insertPeriod(c, "NAM", "DANG", "2027-01-01", "2027-12-31", "MO");
          insertPeriod(c, "QUY", "DANG", "2027-04-01", "2027-06-30", "MO");
        });
  }

  @Test
  void kyDaKhoaPhaiCoThoiDiemKhoa() {
    assertSqlState(
        () ->
            inTenant(
                TENANT_A,
                c -> {
                  try (PreparedStatement st =
                      c.prepareStatement(
                          "INSERT INTO config.period (id, code, name, kind, flow, start_date,"
                              + " end_date, period_bucket, status, created_by, updated_by)"
                              + " VALUES (?, '2027-Q1', 'Quý I/2027', 'QUY', 'DANG', DATE"
                              + " '2027-01-01', DATE '2027-03-31', 20271, 'DA_KHOA', ?, ?)")) {
                    st.setObject(1, UUID.randomUUID());
                    st.setObject(2, ACTOR);
                    st.setObject(3, ACTOR);
                    st.executeUpdate();
                  }
                }),
        "23514");
  }

  @Test
  void ganVaiTroTuDuyetBiChan() throws SQLException {
    assertSqlState(() -> inTenant(TENANT_A, c -> insertRoleBinding(c, ACTOR, ACTOR)), "23514");
    inTenant(TENANT_A, c -> insertRoleBinding(c, ACTOR, OTHER));
    inTenant(TENANT_A, c -> insertRoleBinding(c, ACTOR, null));
  }

  @Test
  void lenhKhoaKyBonMatVaMotLenhConHieuLucMoiKy() throws SQLException {
    assertSqlState(
        () ->
            inTenant(
                TENANT_A,
                c -> {
                  UUID period = insertPeriod(c, "QUY", "DANG", "2027-01-01", "2027-03-31", "MO");
                  insertLockOrder(c, period, "k1", "DA_DUYET", ACTOR, ACTOR, null, null);
                }),
        "23514");
    assertSqlState(
        () ->
            inTenant(
                TENANT_A,
                c -> {
                  UUID period = insertPeriod(c, "QUY", "DANG", "2027-01-01", "2027-03-31", "MO");
                  insertLockOrder(c, period, "k1", "DANG_CHAY", ACTOR, OTHER, OTHER, OTHER);
                }),
        "23514");
    assertSqlState(
        () ->
            inTenant(
                TENANT_A,
                c -> {
                  UUID period = insertPeriod(c, "QUY", "DANG", "2027-01-01", "2027-03-31", "MO");
                  insertLockOrder(c, period, "k1", "CHO_DUYET", ACTOR, null, null, null);
                  insertLockOrder(c, period, "k2", "NHAP", ACTOR, null, null, null);
                }),
        "23505");
    inTenant(
        TENANT_A,
        c -> {
          UUID period = insertPeriod(c, "QUY", "DANG", "2027-01-01", "2027-03-31", "MO");
          insertLockOrder(c, period, "k1", "DA_HUY", ACTOR, OTHER, ACTOR, OTHER);
          insertLockOrder(c, period, "k2", "DA_DUYET", ACTOR, OTHER, null, null);
        });
  }

  @Test
  void nhatKyTrangThaiKyChiGhiThem() throws SQLException {
    UUID[] log = new UUID[1];
    // Ghi thêm được và cam kết để các lệnh sửa, xoá sau có dòng thật để thử.
    try (Connection c = svc()) {
      c.setAutoCommit(false);
      setTenant(c, TENANT_B);
      UUID period = insertPeriod(c, "QUY", "CA_HAI", "2026-01-01", "2026-03-31", "MO");
      log[0] = UUID.randomUUID();
      try (PreparedStatement st =
          c.prepareStatement(
              "INSERT INTO config.period_status_log (id, period_id, from_status, to_status,"
                  + " cause_type, cause_ref, actor_id) VALUES (?, ?, 'MO', 'DANG_KHOA',"
                  + " 'SAGA_CHOT_KY', ?, ?)")) {
        st.setObject(1, log[0]);
        st.setObject(2, period);
        st.setObject(3, UUID.randomUUID());
        st.setObject(4, ACTOR);
        assertThat(st.executeUpdate()).isEqualTo(1);
      }
      c.commit();
    }
    assertSqlState(
        () ->
            inTenant(
                TENANT_B,
                c ->
                    exec(
                        c,
                        "UPDATE config.period_status_log SET to_status = 'MO' WHERE id = ?",
                        log[0])),
        "42501");
    assertSqlState(
        () ->
            inTenant(
                TENANT_B,
                c -> exec(c, "DELETE FROM config.period_status_log WHERE id = ?", log[0])),
        "42501");
  }

  @Test
  void tenantKhacKhongThayVaKhongGhiHoDuoc() throws SQLException {
    UUID setting = UUID.randomUUID();
    try (Connection c = svc()) {
      c.setAutoCommit(false);
      setTenant(c, TENANT_A);
      try (PreparedStatement st =
          c.prepareStatement(
              "INSERT INTO config.tenant_setting (id, setting_key, value, value_schema, created_by,"
                  + " updated_by) VALUES (?, 'task.late.dayType', '\"LAM_VIEC\"'::jsonb,"
                  + " 'setting.dayType', ?, ?)")) {
        st.setObject(1, setting);
        st.setObject(2, ACTOR);
        st.setObject(3, ACTOR);
        st.executeUpdate();
      }
      c.commit();
    }
    inTenant(
        TENANT_B,
        c -> {
          assertThat(count(c, "SELECT count(*) FROM config.tenant_setting WHERE id = ?", setting))
              .isZero();
          assertThat(
                  update(
                      c,
                      "UPDATE config.tenant_setting SET description = 'x' WHERE id = ?",
                      setting))
              .isZero();
        });
    assertSqlState(
        () ->
            inTenant(
                TENANT_B,
                c -> {
                  try (PreparedStatement st =
                      c.prepareStatement(
                          "INSERT INTO config.tenant_setting (id, tenant_id, setting_key, value,"
                              + " value_schema, created_by, updated_by) VALUES (?, ?, 'k',"
                              + " '1'::jsonb, 's', ?, ?)")) {
                    st.setObject(1, UUID.randomUUID());
                    st.setObject(2, TENANT_A);
                    st.setObject(3, ACTOR);
                    st.setObject(4, ACTOR);
                    st.executeUpdate();
                  }
                }),
        "42501");
  }

  @Test
  void danhMucComponentCoBanMauVaMigrationChayLaiKhongDoi() throws SQLException {
    try (Connection c = svc()) {
      assertThat(
              count(
                  c,
                  "SELECT count(*) FROM config.component_registry WHERE registry_version = ?",
                  "2026.3"))
          .isEqualTo(5);
    }
    int executed =
        new SchemaMigrator(MigrationOptions.defaults())
            .migrate(
                PG.getJdbcUrl(),
                "config",
                new MigrationCredentials.Credentials(
                    "mig_config", MissionCoreTestDatabase.TEST_DB_PASSWORD))
            .migrationsExecuted;
    assertThat(executed).isZero();
  }

  private static UUID insertPeriod(
      Connection c, String kind, String flow, String start, String end, String status)
      throws SQLException {
    UUID id = UUID.randomUUID();
    LocalDate startDate = LocalDate.parse(start);
    int bucket =
        startDate.getYear() * 10 + ("NAM".equals(kind) ? 0 : (startDate.getMonthValue() + 2) / 3);
    try (PreparedStatement st =
        c.prepareStatement(
            "INSERT INTO config.period (id, code, name, kind, flow, start_date, end_date,"
                + " period_bucket, status, locked_at, created_by, updated_by)"
                + " VALUES (?, ?, 'Kỳ thử', ?, ?, ?, ?, ?, ?, CASE WHEN ? IN ('DA_KHOA',"
                + " 'MO_LAI_HAU_KIEM') THEN now() END, ?, ?)")) {
      st.setObject(1, id);
      st.setString(2, "K-" + id.toString().substring(0, 8));
      st.setString(3, kind);
      st.setString(4, flow);
      st.setObject(5, startDate);
      st.setObject(6, LocalDate.parse(end));
      st.setInt(7, bucket);
      st.setString(8, status);
      st.setString(9, status);
      st.setObject(10, ACTOR);
      st.setObject(11, ACTOR);
      st.executeUpdate();
    }
    return id;
  }

  private static void insertRoleBinding(Connection c, UUID grantedBy, UUID approvedBy)
      throws SQLException {
    try (PreparedStatement st =
        c.prepareStatement(
            "INSERT INTO config.role_binding (id, person_id, role_code, org_unit_id, scope,"
                + " valid_from, granted_by, approved_by, created_by, updated_by)"
                + " VALUES (?, ?, 'decider', ?, 'DON_VI', now(), ?, ?, ?, ?)")) {
      st.setObject(1, UUID.randomUUID());
      st.setObject(2, UUID.randomUUID());
      st.setObject(3, UUID.randomUUID());
      st.setObject(4, grantedBy);
      st.setObject(5, approvedBy);
      st.setObject(6, grantedBy);
      st.setObject(7, grantedBy);
      st.executeUpdate();
    }
  }

  private static void insertLockOrder(
      Connection c,
      UUID period,
      String key,
      String status,
      UUID requestedBy,
      UUID approvedBy,
      UUID cancelRequestedBy,
      UUID cancelApprovedBy)
      throws SQLException {
    try (PreparedStatement st =
        c.prepareStatement(
            "INSERT INTO config.period_lock_order (id, period_id, requested_by, approved_by,"
                + " basis_doc, idempotency_key, status, cancel_requested_by, cancel_approved_by,"
                + " created_by, updated_by) VALUES (?, ?, ?, ?, 'Quyết định số 01/QĐ', ?, ?, ?, ?,"
                + " ?, ?)")) {
      st.setObject(1, UUID.randomUUID());
      st.setObject(2, period);
      st.setObject(3, requestedBy);
      st.setObject(4, approvedBy);
      st.setString(5, key);
      st.setString(6, status);
      st.setObject(7, cancelRequestedBy);
      st.setObject(8, cancelApprovedBy);
      st.setObject(9, requestedBy);
      st.setObject(10, requestedBy);
      st.executeUpdate();
    }
  }

  /** Chạy {@code work} trong một giao dịch của tenant rồi luôn huỷ, để các ca độc lập nhau. */
  private static void inTenant(UUID tenant, Work work) throws SQLException {
    try (Connection c = svc()) {
      c.setAutoCommit(false);
      try {
        setTenant(c, tenant);
        work.run(c);
      } finally {
        c.rollback();
      }
    }
  }

  private static Connection svc() throws SQLException {
    return DriverManager.getConnection(
        PG.getJdbcUrl(), "svc_config", MissionCoreTestDatabase.TEST_DB_PASSWORD);
  }

  private static void setTenant(Connection c, UUID tenant) throws SQLException {
    try (PreparedStatement st = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      st.setString(1, tenant.toString());
      st.execute();
    }
  }

  private static void exec(Connection c, String sql, Object param) throws SQLException {
    update(c, sql, param);
  }

  private static int update(Connection c, String sql, Object param) throws SQLException {
    try (PreparedStatement st = c.prepareStatement(sql)) {
      st.setObject(1, param);
      return st.executeUpdate();
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

  private static void assertSqlState(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String sqlState) {
    assertThatThrownBy(call)
        .isInstanceOf(SQLException.class)
        .extracting(e -> ((SQLException) e).getSQLState())
        .isEqualTo(sqlState);
  }
}
