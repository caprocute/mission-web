package vn.viettel.okrkpi.integ.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Schema {@code integ} của SVC-13 (DV-13-01): rò rỉ tenant (RLS) do lớp cơ sở sinh trên mọi bảng,
 * cộng ràng buộc của TL-03: ánh xạ chồng thời gian hiệu lực bị EXCLUDE chặn, trùng {@code
 * external_msg_id} trong ngày bị UQ chặn, hai hàm quét liên tenant chỉ trả mã.
 */
class IntegTenantIsolationIT extends TenantIsolationCatalogTest {

  @Override
  protected List<String> schemas() {
    return List.of("integ");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "integ.connector",
        (tenant, seed) -> {
          Map<String, Object> r = audited(tenant, "connector");
          r.put("connector_code", "VOFFICE");
          r.put("protocol", "REST");
          r.put("endpoint", "https://voffice.invalid/api");
          r.put("secret_path", "kv/integ/voffice");
          r.put("direction", "VAO");
          r.put("enabled", true);
          return r;
        });
    f.put(
        "integ.inbox_message",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "inbox"));
          r.put("received_day", LocalDate.of(2026, 10, 22));
          r.put("connector_id", id(tenant, "connector"));
          r.put("external_msg_id", "msg-" + seed);
          r.put("message_type", "VAN_BAN_CHI_DAO");
          r.put("raw_enc", new byte[] {1, 2, 3});
          r.put("dek_id", id(tenant, "dek"));
          return r;
        });
    f.put(
        "integ.mapping_xref",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "xref"));
          r.put("connector_id", id(tenant, "connector"));
          r.put("entity_type", "DON_VI");
          r.put("external_id", "ORG-" + seed);
          r.put("internal_id", id(tenant, "org"));
          r.put("valid_from", LocalDate.of(2026, 1, 1));
          return r;
        });
    f.put(
        "integ.sync_cursor",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("connector_id", id(tenant, "connector"));
          r.put("stream", "directive");
          r.put("cursor_value", "0");
          return r;
        });
    f.put(
        "integ.dead_letter",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "dead"));
          r.put("connector_id", id(tenant, "connector"));
          r.put("direction", "VAO");
          r.put("ref_message_id", id(tenant, "inbox"));
          r.put("error_code", "LOI");
          r.put("error_detail", "Lỗi");
          return r;
        });
    f.put(
        "integ.outbound_message",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "out"));
          r.put("connector_id", id(tenant, "connector"));
          r.put("source_event_id", id(tenant, "event"));
          r.put("message_type", "KET_QUA_XEP_LOAI");
          r.put("payload_enc", new byte[] {1});
          r.put("dek_id", id(tenant, "dek"));
          return r;
        });
    f.put(
        "integ.transfer_package",
        (tenant, seed) -> {
          Map<String, Object> r = audited(tenant, "transfer_package");
          r.put("person_id", id(tenant, "person"));
          r.put("direction", "DI");
          r.put("peer_cell_code", "C02");
          r.put("peer_tenant_code", "T02");
          r.put("peer_tenant_id", id(tenant, "peer-tenant"));
          r.put("manifest", jsonb("{}"));
          r.put("object_key", "tp/" + tenant);
          r.put("package_sha256", new byte[32]);
          r.put("wrapped_key", new byte[] {1});
          r.put("signature", new byte[] {1});
          r.put("status", "DONG_GOI");
          return r;
        });
    return f;
  }

  @Test
  void anhXaChongHieuLucBiChanConNoiTiepThiDuoc() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID connector = connector(st, t);
      st.execute(xref(t, connector, "'2026-01-01'", "'2026-07-01'"));
      assertThatThrownBy(() -> st.execute(xref(t, connector, "'2026-06-01'", "NULL")))
          .hasMessageContaining("ex_mapping_xref__hieu_luc");
      st.execute(xref(t, connector, "'2026-07-01'", "NULL"));
      // Cột sinh valid_period báo lỗi khoảng ngược trước khi tới ck_mapping_xref__valid.
      assertThatThrownBy(() -> st.execute(xref(t, connector, "'2026-07-01'", "'2026-06-01'")))
          .hasMessageContaining("range lower bound");
    }
  }

  @Test
  void trungMaThongDiepTrongNgayBiChan() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID connector = connector(st, t);
      st.execute(inbox(t, connector, "m-1"));
      assertThatThrownBy(() -> st.execute(inbox(t, connector, "m-1")))
          .hasMessageContaining("duplicate key");
    }
  }

  @Test
  void hamQuetChiTraMaVaVaiTroUngDungKhongXoaAnhXa() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID connector = connector(st, t);
      st.execute(xref(t, connector, "'2026-01-01'", "NULL"));
    }
    try (Connection svc = DriverManager.getConnection(jdbcUrl(), "svc_integ", TEST_PASSWORD);
        Statement st = svc.createStatement()) {
      int count = 0;
      try (ResultSet rs = st.executeQuery("SELECT * FROM integ.fn_ket_noi_dang_bat()")) {
        while (rs.next()) {
          count += t.equals(rs.getObject("tenant_id", UUID.class)) ? 1 : 0;
        }
      }
      assertThat(count).isEqualTo(1);
      svc.setAutoCommit(false);
      st.execute("SELECT set_config('app.tenant_id', '" + t + "', true)");
      assertThatThrownBy(() -> st.executeUpdate("DELETE FROM integ.mapping_xref"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
  }

  private Connection admin() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static UUID connector(Statement st, UUID t) throws SQLException {
    UUID id = UUID.randomUUID();
    st.execute(
        "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code,"
            + " protocol, endpoint, secret_path, direction, enabled) VALUES ('"
            + id
            + "', '"
            + t
            + "', gen_random_uuid(), gen_random_uuid(), 'VOFFICE', 'REST', 'https://x.invalid',"
            + " 'kv/x', 'VAO', true)");
    return id;
  }

  private static String xref(UUID t, UUID connector, String from, String to) {
    return "INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type, external_id,"
        + " internal_id, valid_from, valid_to) VALUES (gen_random_uuid(), '"
        + t
        + "', '"
        + connector
        + "', 'DON_VI', '42', gen_random_uuid(), "
        + from
        + ", "
        + to
        + ")";
  }

  private static String inbox(UUID t, UUID connector, String ext) {
    return "INSERT INTO integ.inbox_message (id, tenant_id, connector_id, external_msg_id,"
        + " message_type, raw_enc, dek_id) VALUES (gen_random_uuid(), '"
        + t
        + "', '"
        + connector
        + "', '"
        + ext
        + "', 'X', '\\x01', gen_random_uuid())";
  }

  private static Map<String, Object> audited(UUID tenant, String table) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", id(tenant, table));
    r.put("created_by", id(tenant, "user"));
    r.put("updated_by", id(tenant, "user"));
    return r;
  }

  private static PGobject jsonb(String value) {
    try {
      PGobject o = new PGobject();
      o.setType("jsonb");
      o.setValue(value);
      return o;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static UUID id(UUID tenant, String name) {
    return UUID.nameUUIDFromBytes((tenant + "/" + name).getBytes(StandardCharsets.UTF_8));
  }
}
