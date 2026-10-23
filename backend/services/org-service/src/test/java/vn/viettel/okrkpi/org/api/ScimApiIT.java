package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.org.application.port.IntegrationGatewayPort;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;
import vn.viettel.okrkpi.org.support.AesGcmTestFieldEncryptor;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * DV-04-06 · Bộ ca tuân thủ SCIM 2.0 cơ bản (RFC 7644): tạo, lọc, PATCH, vô hiệu hoá người dùng; nhóm ánh xạ đơn vị;
 * khử trùng lệnh lặp; tách tenant; số định danh không có dạng rõ trong CSDL và nhật ký.
 */
@AutoConfigureMockMvc
@Import(ScimApiIT.Crypto.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "okrkpi.org.scim.review-unmatched-sources=IDP_DOI_SOAT")
class ScimApiIT extends IntegrationTestBase {

  static final String SCIM = "application/scim+json";
  static final String EXT = "urn:okrkpi:params:scim:schemas:extension:mission:2.0:User";
  static final String NATIONAL = "001090024680";
  // Ngày nghiệp vụ theo giờ Việt Nam như Clock của dịch vụ (okrkpi.org.zone); current_date của CSDL theo UTC
  // lệch một ngày từ 17h UTC, khi đó bản ghi hiệu lực từ "hôm nay" chưa bao trùm current_date.
  static final String HOM_NAY = "(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date";

