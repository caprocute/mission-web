package vn.viettel.okrkpi.config.schema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.postgresql.util.PGobject;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Kiểm thử rò rỉ tenant (RLS) cho các bảng của schema {@code config} (CORE-08-01).
 *
 * <p>{@code ui_pack} và {@code ui_pack_version} tham chiếu vòng nhau nên bộ sinh dòng tự động không
 * xếp được thứ tự; lớp này chèn trước một {@code ui_pack} cho mỗi tenant và cho {@code
 * ui_pack_version} một fixture trỏ về gói đó.
 */
class ConfigTenantIsolationIT extends TenantIsolationCatalogTest {

  private static final UUID ACTOR = UUID.fromString("01927a08-0000-7000-8000-0000000000ac");

  @Override
  protected List<String> schemas() {
    return List.of("config");
  }

  @Override
  protected Map<String, String> tenantlessTableExceptions() {
    return Map.of(
        "config.component_registry",
        "Danh mục component SDUI dùng chung nền tảng, phát hành cùng bản web-frontend (TL-03)");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    return Map.of(
        "config.ui_pack_version",
        (tenantId, seed) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", UUID.nameUUIDFromBytes(("ver-" + tenantId).getBytes()));
          row.put("ui_pack_id", seededPack(tenantId));
          row.put("version", "1.0." + seed);
          row.put("tokens", json("{}"));
          row.put("layouts", json("{}"));
          row.put("component_registry_version", "2026.3");
          row.put("created_by", ACTOR);
          row.put("updated_by", ACTOR);
          return row;
        });
  }

  @BeforeAll
  void seedUiPacks() throws SQLException {
    try (Connection admin =
            DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement st =
            admin.prepareStatement(
                "INSERT INTO config.ui_pack (id, tenant_id, code, name, created_by, updated_by)"
                    + " VALUES (?, ?, ?, 'Gói mẫu', ?, ?)")) {
      for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
        st.setObject(1, seededPack(tenant));
        st.setObject(2, tenant);
        st.setString(3, "goi-mau-" + tenant.toString().substring(34));
        st.setObject(4, ACTOR);
        st.setObject(5, ACTOR);
        st.executeUpdate();
      }
    }
  }

  private static PGobject json(String value) {
    PGobject json = new PGobject();
    try {
      json.setType("jsonb");
      json.setValue(value);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return json;
  }

  private static UUID seededPack(UUID tenantId) {
    return UUID.nameUUIDFromBytes(("pack-" + tenantId).getBytes());
  }
}
