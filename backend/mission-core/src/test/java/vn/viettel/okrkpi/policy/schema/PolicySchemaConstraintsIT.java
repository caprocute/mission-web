package vn.viettel.okrkpi.policy.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;

/**
 * Bất biến của Policy Pack ở tầng CSDL (CORE-05-01, TL-03 "Schema policy"): quy tắc của phiên bản
 * đã phát hành không sửa, xoá, thêm được; phát hành thiếu chữ ký hoặc tự duyệt bị chặn; nội dung
 * phiên bản đã đạt kiểm thử đóng băng; gắn phiên bản của kỳ đã khoá không đổi được. Mọi thao tác
 * chạy bằng vai trò {@code svc_policy} như ứng dụng thật, nên chứng minh cả khi gọi SQL trực tiếp.
 */
class PolicySchemaConstraintsIT {

  private static final UUID TENANT = UUID.fromString("01927a05-0000-7000-8000-00000000a001");
  private static final UUID AUTHOR = UUID.fromString("01927a05-0000-7000-8000-0000000000ac");
  private static final UUID APPROVER = UUID.fromString("01927a05-0000-7000-8000-0000000000ad");
  private static final String FROZEN = "23000";
  private static final String CHECK = "23514";

  private static final PostgreSQLContainer<?> PG = MissionCoreTestDatabase.container();

  @FunctionalInterface
  private interface Work {
    void run(Connection c) throws SQLException;
  }

  @Test
  void quyTacCuaPhienBanDaPhatHanhKhongSuaXoaThemDuoc() throws SQLException {
    Work publish =
        c -> {
          UUID version = insertVersion(c, insertPack(c));
          insertRule(c, version, "diem");
          exec(
              c,
              "UPDATE policy.policy_pack_version SET status = 'DA_PHAT_HANH', golden_pass_rate = 1,"
                  + " content_hash = '\\x01'::bytea, signature = '\\x02'::bytea, approved_by = ?"
                  + " WHERE id = ?",
              APPROVER,
              version);
        };
    inTenant(publish);
    assertSqlState(
        () ->
            inTenant(
                c -> {
                  publish.run(c);
                  exec(
                      c,
                      "UPDATE policy.policy_rule SET expression = '0.0' WHERE rule_key = ?",
                      "diem");
                }),
        FROZEN);
    assertSqlState(
        () ->
            inTenant(
                c -> {
                  publish.run(c);
                  exec(c, "DELETE FROM policy.policy_rule WHERE rule_key = ?", "diem");
                }),
        FROZEN);
    assertSqlState(
        () ->
            inTenant(
                c -> {
                  UUID version = insertVersion(c, insertPack(c));
                  exec(
                      c,
                      "UPDATE policy.policy_pack_version SET status = 'DAT_KIEM_THU', golden_pass_rate"
                          + " = 1 WHERE id = ?",
                      version);
                  insertRule(c, version, "them_sau");
                }),
        FROZEN);
  }

  @Test
  void phatHanhThieuChuKyHoacTuDuyetBiChan() {
    assertSqlState(() -> inTenant(c -> publish(c, null, APPROVER)), CHECK);
    assertSqlState(() -> inTenant(c -> publish(c, "\\x02", AUTHOR)), CHECK);
    assertSqlState(() -> inTenant(c -> publish(c, "\\x02", null)), CHECK);
  }

  @Test
  void datKiemThuPhaiDatTronVenVaNoiDungDongBang() throws SQLException {
    assertSqlState(
        () ->
            inTenant(
                c ->
                    exec(
                        c,
                        "UPDATE policy.policy_pack_version SET status = 'DAT_KIEM_THU',"
                            + " golden_pass_rate = 0.9 WHERE id = ?",
                        insertVersion(c, insertPack(c)))),
        CHECK);
    assertSqlState(
        () ->
            inTenant(
                c -> {
                  UUID version = passTests(c);
                  exec(
                      c,
                      "UPDATE policy.policy_pack_version SET manifest = '{\"lamTron\":3}'::jsonb"
                          + " WHERE id = ?",
                      version);
                }),
        FROZEN);
    // Cách khớp TL-02b (sửa phiên bản đạt kiểm thử) với trigger TL-03: về NHAP trước rồi mới sửa.
    inTenant(
        c -> {
          UUID version = passTests(c);
          exec(c, "UPDATE policy.policy_pack_version SET status = 'NHAP' WHERE id = ?", version);
          exec(
              c,
              "UPDATE policy.policy_pack_version SET manifest = '{\"lamTron\":3}'::jsonb WHERE id = ?",
              version);
          insertRule(c, version, "sua_lai");
        });
  }

