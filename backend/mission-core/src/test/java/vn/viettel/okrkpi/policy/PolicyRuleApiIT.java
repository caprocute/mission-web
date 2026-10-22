package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcPolicyPackRepository;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-04 trên PostgreSQL thật với gói ND 335 mẫu: đọc quy tắc kèm ETag, lưu
 * quy tắc kiểm kiểu CEL theo khai báo của gói (422 {@code POLICY-422-CEL-TYPE}, MSG-15-01), sửa
 * phiên bản {@code DAT_KIEM_THU} đưa về {@code NHAP} (MSG-15-03), phiên bản {@code CHO_PHE_DUYET}
 * bị 409 {@code POLICY-409-VERSION-FROZEN}; ba thao tác khớp {@code policies.yaml}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class PolicyRuleApiIT extends ContractConformanceTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyPackService packs;
  @Autowired private JdbcPolicyPackRepository repository;

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
    VersionView root =
        TenantContext.call(
            tenant,
            () ->
                packs.importPack(
                    tenant,
                    author,
                    new PackImport(
                        manifest, PackOrigin.GOC_TOAN_CUC, "KHOI_CHINH_QUYEN", null, null)));
    UUID v = root.version().id();
    inTenant(
        jdbc -> {
          repository.insertRules(
              tenant,
              List.of(
                  new PolicyRule(
                      Ids.newUuidV7(),
                      v,
                      "kpi_A",
                      "nv.ke_hoach == 0 ? 1.0 : math.least(double(nv.so_luong_duyet)"
                          + " / double(nv.ke_hoach), 1.0)",
                      "DECIMAL",
                      "{\"digits\":3,\"mode\":\"NUA_LEN\"}",
                      "PL 3 mục 1",
                      10,
                      "{}",
                      false),
                  new PolicyRule(
                      Ids.newUuidV7(),
                      v,
                      "nhom",
                      "kpi_nhom * double(g.diem_toi_da)",
                      "DECIMAL",
                      "{\"digits\":2,\"mode\":\"NUA_LEN\"}",
                      "PL A5",
                      70,
                      "{\"nhom\":[{\"ma\":\"KET_QUA_NHIEM_VU\",\"diem_toi_da\":70}]}",
                      false)),
              author);
          return null;
        });
    return root;
  }

  private String path(VersionView root, String key) {
    return "/api/v1/policies/packs/" + root.pack().id() + "/versions/1.4.0/rules/" + key;
  }

  private MockHttpServletRequestBuilder save(
      VersionView root, String key, String etag, String content) {
    MockHttpServletRequestBuilder b =
        put(path(root, key))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content);
    return etag == null ? b : b.header("If-Match", etag);
  }

  private void status(UUID versionId, String status) throws Exception {
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE policy.policy_pack_version SET status = ?, golden_pass_rate = 1"
                        + " WHERE tenant_id = ? AND id = ?")
                .params(status, tenant.value(), versionId)
                .update());
  }

  private static final String KPI_C =
      "{\"expression\":\"math.greatest(0.0, 1.0 - tham_so.tru_tien_do *"
          + " double(nv.ngay_lam_viec_cham_chu_quan))\",\"resultType\":\"DECIMAL\","
          + "\"rounding\":{\"digits\":3,\"mode\":\"NUA_LEN\"},\"legalRef\":\"PL 3 mục 3\","
          + "\"evalOrder\":40,\"params\":{\"tru_tien_do\":0.05,\"loai_ngay\":\"NGAY_LAM_VIEC\"}}";

  @Test
  void rulesAreReadTypeCheckedAndSavedPerContract() throws Exception {
    VersionView root = seed();
    String base = "/api/v1/policies/packs/" + root.pack().id() + "/versions/1.4.0/rules";

    MvcResult list = perform(get(base));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        list, "/policies/packs/{id}/versions/{v}/rules", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items"))
        .extracting(n -> n.get("ruleKey").asText())
        .containsExactly("kpi_A", "nhom");

    MvcResult one = perform(get(path(root, "kpi_A")));
    assertThat(one.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertResponseConforms(
        one, "/policies/packs/{id}/versions/{v}/rules/{key}", PathItem.HttpMethod.GET);
    assertThat(body(one).get("rounding").get("digits").asInt()).isEqualTo(3);
    assertThat(perform(get(path(root, "khong_co"))).getResponse().getStatus()).isEqualTo(404);

    MvcResult created = perform(save(root, "kpi_C", "\"0\"", KPI_C));
    assertThat(created.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        created, "/policies/packs/{id}/versions/{v}/rules/{key}", PathItem.HttpMethod.PUT);
    assertThat(body(created).get("statusReverted").asBoolean()).isFalse();
    assertThat(body(created).get("params").get("tru_tien_do").asDouble()).isEqualTo(0.05);

    MvcResult updated =
        perform(
            save(
                root,
                "kpi_A",
                "\"0\"",
                "{\"expression\":\"math.least(double(nv.so_luong_duyet), 1.0)\","
                    + "\"resultType\":\"DECIMAL\"}"));
    assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    assertThat(updated.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(body(updated).get("legalRef").asText()).isEqualTo("PL 3 mục 1");
    assertThat(
            perform(
                    save(
                        root,
                        "kpi_A",
                        "\"0\"",
                        "{\"expression\":\"1.0\",\"resultType\":\"DECIMAL\"}"))
                .getResponse()
                .getStatus())
        .isEqualTo(409);

    MvcResult undeclared =
        perform(
            save(
                root,
                "kpi_A",
                "\"1\"",
                "{\"expression\":\"ho_so.diem * 1.0\",\"resultType\":\"DECIMAL\"}"));
    assertThat(undeclared.getResponse().getStatus()).isEqualTo(422);
    JsonNode problem = body(undeclared);
    assertThat(problem.get("code").asText()).isEqualTo("POLICY-422-CEL-TYPE");
    assertThat(problem.get("msg").asText()).isEqualTo("MSG-15-01");
    assertThat(problem.get("detail").asText()).contains("tại khối kpi_A");

    MvcResult sum =
        perform(
            save(
                root,
                "nhom",
                "\"0\"",
                "{\"expression\":\"kpi_nhom * double(g.diem_toi_da)\",\"resultType\":\"DECIMAL\","
                    + "\"params\":{\"nhom\":[{\"ma\":\"KET_QUA_NHIEM_VU\",\"diem_toi_da\":69}]}}"));
    assertThat(sum.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(sum).get("code").asText()).isEqualTo("POLICY-422-GROUP-SUM");
    assertThat(body(sum).get("detail").asText())
        .isEqualTo("Tổng điểm tối đa các nhóm là 99, phải bằng 100.");

    MvcResult range =
        perform(
            save(
                root,
                "kpi_C",
                "\"0\"",
                KPI_C.replace("\"tru_tien_do\":0.05", "\"tru_tien_do\":0.3")));
    assertThat(range.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(range).get("code").asText()).isEqualTo("COMMON-422-VALIDATION");

    assertThat(perform(save(root, "kpi_C", null, KPI_C)).getResponse().getStatus()).isEqualTo(428);
  }

  @Test
  void testedVersionRevertsToDraftAndPendingApprovalIsFrozen() throws Exception {
    VersionView root = seed();
    UUID versionId = root.version().id();
    status(versionId, "DAT_KIEM_THU");
    MvcResult reverted = perform(save(root, "kpi_C", "\"0\"", KPI_C));
    assertThat(reverted.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(reverted).get("statusReverted").asBoolean()).isTrue();
    assertThat(body(reverted).get("versionStatus").asText()).isEqualTo("NHAP");
    String stored =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT status FROM policy.policy_pack_version WHERE id = ?")
                    .param(versionId)
                    .query(String.class)
                    .single());
    assertThat(stored).isEqualTo("NHAP");

    status(versionId, "CHO_PHE_DUYET");
    MvcResult frozen = perform(save(root, "kpi_C", "\"0\"", KPI_C));
    assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(frozen).get("code").asText()).isEqualTo("POLICY-409-VERSION-FROZEN");
  }
}
