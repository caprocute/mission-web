package vn.viettel.okrkpi.policy.schema;

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
 * Kiểm thử rò rỉ tenant (RLS) cho các bảng của schema {@code policy} (CORE-05-01).
 *
 * <p>{@code policy_pack_version} và {@code test_run} tham chiếu vòng nhau nên bộ sinh dòng tự động
 * không xếp được thứ tự; lớp này chèn trước một gói và một phiên bản cho mỗi tenant và cho {@code
 * test_run} một fixture trỏ về phiên bản đó. Ràng buộc {@code ck_policy_pack_version__signed} so
 * sánh {@code created_by} khiến bộ sinh tự động gán nhầm chuỗi, nên phiên bản cũng dùng fixture. Ba
 * bảng hồ sơ tập khách hàng (CORE-05-12) có CHECK dạng mã và dạng phiên bản nên cũng dùng fixture,
 * trỏ về một hồ sơ và một phiên bản chèn trước cho mỗi tenant.
 */
class PolicyTenantIsolationIT extends TenantIsolationCatalogTest {

  private static final UUID ACTOR = UUID.fromString("01927a05-0000-7000-8000-0000000000ac");

  @Override
  protected List<String> schemas() {
    return List.of("policy");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    return Map.of(
        "policy.policy_pack_version",
        (tenantId, seed) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", seeded("ver-" + seed, tenantId));
          row.put("policy_pack_id", seeded("pack", tenantId));
          row.put("version", "1.0." + seed);
          row.put("manifest", json("{}"));
          row.put("change_note", "Bản mẫu");
          row.put("created_by", ACTOR);
          row.put("updated_by", ACTOR);
          return row;
        },
        "policy.test_run",
        (tenantId, seed) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", seeded("run-" + seed, tenantId));
          row.put("policy_pack_version_id", seeded("ver", tenantId));
          row.put("engine", "CEL_JAVA");
          row.put("total", 1);
          row.put("passed", 1);
          row.put("failed", 0);
          row.put("created_by", ACTOR);
          row.put("updated_by", ACTOR);
          return row;
        },
        "policy.publish_request",
        (tenantId, seed) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", seeded("req-" + seed, tenantId));
          row.put("policy_pack_version_id", seeded("ver", tenantId));
          row.put("period_id", seeded("period", tenantId));
          row.put("scopes", json("[{\"flow\":\"DANG\"}]"));
          row.put("status", "TU_CHOI");
          row.put("decided_by", ACTOR);
          row.put("decided_at", java.sql.Timestamp.from(java.time.Instant.now()));
          row.put("created_by", ACTOR);
          row.put("updated_by", ACTOR);
          return row;
        },
        "policy.customer_profile",
        (tenantId, seed) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", seeded("profile-" + seed, tenantId));
          row.put("code", "mau/ho-so-" + seed);
          row.put("name", "Hồ sơ mẫu");
          row.put("created_by", ACTOR);
          row.put("updated_by", ACTOR);
          return row;
        },
        "policy.customer_profile_version",
        (tenantId, seed) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", seeded("profile-ver-" + seed, tenantId));
          row.put("customer_profile_id", seeded("profile", tenantId));
          row.put("version", "1.0." + seed);
          row.put("manifest", json("{}"));
          row.put("change_note", "Bản mẫu");
          row.put("created_by", ACTOR);
          row.put("updated_by", ACTOR);
          return row;
        },
        "policy.param_constraint",
        (tenantId, seed) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", seeded("constraint-" + seed, tenantId));
          row.put("customer_profile_version_id", seeded("profile-ver", tenantId));
          row.put("param_key", "tham_so.mau_" + seed);
          row.put("created_by", ACTOR);
          row.put("updated_by", ACTOR);
          return row;
        });
  }

  @BeforeAll
  void seedVersions() throws SQLException {
    try (Connection admin =
            DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement pack =
            admin.prepareStatement(
                "INSERT INTO policy.policy_pack (id, tenant_id, code, name, sector, flow,"
                    + " legal_basis, created_by, updated_by) VALUES (?, ?, ?, 'Gói mẫu', 'DANG',"
                    + " 'DANG', 'Quy định mẫu', ?, ?)");
        PreparedStatement version =
            admin.prepareStatement(
                "INSERT INTO policy.policy_pack_version (id, tenant_id, policy_pack_id, version,"
                    + " manifest, change_note, created_by, updated_by) VALUES (?, ?, ?, '0.0.1',"
                    + " '{}'::jsonb, 'Bản mẫu', ?, ?)");
        PreparedStatement profile =
            admin.prepareStatement(
                "INSERT INTO policy.customer_profile (id, tenant_id, code, name, created_by,"
                    + " updated_by) VALUES (?, ?, 'mau/ho-so-goc', 'Hồ sơ mẫu', ?, ?)");
        PreparedStatement profileVersion =
            admin.prepareStatement(
                "INSERT INTO policy.customer_profile_version (id, tenant_id, customer_profile_id,"
                    + " version, manifest, change_note, created_by, updated_by) VALUES (?, ?, ?,"
                    + " '0.0.1', '{}'::jsonb, 'Bản mẫu', ?, ?)")) {
      for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
        pack.setObject(1, seeded("pack", tenant));
        pack.setObject(2, tenant);
        pack.setString(3, "goi-mau-" + tenant.toString().substring(34));
        pack.setObject(4, ACTOR);
        pack.setObject(5, ACTOR);
        pack.executeUpdate();
        version.setObject(1, seeded("ver", tenant));
        version.setObject(2, tenant);
        version.setObject(3, seeded("pack", tenant));
        version.setObject(4, ACTOR);
        version.setObject(5, ACTOR);
        version.executeUpdate();
        profile.setObject(1, seeded("profile", tenant));
        profile.setObject(2, tenant);
        profile.setObject(3, ACTOR);
        profile.setObject(4, ACTOR);
        profile.executeUpdate();
        profileVersion.setObject(1, seeded("profile-ver", tenant));
        profileVersion.setObject(2, tenant);
        profileVersion.setObject(3, seeded("profile", tenant));
        profileVersion.setObject(4, ACTOR);
        profileVersion.setObject(5, ACTOR);
        profileVersion.executeUpdate();
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

  private static UUID seeded(String kind, UUID tenantId) {
    return UUID.nameUUIDFromBytes((kind + "-" + tenantId).getBytes());
  }
}
