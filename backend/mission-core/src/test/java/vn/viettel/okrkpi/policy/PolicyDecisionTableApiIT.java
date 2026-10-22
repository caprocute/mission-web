package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-05 trên PostgreSQL thật: lưu bảng xếp loại {@code xep-loai-dang} của
 * TL-01 kèm cảnh báo dòng bị che (MSG-15-04), FEEL sai bị 422 {@code POLICY-422-FEEL}, biến lạ
 * trong mẫu câu bị 422 {@code POLICY-422-TEMPLATE-VAR} (MSG-15-05), xuất XML DMN 1.4, phiên bản
 * không ở {@code NHAP} bị 409, thiếu {@code If-Match} bị 428; bốn thao tác khớp {@code
 * policies.yaml}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class PolicyDecisionTableApiIT extends ContractConformanceTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyPackService packs;

  @Autowired
  @Qualifier("policyDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("policyTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.POLICIES;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private <T> T inTenant(Function<JdbcClient, T> sql) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> sql.apply(jdbc)));
  }

  private VersionView seed() throws Exception {
    Map<String, Object> manifest =
        PolicyPackFixtures.yaml(
            PolicyPackFixtures.pack(PolicyPackFixtures.GOI_NHA_NUOC).resolve("policy.yaml"));
    return TenantContext.call(
        tenant,
        () ->
            packs.importPack(
                tenant,
                author,
                new PackImport(manifest, PackOrigin.GOC_TOAN_CUC, "KHOI_CHINH_QUYEN", null, null)));
  }

  private static String base(VersionView root) {
    return "/api/v1/policies/packs/" + root.pack().id() + "/versions/1.4.0/decision-tables";
  }

  private MockHttpServletRequestBuilder save(
      VersionView root, String key, String etag, String content) {
    MockHttpServletRequestBuilder b =
        put(base(root) + "/" + key)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content);
    return etag == null ? b : b.header("If-Match", etag);
  }

  private static String row(String diem, String vuot, String truot, String level, String why) {
    return "{\"inputs\":{\"diem\":\""
        + diem
        + "\",\"ty_le_vuot\":\""
        + vuot
        + "\",\"truot_then_chot_chu_quan\":\""
        + truot
        + "\"},\"ratingLevel\":\""
        + level
        + "\",\"clause\":\"QĐ 366 Điều 11\",\"explanation\":\""
        + why
        + "\"}";
  }

  private static String table(String templates, String... rows) {
    return "{\"hitPolicy\":\"FIRST\",\"legalRef\":\"QĐ 366-QĐ/TW\",\"inputs\":["
        + "{\"name\":\"diem\",\"label\":\"Điểm\",\"type\":\"number\"},"
        + "{\"name\":\"ty_le_vuot\",\"type\":\"number\"},"
        + "{\"name\":\"truot_then_chot_chu_quan\",\"type\":\"boolean\"}],\"rules\":["
        + String.join(",", rows)
        + "],\"explanationTemplates\":"
        + templates
        + "}";
  }

  private static final String TEMPLATES =
      "{\"HTTNV\":\"Đạt {diem} điểm, chưa đủ điều kiện {muc_tren}: {dieu_kien_thieu}.\"}";

  private static final String XEP_LOAI_DANG =
      table(
          TEMPLATES,
          row(">= 90", ">= 0.30", "-", "HTXSNV", "Điểm từ 90 và vượt từ 30%"),
          row(">= 80", "-", "false", "HTTNV", "Điểm từ 80"),
          row(">= 60", "-", "-", "HTNV", "Điểm từ 60"),
          row("-", "-", "-", "KHTNV", "Còn lại"));

  @Test
  void decisionTablesAreSavedCheckedAndExportedPerContract() throws Exception {
    VersionView root = seed();

    MvcResult created = perform(save(root, "xep_loai", "\"0\"", XEP_LOAI_DANG));
    assertThat(created.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        created,
        "/policies/packs/{id}/versions/{v}/decision-tables/{key}",
        PathItem.HttpMethod.PUT);
    assertThat(body(created).get("warnings")).isEmpty();
    assertThat(body(created).get("dmnHash").asText()).hasSize(64);

    MvcResult list = perform(get(base(root)));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        list, "/policies/packs/{id}/versions/{v}/decision-tables", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items"))
        .extracting(n -> n.get("tableKey").asText())
        .containsExactly("xep_loai");

    MvcResult one = perform(get(base(root) + "/xep_loai"));
    assertThat(one.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertResponseConforms(
        one, "/policies/packs/{id}/versions/{v}/decision-tables/{key}", PathItem.HttpMethod.GET);
    assertThat(body(one).get("rules")).hasSize(4);
    assertThat(perform(get(base(root) + "/khong_co")).getResponse().getStatus()).isEqualTo(404);

    MvcResult dmn = perform(get(base(root) + "/xep_loai/dmn"));
    assertThat(dmn.getResponse().getStatus()).isEqualTo(200);
    assertThat(dmn.getResponse().getContentType()).startsWith("application/xml");
    assertThat(dmn.getResponse().getHeader("Content-Disposition")).contains("xep_loai-1.4.0.dmn");
    assertThat(dmn.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("https://www.omg.org/spec/DMN/20211108/MODEL/")
        .contains("hitPolicy=\"FIRST\"");

    MvcResult shadowed =
        perform(
            save(
                root,
                "xep_loai",
                "\"0\"",
                table(
                    TEMPLATES,
                    row(">= 60", "-", "-", "HTNV", "Điểm từ 60"),
                    row(">= 80", "-", "false", "HTTNV", "Điểm từ 80"),
                    row("-", "-", "-", "KHTNV", "Còn lại"))));
    assertThat(shadowed.getResponse().getStatus()).isEqualTo(200);
    JsonNode warning = body(shadowed).get("warnings").get(0);
    assertThat(warning.get("msg").asText()).isEqualTo("MSG-15-04");
    assertThat(warning.get("row").asInt()).isEqualTo(2);
    assertThat(warning.get("shadowedBy").asInt()).isEqualTo(1);

    MvcResult feel =
        perform(
            save(
                root,
                "xep_loai",
                "\"1\"",
                table(
                    TEMPLATES,
                    row(">= 9O", "-", "-", "HTNV", "Sai"),
                    row("-", "-", "-", "KHTNV", "Còn lại"))));
    assertThat(feel.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(feel).get("code").asText()).isEqualTo("POLICY-422-FEEL");
    assertThat(body(feel).get("detail").asText()).contains("dòng 1, cột diem");

    MvcResult variable =
        perform(
            save(
                root,
                "xep_loai",
                "\"1\"",
                table("{\"HTNV\":\"Điểm {diem_ca_nhan}\"}", row("-", "-", "-", "HTNV", "Mọi"))));
    assertThat(variable.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(variable).get("code").asText()).isEqualTo("POLICY-422-TEMPLATE-VAR");
    assertThat(body(variable).get("msg").asText()).isEqualTo("MSG-15-05");

    assertThat(perform(save(root, "xep_loai", null, XEP_LOAI_DANG)).getResponse().getStatus())
        .isEqualTo(428);
    assertThat(perform(save(root, "xep_loai", "\"0\"", XEP_LOAI_DANG)).getResponse().getStatus())
        .isEqualTo(409);

    UUID versionId = root.version().id();
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE policy.policy_pack_version SET status = 'DAT_KIEM_THU',"
                        + " golden_pass_rate = 1 WHERE tenant_id = ? AND id = ?")
                .params(tenant.value(), versionId)
                .update());
    MvcResult frozen = perform(save(root, "xep_loai", "\"1\"", XEP_LOAI_DANG));
    assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(frozen).get("code").asText()).isEqualTo("POLICY-409-VERSION-FROZEN");
  }
}
