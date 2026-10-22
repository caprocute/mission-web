package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService.CaseEdit;
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.infrastructure.signing.KeyPairPolicyCaseSigner;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-06 trên PostgreSQL thật: thêm ca vàng (201, ETag, Location), mã trùng
 * 422 {@code POLICY-422-CASE-CODE-EXISTS}, người tạo tự ký duyệt bị 403 {@code
 * POLICY-403-SAME-AUTHOR}, người khác ký duyệt được và chữ ký kiểm lại được, ký lại bị 409, thiếu
 * {@code If-Match} bị 428, danh sách kèm tóm tắt bộ vàng; bốn thao tác khớp {@code policies.yaml}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class GoldenTestCaseApiIT extends ContractConformanceTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyPackService packs;
  @Autowired private GoldenTestCaseService cases;
  @Autowired private KeyPairPolicyCaseSigner signer;

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

  private MockHttpServletRequestBuilder create(String content) {
    return post("/api/v1/policies/test-cases")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(content);
  }

  private MockHttpServletRequestBuilder approve(UUID id, String etag) {
    MockHttpServletRequestBuilder b =
        post("/api/v1/policies/test-cases/" + id + "/approve")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"comment\":\"Đã đối chiếu tính tay\"}");
    return etag == null ? b : b.header("If-Match", etag);
  }

  private static String tc(UUID pack, String code) {
    return "{\"policyPackId\":\""
        + pack
        + "\",\"caseCode\":\""
        + code
        + "\",\"description\":\"Ngày nghỉ bù trùng ngày lễ không tính ngày chậm\","
        + "\"input\":{\"nv\":{\"ke_hoach\":4,\"so_luong_duyet\":4,\"muc_chat_luong\":1,"
        + "\"ngay_cham_chu_quan\":0}},\"expected\":{\"kpi_C\":1.0},"
        + "\"legalRef\":\"PL 3; PL A4\",\"source\":\"BA\"}";
  }

  @Test
  void goldenCasesAreCreatedListedAndApprovedPerContract() throws Exception {
    UUID pack = seed().pack().id();

    MvcResult created = perform(create(tc(pack, "TC-149")));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/policies/test-cases", PathItem.HttpMethod.POST);
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    JsonNode tc149 = body(created);
    UUID ownId = UUID.fromString(tc149.get("id").asText());
    assertThat(created.getResponse().getHeader("Location")).endsWith("/test-cases/" + ownId);
    assertThat(tc149.get("status").asText()).isEqualTo("NHAP");
    assertThat(tc149.get("signed").asBoolean()).isFalse();

    MvcResult duplicate = perform(create(tc(pack, "TC-149")));
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(duplicate).get("code").asText()).isEqualTo("POLICY-422-CASE-CODE-EXISTS");
    MvcResult unknown = perform(create(tc(pack, "TC-151").replace("\"nv\":", "\"ho_so_that\":")));
    assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(unknown).get("code").asText()).isEqualTo("COMMON-422-VALIDATION");

    MvcResult self = perform(approve(ownId, "\"0\""));
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("POLICY-403-SAME-AUTHOR");

    GoldenTestCase byOther =
        TenantContext.call(
            tenant,
            () ->
                cases.addCase(
                    tenant,
                    UUID.randomUUID(),
                    new CaseEdit(
                        pack,
                        "TC-150",
                        "Hoàn thành đúng kế hoạch",
                        Map.of("nv", Map.of("ke_hoach", 4, "so_luong_duyet", 4)),
                        Map.of("kpi_A", 1.0),
                        null,
                        "KHACH_HANG")));
    assertThat(perform(approve(byOther.id(), null)).getResponse().getStatus()).isEqualTo(428);
    MvcResult approved = perform(approve(byOther.id(), "\"0\""));
    assertThat(approved.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(approved, "/policies/test-cases/{id}/approve", PathItem.HttpMethod.POST);
    assertThat(approved.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(body(approved).get("status").asText()).isEqualTo("DA_DUYET");
    assertThat(body(approved).get("signed").asBoolean()).isTrue();
    GoldenTestCase stored =
        TenantContext.call(tenant, () -> cases.findCase(tenant, byOther.id()).orElseThrow());
    assertThat(
            signer.verify(
                cases.canonical(stored).getBytes(StandardCharsets.UTF_8), stored.signature()))
        .isTrue();
    MvcResult again = perform(approve(byOther.id(), "\"1\""));
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("POLICY-409-NOT-PENDING");

    MvcResult one = perform(get("/api/v1/policies/test-cases/" + ownId));
    assertThat(one.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertResponseConforms(one, "/policies/test-cases/{id}", PathItem.HttpMethod.GET);
    assertThat(
            perform(get("/api/v1/policies/test-cases/" + UUID.randomUUID()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    MvcResult list = perform(get("/api/v1/policies/test-cases?pack=" + pack + "&limit=1"));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(list, "/policies/test-cases", PathItem.HttpMethod.GET);
    JsonNode page = body(list);
    assertThat(page.get("items"))
        .extracting(n -> n.get("caseCode").asText())
        .containsExactly("TC-149");
    assertThat(page.get("suite").get("total").asInt()).isEqualTo(1);
    assertThat(page.get("suite").get("pending").asInt()).isEqualTo(1);
    MvcResult next =
        perform(
            get(
                "/api/v1/policies/test-cases?pack="
                    + pack
                    + "&limit=1&cursor="
                    + page.get("nextCursor").asText()));
    assertThat(body(next).get("items"))
        .extracting(n -> n.get("caseCode").asText())
        .containsExactly("TC-150");
    MvcResult filtered =
        perform(get("/api/v1/policies/test-cases?pack=" + pack + "&status=DA_DUYET&q=TC-15"));
    assertThat(body(filtered).get("items")).hasSize(1);
    assertThat(
            perform(get("/api/v1/policies/test-cases?pack=" + pack + "&status=SAI"))
                .getResponse()
                .getStatus())
        .isEqualTo(422);
    assertThat(
            perform(get("/api/v1/policies/test-cases?pack=" + UUID.randomUUID()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(List.of(signer.keyId())).isNotEmpty();
  }
}
