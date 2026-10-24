package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.PostgresSupport;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * Dữ liệu thử dev ({@code infra/dev/postgres/seed/org-du-lieu-thu.sql}) khớp realm mission
 * ({@code infra/dev/keycloak/realm-mission.json}): mỗi tài khoản thử có {@code unit_id} có hồ sơ cán bộ trùng
 * {@code sub}, phân công chính tại đúng đơn vị đó và {@code GET /api/v1/org/me} trả phân công; nạp hai lần không trùng.
 */
@AutoConfigureMockMvc
class DevSeedApiIT extends IntegrationTestBase {

  private static final Path REPO = Path.of("../../..");
  private static final Path SEED = REPO.resolve("infra/dev/postgres/seed/org-du-lieu-thu.sql");
  private static final Path REALM = REPO.resolve("infra/dev/keycloak/realm-mission.json");

  @Autowired
  MockMvc mvc;

  private static JdbcTemplate admin;

  record TestUser(String username, UUID id, UUID tenant, UUID unit) {
  }

  @BeforeAll
  static void seedTwice() throws Exception {
    PostgresSupport.start();
    DataSource ds = new DriverManagerDataSource(PostgresSupport.jdbcUrl(), PostgresSupport.adminUsername(),
        PostgresSupport.adminPassword());
    admin = new JdbcTemplate(ds);
    String sql = Files.readString(SEED, StandardCharsets.UTF_8);
    admin.execute(sql);
    admin.execute(sql);
  }

  @Test
  void moiTaiKhoanThuCoHoSoVaPhanCongTaiDonViCuaRealm() throws Exception {
    List<TestUser> users = realmUsers();
    assertThat(users).hasSize(12);
    for (TestUser u : users) {
      Map<String, Object> row = admin.queryForMap(
          "SELECT count(*) AS n, min(a.org_unit_id::text) AS unit FROM org.person p JOIN org.assignment a "
              + "ON a.person_id = p.id AND a.tenant_id = p.tenant_id WHERE p.id = ? AND p.tenant_id = ? "
              + "AND a.kind = 'CHINH'",
          u.id(), u.tenant());
      assertThat(((Number) row.get("n")).intValue()).as(u.username()).isEqualTo(1);
      assertThat(row.get("unit")).as(u.username()).isEqualTo(u.unit().toString());
    }
    // Chỉ quanly có thêm một phân công kiêm nhiệm (HT-02 chọn phân công khi đăng nhập)
    Integer kiemNhiem = admin.queryForObject(
        "SELECT count(*) FROM org.assignment a JOIN org.person p ON p.id = a.person_id AND p.tenant_id = a.tenant_id "
            + "WHERE a.kind = 'KIEM_NHIEM' AND a.tenant_id = ?",
        Integer.class, users.get(0).tenant());
    assertThat(kiemNhiem).isEqualTo(1);
    Integer units = admin.queryForObject("SELECT count(*) FROM org.org_unit WHERE tenant_id = ?", Integer.class,
        users.get(0).tenant());
    assertThat(units).isEqualTo(4);
  }

  @Test
  void hoSoCuaToiTraPhanCongChoTaiKhoanThu() throws Exception {
    TestUser canbo = realmUsers().stream().filter(u -> u.username().equals("canbo")).findFirst().orElseThrow();
    String token = "Bearer " + TestJwt.token(canbo.tenant(), canbo.id(), List.of("member"), "aal1", canbo.unit());
    mvc.perform(get("/api/v1/org/me").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.personId").value(canbo.id().toString()))
        .andExpect(jsonPath("$.fullName").value("Nguyễn Văn Cán Bộ"))
        .andExpect(jsonPath("$.assignments.length()").value(1))
        .andExpect(jsonPath("$.assignments[0].orgUnitId").value(canbo.unit().toString()));
  }

  private static List<TestUser> realmUsers() throws Exception {
    JsonNode realm = new ObjectMapper().readTree(REALM.toFile());
    List<TestUser> out = new ArrayList<>();
    for (JsonNode u : realm.path("users")) {
      JsonNode attrs = u.path("attributes");
      if (!attrs.has("unit_id")) {
        continue;
      }
      out.add(new TestUser(u.path("username").asText(), UUID.fromString(u.path("id").asText()),
          UUID.fromString(attrs.path("tenant").get(0).asText()), UUID.fromString(attrs.path("unit_id").get(0).asText())));
    }
    return out;
  }
}