  @Test
  void ganPhienBanCuaKyDaKhoaKhongDoiDuoc() throws SQLException {
    Work frozenBinding =
        c -> {
          UUID pack = insertPack(c);
          UUID v1 = insertVersion(c, pack);
          UUID binding = UUID.randomUUID();
          exec(
              c,
              "INSERT INTO policy.policy_binding (id, policy_pack_version_id, period_id, flow,"
                  + " is_frozen, created_by, updated_by) VALUES (?, ?, ?, 'DANG', true, ?, ?)",
              binding,
              v1,
              UUID.randomUUID(),
              AUTHOR,
              AUTHOR);
        };
    assertSqlState(
        () ->
            inTenant(
                c -> {
                  frozenBinding.run(c);
                  UUID v2 = insertVersion(c, insertPack(c));
                  exec(c, "UPDATE policy.policy_binding SET policy_pack_version_id = ?", v2);
                }),
        FROZEN);
    assertSqlState(
        () ->
            inTenant(
                c -> {
                  frozenBinding.run(c);
                  exec(c, "DELETE FROM policy.policy_binding WHERE is_frozen = ?", true);
                }),
        FROZEN);
    inTenant(
        c -> {
          frozenBinding.run(c);
          assertThat(exec(c, "UPDATE policy.policy_binding SET is_frozen = ?", false)).isEqualTo(1);
          UUID v2 = insertVersion(c, insertPack(c));
          assertThat(exec(c, "UPDATE policy.policy_binding SET policy_pack_version_id = ?", v2))
              .isEqualTo(1);
        });
  }

  private static void publish(Connection c, String signature, UUID approvedBy) throws SQLException {
    UUID version = insertVersion(c, insertPack(c));
    exec(
        c,
        "UPDATE policy.policy_pack_version SET status = 'DA_PHAT_HANH', golden_pass_rate = 1,"
            + " content_hash = '\\x01'::bytea, signature = ?::bytea, approved_by = ? WHERE id = ?",
        signature,
        approvedBy,
        version);
  }

  private static UUID passTests(Connection c) throws SQLException {
    UUID version = insertVersion(c, insertPack(c));
    exec(
        c,
        "UPDATE policy.policy_pack_version SET status = 'DAT_KIEM_THU', golden_pass_rate = 1"
            + " WHERE id = ?",
        version);
    return version;
  }

  private static UUID insertPack(Connection c) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO policy.policy_pack (id, code, name, sector, flow, legal_basis, created_by,"
            + " updated_by) VALUES (?, ?, 'Khối Đảng', 'DANG', 'DANG', 'QĐ 366-QĐ/TW', ?, ?)",
        id,
        "khoi-dang/" + id,
        AUTHOR,
        AUTHOR);
    return id;
  }

  private static UUID insertVersion(Connection c, UUID pack) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO policy.policy_pack_version (id, policy_pack_id, version, manifest,"
            + " change_note, created_by, updated_by) VALUES (?, ?, '1.0.0', '{}'::jsonb,"
            + " 'Bản đầu', ?, ?)",
        id,
        pack,
        AUTHOR,
        AUTHOR);
    return id;
  }

  private static void insertRule(Connection c, UUID version, String key) throws SQLException {
    exec(
        c,
        "INSERT INTO policy.policy_rule (id, policy_pack_version_id, rule_key, expression,"
            + " result_type, rounding, legal_ref, eval_order, created_by, updated_by) VALUES (?, ?,"
            + " ?, '1.0', 'DECIMAL', '{\"digits\":2}'::jsonb, 'PL 3 mục 2', 1, ?, ?)",
        UUID.randomUUID(),
        version,
        key,
        AUTHOR,
        AUTHOR);
  }

  /** Chạy {@code work} trong một giao dịch của tenant rồi luôn huỷ, để các ca độc lập nhau. */
  private static void inTenant(Work work) throws SQLException {
    try (Connection c =
        DriverManager.getConnection(
            PG.getJdbcUrl(), "svc_policy", MissionCoreTestDatabase.TEST_DB_PASSWORD)) {
      c.setAutoCommit(false);
      try {
        exec(c, "SELECT set_config('app.tenant_id', ?, true)", TENANT.toString());
        work.run(c);
      } finally {
        c.rollback();
      }
    }
  }

  private static int exec(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement st = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        st.setObject(i + 1, params[i]);
      }
      return st.execute() ? 0 : st.getUpdateCount();
    }
  }

  private static void assertSqlState(ThrowingCallable call, String sqlState) {
    assertThatThrownBy(call)
        .isInstanceOf(SQLException.class)
        .extracting(e -> ((SQLException) e).getSQLState())
        .isEqualTo(sqlState);
  }
}