  @TestConfiguration
  static class Crypto {
    @Bean
    @Primary
    FieldEncryptor testFieldEncryptor() {
      return new AesGcmTestFieldEncryptor();
    }
  }

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;
  @MockitoBean
  IntegrationGatewayPort gateway;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private String titleCode;
  private UUID unitA;
  private UUID unitB;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K3");
    unitA = UUID.randomUUID();
    unitB = UUID.randomUUID();
    fx.unit(tenant, unitA, "SO-TC", "Sở Tài chính", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    fx.unit(tenant, unitB, "SO-NV", "Sở Nội vụ", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    UUID title = fx.jobTitle(tenant);
    titleCode = fx.admin().queryForObject("SELECT code FROM org.job_title WHERE id = ?", String.class, title);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  private ResultActions scim(MockHttpServletRequestBuilder req) throws Exception {
    return scim(req, tenant, "HRM_SO_TC", List.of("service_client"));
  }

  private ResultActions scim(MockHttpServletRequestBuilder req, UUID t, String client, List<String> roles)
      throws Exception {
    String token = TestJwt.token(t, UUID.randomUUID(), roles, "aal1", null, Map.of("azp", client));
    return mvc.perform(req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(SCIM));
  }

  private String userBody(String externalId, String userName, String name, String nationalId, String birth) {
    return """
        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User","%s"],
         "externalId":"%s","userName":"%s","displayName":"%s","title":"%s","active":true,
         "%s":{"nationalId":"%s","birthDate":"%s","partyMember":true}}
        """.formatted(EXT, externalId, userName, name, titleCode, EXT, nationalId, birth);
  }

  private JsonNode body(ResultActions r) throws Exception {
    return json.readTree(r.andReturn().getResponse().getContentAsString());
  }

  private int logCount(String status) {
    return fx.admin().queryForObject("SELECT count(*) FROM org.scim_sync_log WHERE tenant_id = ? AND status = ?",
        Integer.class, tenant, status);
  }

  @Test
  void cauHinhDichVuVaLuocDo() throws Exception {
    scim(get("/scim/v2/ServiceProviderConfig")).andExpect(status().isOk())
        .andExpect(jsonPath("$.patch.supported").value(true)).andExpect(jsonPath("$.bulk.supported").value(false));
    scim(get("/scim/v2/ResourceTypes")).andExpect(status().isOk()).andExpect(jsonPath("$.totalResults").value(2));
    scim(get("/scim/v2/Schemas")).andExpect(status().isOk()).andExpect(jsonPath("$.Resources[1].id").value(EXT));
  }

  @Test
  void taoLocPatchVaVoHieuHoaNguoiDung(CapturedOutput output) throws Exception {
    ResultActions created = scim(post("/scim/v2/Users")
        .content(userBody("NV-001", "an.nv", "Nguyễn Văn An", NATIONAL, "1985-04-12")))
        .andExpect(status().isCreated()).andExpect(header().exists(HttpHeaders.LOCATION))
        .andExpect(header().string(HttpHeaders.ETAG, "W/\"0\""))
        .andExpect(jsonPath("$.userName").value("an.nv")).andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$['" + EXT + "'].nationalId").doesNotExist())
        .andExpect(jsonPath("$['" + EXT + "'].birthDate").doesNotExist())
        .andExpect(jsonPath("$['" + EXT + "'].employmentStatus").value("DANG_CONG_TAC"));
    String id = body(created).get("id").asText();
    UUID personId = fx.admin().queryForObject("SELECT internal_id FROM org.scim_resource WHERE id = ?::uuid",
        UUID.class, id);
    assertThat(fx.admin().queryForObject("SELECT count(*) FROM org.person WHERE id = ? AND national_id_bidx IS NOT NULL "
        + "AND national_id_enc IS NOT NULL AND birth_date_enc IS NOT NULL", Integer.class, personId)).isOne();
    assertThat(fx.outboxCount(tenant, "org.person.changed.v1")).isEqualTo(1);

    // Lọc theo userName và externalId.
    scim(get("/scim/v2/Users").param("filter", "userName eq \"an.nv\"")).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalResults").value(1)).andExpect(jsonPath("$.Resources[0].id").value(id));
    scim(get("/scim/v2/Users").param("filter", "externalId eq \"KHONG-CO\"")).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalResults").value(0));
    scim(get("/scim/v2/Users").param("filter", "emails co \"x\"")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.scimType").value("invalidFilter"));

    // PATCH tắt active rồi đổi tên hiển thị; If-Match sai bị 412.
    String deactivate = """
        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
         "Operations":[{"op":"replace","path":"active","value":false}]}""";
    scim(patch("/scim/v2/Users/" + id).header(HttpHeaders.IF_MATCH, "W/\"7\"").content(deactivate))
        .andExpect(status().isPreconditionFailed());
    scim(patch("/scim/v2/Users/" + id).header(HttpHeaders.IF_MATCH, "W/\"0\"").content(deactivate))
        .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$['" + EXT + "'].employmentStatus").value("TAM_NGHI"));
    // Lặp đúng lệnh vừa thành công: bỏ qua, không phát thêm sự kiện.
    scim(patch("/scim/v2/Users/" + id).content(deactivate)).andExpect(status().isOk());
    assertThat(logCount("BO_QUA")).isEqualTo(1);
    scim(patch("/scim/v2/Users/" + id).content("""
        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
         "Operations":[{"op":"replace","value":{"active":true,"displayName":"Nguyễn Văn Ân"}}]}"""))
        .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Nguyễn Văn Ân"))
        .andExpect(jsonPath("$.active").value(true));
    assertThat(fx.outboxCount(tenant, "org.person.changed.v1")).isEqualTo(3);

    // DELETE là vô hiệu hoá: người vẫn còn, trạng thái tạm nghỉ, tài nguyên SCIM không còn đọc được.
    scim(delete("/scim/v2/Users/" + id)).andExpect(status().isNoContent());
    scim(get("/scim/v2/Users/" + id)).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"));
    assertThat(fx.admin().queryForObject("SELECT employment_status FROM org.person WHERE id = ? "
        + "AND recorded_to = 'infinity' AND valid_period @> " + HOM_NAY, String.class, personId))
        .isEqualTo("TAM_NGHI");

    // Mọi lệnh ghi đều có dòng nhật ký: 1 tạo, 1 PATCH 412, 2 PATCH thành công, 1 bỏ qua, 1 xoá.
    assertThat(fx.admin().queryForObject("SELECT count(*) FROM org.scim_sync_log WHERE tenant_id = ?",
        Integer.class, tenant)).isEqualTo(6);
    assertThat(logCount("LOI")).isEqualTo(1);
    assertNoPlaintext(NATIONAL, output);
  }

  @Test
  void khopNguoiDaCoBangChiMucMuVaDoiSoatKhiKhongChac() throws Exception {
    String first = body(scim(post("/scim/v2/Users")
        .content(userBody("NV-002", "binh.tt", "Trần Thị Bình", NATIONAL, "1990-01-01")))
        .andExpect(status().isCreated())).get("id").asText();
    UUID person = fx.admin().queryForObject("SELECT internal_id FROM org.scim_resource WHERE id = ?::uuid",
        UUID.class, first);
    // Nguồn khác gửi cùng số định danh, cùng ngày sinh: khớp đúng người, không tạo người mới.
    String other = body(scim(post("/scim/v2/Users")
        .content(userBody("U-9", "binh", "Trần Thị Bình", NATIONAL, "1990-01-01")), tenant, "IDP_SO_TC",
        List.of("service_client")).andExpect(status().isCreated())).get("id").asText();
    assertThat(fx.admin().queryForObject("SELECT internal_id FROM org.scim_resource WHERE id = ?::uuid",
        UUID.class, other)).isEqualTo(person);
    // Cùng số định danh, khác ngày sinh: không tự khớp, vào bảng lệch, trả 409.
    scim(post("/scim/v2/Users").content(userBody("NV-003", "binh2", "Trần Bình", NATIONAL, "1991-02-02")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.scimType").value("uniqueness"));
    // Nguồn cấu hình đối soát trước: người chưa có cũng vào bảng lệch.
    scim(post("/scim/v2/Users").content(userBody("X-1", "moi", "Lê Văn Mới", "001090099999", "1970-05-05")),
        tenant, "IDP_DOI_SOAT", List.of("service_client")).andExpect(status().isConflict());
    assertThat(fx.admin().queryForObject("SELECT count(*) FROM org.sync_discrepancy WHERE tenant_id = ? "
        + "AND field = 'person_match' AND source_connector = 'SCIM'", Integer.class, tenant)).isEqualTo(2);
    assertThat(fx.admin().queryForObject("SELECT count(DISTINCT id) FROM org.person WHERE tenant_id = ?",
        Integer.class, tenant)).isOne();
    // Tạo lại cùng externalId đã có: 409.
    scim(post("/scim/v2/Users").content(userBody("NV-002", "binh.khac", "Trần Thị Bình", NATIONAL, "1990-01-01")))
        .andExpect(status().isConflict());
  }

  @Test
  void nhomAnhXaDonViVaThanhVienLaPhanCongChinh() throws Exception {
    String user = body(scim(post("/scim/v2/Users")
        .content(userBody("NV-010", "cuong.lv", "Lê Văn Cường", "001090011111", "1982-03-03")))
        .andExpect(status().isCreated())).get("id").asText();
    UUID person = fx.admin().queryForObject("SELECT internal_id FROM org.scim_resource WHERE id = ?::uuid",
        UUID.class, user);
    scim(post("/scim/v2/Groups").content("""
        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"KHONG-CO","displayName":"Không có"}"""))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.scimType").value("invalidValue"));
    String groupA = body(scim(post("/scim/v2/Groups").content("""
        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"SO-TC","displayName":"Sở Tài chính",
         "members":[{"value":"%s"}]}""".formatted(user))).andExpect(status().isCreated())
        .andExpect(jsonPath("$.members[0].value").value(user))).get("id").asText();
    assertThat(primaryUnit(person)).isEqualTo(unitA);

    String groupB = body(scim(post("/scim/v2/Groups").content("""
        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"SO-NV","displayName":"Sở Nội vụ"}"""))
        .andExpect(status().isCreated())).get("id").asText();
    // Chuyển thành viên sang nhóm B bằng PATCH: phân công chính chuyển sang đơn vị B.
    scim(patch("/scim/v2/Groups/" + groupB).content("""
        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
         "Operations":[{"op":"add","path":"members","value":[{"value":"%s"}]}]}""".formatted(user)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(1));
    assertThat(primaryUnit(person)).isEqualTo(unitB);
    scim(get("/scim/v2/Groups/" + groupA)).andExpect(status().isOk()).andExpect(jsonPath("$.members.length()")
        .value(0));
    scim(get("/scim/v2/Groups").param("filter", "externalId eq \"SO-NV\"")).andExpect(jsonPath("$.totalResults")
        .value(1));
    // Gỡ thành viên: phân công kết thúc.
    scim(patch("/scim/v2/Groups/" + groupB).content("""
        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
         "Operations":[{"op":"remove","path":"members[value eq \\"%s\\"]"}]}""".formatted(user)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(0));
    assertThat(primaryUnit(person)).isNull();
    assertThat(fx.outboxCount(tenant, "org.assignment.changed.v1")).isGreaterThanOrEqualTo(3);
    scim(put("/scim/v2/Groups/" + groupA).content("""
        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Sở Tài chính",
         "members":[{"value":"%s"}]}""".formatted(user))).andExpect(status().isOk());
    assertThat(primaryUnit(person)).isEqualTo(unitA);
    scim(delete("/scim/v2/Groups/" + groupA)).andExpect(status().isNoContent());
    assertThat(primaryUnit(person)).isNull();
  }

  @Test
  void clientChiGhiTrongTenantCuaMinh() throws Exception {
    String id = body(scim(post("/scim/v2/Users")
        .content(userBody("NV-020", "dung", "Phạm Dũng", "001090022222", "1979-09-09")))
        .andExpect(status().isCreated())).get("id").asText();
    UUID tenantB = UUID.randomUUID();
    // Client của tenant A ghi vào tenant B qua tiêu đề: 403.
    scim(post("/scim/v2/Users").header("X-Tenant-Id", tenantB.toString())
        .content(userBody("NV-021", "x", "X", "001090033333", "1979-09-09"))).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"));
    // Client của tenant B không thấy, không sửa được tài nguyên của tenant A.
    scim(get("/scim/v2/Users/" + id), tenantB, "HRM_SO_TC", List.of("service_client"))
        .andExpect(status().isNotFound());
    scim(delete("/scim/v2/Users/" + id), tenantB, "HRM_SO_TC", List.of("service_client"))
        .andExpect(status().isNotFound());
    // Token người dùng (không phải client SCIM): 403.
    scim(get("/scim/v2/Users"), tenant, "mission-web", List.of("tenant_admin")).andExpect(status().isForbidden());
    mvc.perform(get("/scim/v2/Users")).andExpect(status().isUnauthorized());
    assertThat(fx.admin().queryForObject("SELECT count(*) FROM org.scim_sync_log WHERE tenant_id = ?",
        Integer.class, tenantB)).isEqualTo(1);
  }

  private UUID primaryUnit(UUID person) {
    List<UUID> units = fx.admin().queryForList("SELECT org_unit_id FROM org.assignment WHERE person_id = ? "
        + "AND kind = 'CHINH' AND recorded_to = 'infinity' AND valid_period @> " + HOM_NAY, UUID.class, person);
    assertThat(units).hasSizeLessThanOrEqualTo(1);
    return units.isEmpty() ? null : units.get(0);
  }

  private void assertNoPlaintext(String nationalId, CapturedOutput output) {
    List<String> tables = fx.admin().queryForList("SELECT c.relname FROM pg_class c JOIN pg_namespace n "
        + "ON n.oid = c.relnamespace WHERE n.nspname = 'org' AND c.relkind IN ('r','p') AND NOT c.relispartition",
        String.class);
    for (String t : tables) {
      Integer hits = fx.admin().queryForObject("SELECT count(*) FROM org." + t + " x WHERE x::text LIKE ? "
          + "OR x::text LIKE '%' || encode(convert_to(?, 'UTF8'), 'hex') || '%'", Integer.class,
          "%" + nationalId + "%", nationalId);
      assertThat(hits).as("org." + t).isZero();
    }
    assertThat(output.getAll()).doesNotContain(nationalId);
  }
}
