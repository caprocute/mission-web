package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.BindingReader;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;

/**
 * API nội bộ tham số luồng (DV-06-08) trên PostgreSQL thật: danh mục mẫu, lưu có {@code If-Match}
 * và linter, mô phỏng không ghi engine của cell, kích hoạt và gỡ kích hoạt vào {@code wf_binding}
 * mà {@code JdbcBindingReader} đọc khi khởi động luồng, chỉ client dịch vụ được gọi.
 */
class InternalBindingApiIT extends WorkflowIntegrationTest {

  static final UUID CLIENT = UUID.randomUUID();
  static final String BASE = "/internal/v1/workflow";

  @Autowired private ObjectMapper json;
  @Autowired private BindingReader reader;
  @Autowired private PlatformTransactionManager transactionManager;

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();
  private UUID tenant;
  private UUID period;

  @BeforeEach
  void tenant() {
    tenant = UUID.randomUUID();
    period = UUID.randomUUID();
  }

  /** Token client credentials của mission-core: không có claim tenant, tenant đi trong header. */
  private MockHttpServletRequestBuilder svc(MockHttpServletRequestBuilder request) {
    return request
        .with(
            jwt()
                .jwt(
                    j ->
                        j.subject(CLIENT.toString())
                            .claim("azp", "mission-core")
                            .claim("preferred_username", "service-account-mission-core"))
                .authorities(new SimpleGrantedAuthority("ROLE_service_client")))
        .header("X-Tenant-Id", tenant.toString())
        .accept(MediaType.APPLICATION_JSON);
  }

  private Map<String, Object> body(String role, int levels) {
    List<Map<String, Object>> cap = new ArrayList<>();
    cap.add(new HashMap<>(Map.of("vai_tro", "manager", "pham_vi", "DON_VI_TRUC_TIEP")));
    for (int i = 1; i < levels; i++) {
      cap.add(
          new HashMap<>(
              Map.of("vai_tro", role, "pham_vi", "CAP_TREN_TRUC_TIEP", "han_ngay_lam_viec", 2)));
    }
    Map<String, Object> params = new HashMap<>();
    params.put("so_cap", levels);
    params.put("cap", cap);
    params.put("thoi_han_moi_buoc", "P3D_LV");
    params.put("nhac_truoc", "P1D_LV");
    params.put("khi_qua_han", "CHUYEN_CAP_PHO_UY_QUYEN");
    Map<String, Object> scope = new HashMap<>();
    scope.put("flow", "DANG");
    scope.put("subjectGroupId", null);
    scope.put("fromPeriodId", period.toString());
    Map<String, Object> b = new HashMap<>();
    b.put("processKind", "KE_HOACH_QUY");
    b.put("templateKey", "duyet-ke-hoach-quy");
    b.put("templateVersionNo", 1);
    b.put("scope", scope);
    b.put("params", params);
    return b;
  }

  private MvcResult save(UUID version, String ifMatch, Map<String, Object> body, int expected)
      throws Exception {
    MockHttpServletRequestBuilder r =
        svc(put(BASE + "/bindings/KE_HOACH_QUY").param("policyPackVersionId", version.toString()))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(body));
    if (ifMatch != null) {
      r = r.header("If-Match", ifMatch);
    }
    return mvc.perform(r).andExpect(status().is(expected)).andReturn();
  }

  private JsonNode read(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private JsonNode call(String path, UUID version) throws Exception {
    return read(
        mvc.perform(
                svc(post(BASE + path).param("policyPackVersionId", version.toString()))
                    .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andReturn());
  }

  private Optional<ApprovalParams> startParams() throws Exception {
    return TenantContext.call(
        TenantId.of(tenant),
        () ->
            new TransactionTemplate(transactionManager)
                .execute(t -> reader.find(tenant, "KE_HOACH_QUY", ApprovalFlow.DANG)));
  }

  @Test
  void danhMucMauVaKhaiBaoThamSo() throws Exception {
    mvc.perform(svc(get(BASE + "/templates").param("status", "HIEU_LUC").param("limit", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].key").value("duyet-de-nghi"))
        .andExpect(jsonPath("$.items[0].status").value("HIEU_LUC"))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    mvc.perform(svc(get(BASE + "/templates").param("processKind", "KE_HOACH_QUY")))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("Duyệt kế hoạch quý"))
        .andExpect(jsonPath("$.items[0].testCount").value(7))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
    mvc.perform(svc(get(BASE + "/templates/duyet-ke-hoach-quy/versions/1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paramSchema.properties.so_cap.maximum").value(4))
        .andExpect(jsonPath("$.previewSteps.length()").value(2))
        .andExpect(jsonPath("$.previewSteps[1].signRequired").value(true));
    mvc.perform(svc(get(BASE + "/templates/duyet-ke-hoach-quy/versions/9")))
        .andExpect(status().isNotFound());
  }

  @Test
  void luuCanIfMatchVaLinter() throws Exception {
    UUID version = UUID.randomUUID();
    save(version, null, body("leader", 3), 428);
    mvc.perform(
            svc(
                get(BASE + "/bindings/KE_HOACH_QUY")
                    .param("policyPackVersionId", version.toString())))
        .andExpect(status().isNotFound());

    JsonNode saved = read(save(version, "\"0\"", body("leader", 3), 200));
    assertThat(saved.path("status").asText()).isEqualTo("NHAP");
    assertThat(saved.path("rowVersion").asLong()).isEqualTo(1);
    assertThat(saved.path("lint").path("passed").asBoolean()).isTrue();
    assertThat(saved.path("templateTests").path("passed").asInt()).isEqualTo(7);
    assertThat(saved.path("templateTests").path("total").asInt()).isEqualTo(7);
    assertThat(saved.path("scope").path("fromPeriodId").asText()).isEqualTo(period.toString());

    mvc.perform(
            svc(
                get(BASE + "/bindings/KE_HOACH_QUY")
                    .param("policyPackVersionId", version.toString())))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"1\""))
        .andExpect(jsonPath("$.params.so_cap").value(3));

    JsonNode conflict = read(save(version, "\"0\"", body("leader", 4), 409));
    assertThat(conflict.path("code").asText()).isEqualTo("POLICY-409-VERSION-CONFLICT");

    JsonNode invalid = read(save(version, "\"1\"", body("truong_phong", 3), 422));
    assertThat(invalid.path("code").asText()).isEqualTo("POLICY-422-WF-PARAMS");
    assertThat(invalid.path("errors"))
        .extracting(e -> e.path("rule").asText())
        .containsOnly("vai-tro");

    Map<String, Object> signDropped = body("leader", 3);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> cap =
        (List<Map<String, Object>>) ((Map<String, Object>) signDropped.get("params")).get("cap");
    cap.get(2).put("ky_so", false);
    JsonNode unsigned = read(save(version, "\"1\"", signDropped, 422));
    assertThat(unsigned.path("errors").get(0).path("rule").asText()).isEqualTo("ky-so-bat-buoc");

    Map<String, Object> unknown = body("leader", 3);
    unknown.put("templateKey", "duyet-tu-do");
    assertThat(read(save(version, "\"1\"", unknown, 422)).path("code").asText())
        .isEqualTo("POLICY-422-TEMPLATE-NOT-ACTIVE");

    JsonNode second = read(save(version, "\"1\"", body("leader", 4), 200));
    assertThat(second.path("rowVersion").asLong()).isEqualTo(2);
    mvc.perform(svc(get(BASE + "/bindings").param("policyPackVersionId", version.toString())))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].params.so_cap").value(4));
  }

  @Test
  void moPhongKhongGhiEngineCuaCell() throws Exception {
    long executions = count("flowable.act_ru_execution");
    long history = count("flowable.act_hi_procinst");
    long drafts = count("flowable.wf_binding_draft");
    Map<String, Object> request = body("leader", 3);
    request.put("samples", List.of("DANG_TRA_LAI_CAP_CUOI"));
    JsonNode result =
        read(
            mvc.perform(
                    svc(post(BASE + "/bindings/simulate"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(result.path("steps")).hasSize(3);
    assertThat(result.path("lint").path("passed").asBoolean()).isTrue();
    assertThat(result.path("templateTests").path("passed").asInt()).isEqualTo(7);
    assertThat(result.path("sampleRuns")).hasSize(1);
    assertThat(result.path("sampleRuns").get(0).path("endState").asText()).isEqualTo("TRA_LAI");
    assertThat(count("flowable.act_ru_execution")).isEqualTo(executions);
    assertThat(count("flowable.act_hi_procinst")).isEqualTo(history);
    assertThat(count("flowable.wf_binding_draft")).isEqualTo(drafts);

    Map<String, Object> bad = body("leader", 5);
    JsonNode linted =
        read(
            mvc.perform(
                    svc(post(BASE + "/bindings/simulate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(bad)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(linted.path("lint").path("passed").asBoolean()).isFalse();
    assertThat(linted.path("lint").path("issues").get(0).path("rule").asText()).isEqualTo("so-cap");
  }

  @Test
  void kichHoatChepVaoWfBindingRoiGoTraLaiDongCu() throws Exception {
    UUID v1 = UUID.randomUUID();
    UUID v2 = UUID.randomUUID();
    save(v1, "\"0\"", body("leader", 2), 200);
    save(v2, "\"0\"", body("decider", 3), 200);
    assertThat(startParams()).isEmpty();

    JsonNode first = call("/bindings/activate", v1);
    assertThat(first.path("items").get(0).path("status").asText()).isEqualTo("HIEU_LUC");
    assertThat(startParams().orElseThrow().levels()).hasSize(2);
    assertThat(bindingVersion()).isEqualTo(v1);
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM flowable.wf_template WHERE template_code = 'duyet-ke-hoach-quy'"
                    + " AND tenant_id = ?",
                Long.class,
                tenant))
        .isEqualTo(1);
    // Đã phát hành thì không sửa được.
    assertThat(read(save(v1, "\"2\"", body("leader", 3), 409)).path("code").asText())
        .isEqualTo("POLICY-409-VERSION-FROZEN");
    // Gọi lại kích hoạt không đổi gì.
    call("/bindings/activate", v1);

    call("/bindings/activate", v2);
    assertThat(startParams().orElseThrow().levels())
        .extracting(r -> r.role())
        .containsExactly("manager", "decider", "decider");
    assertThat(bindingRows()).isEqualTo(1);
    assertThat(bindingVersion()).isEqualTo(v2);
    mvc.perform(svc(get(BASE + "/bindings").param("policyPackVersionId", v1.toString())))
        .andExpect(jsonPath("$.items[0].status").value("NGUNG"));

    JsonNode undone = call("/bindings/deactivate", v2);
    assertThat(undone.path("items").get(0).path("status").asText()).isEqualTo("NGUNG");
    assertThat(startParams().orElseThrow().levels()).hasSize(2);
    // Gỡ phiên bản mới trả lại cả phiên bản gói của dòng cũ (V10).
    assertThat(bindingVersion()).isEqualTo(v1);
    mvc.perform(svc(get(BASE + "/bindings").param("policyPackVersionId", v1.toString())))
        .andExpect(jsonPath("$.items[0].status").value("HIEU_LUC"));

    call("/bindings/deactivate", v1);
    assertThat(startParams()).isEmpty();
    assertThat(bindingRows()).isZero();
  }

  @Test
  void chiClientDichVuDuocGoi() throws Exception {
    mvc.perform(
            get(BASE + "/templates")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(UUID.randomUUID().toString())
                                    .claim("tenant_id", tenant.toString()))))
        .andExpect(status().isForbidden());
    mvc.perform(get(BASE + "/templates")).andExpect(status().isUnauthorized());
  }

  private long count(String table) {
    return owner.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private UUID bindingVersion() {
    return owner.queryForObject(
        "SELECT policy_pack_version_id FROM flowable.wf_binding WHERE tenant_id = ?",
        UUID.class,
        tenant);
  }

  private long bindingRows() {
    return owner.queryForObject(
        "SELECT count(*) FROM flowable.wf_binding WHERE tenant_id = ?", Long.class, tenant);
  }
}
