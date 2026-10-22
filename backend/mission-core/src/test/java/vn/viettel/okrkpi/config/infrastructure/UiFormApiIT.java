package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.config.application.FormSchemaService;
import vn.viettel.okrkpi.config.domain.FormSchema;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-13 trên PostgreSQL thật: tạo, lưu nháp, đọc phiên bản và bản hiện hành
 * có ETag/304, gắn vào UI Pack nháp; đổi kiểu trường tính điểm 409 {@code UI-409-SCORED-FIELD}, CEL
 * sai 422 {@code UI-422-CEL-INVALID}, khoá trùng và điều kiện vòng 422, chưa đạt bộ test 422 {@code
 * UI-422-FORM-TEST-FAILED}; sáu thao tác khớp {@code config.yaml}. MOD-05 thay bằng cổng giả khai
 * báo một trường tính điểm.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import({FeatureFlagApiIT.AuditConfig.class, UiPackApiIT.UiPackNoticeConfig.class})
@DirtiesContext
class UiFormApiIT extends ContractConformanceTest {

  static final UUID POLICY = UUID.randomUUID();

  @TestConfiguration
  static class PolicyConfig {
    @Bean
    PolicySchemaPort testPolicySchemas() {
      return new PolicySchemaPort() {
        @Override
        public Optional<Set<String>> fields(TenantId t, String ref, String range) {
          return Optional.empty();
        }

        @Override
        public Map<String, String> scoredFields(TenantId t, UUID policyPack) {
          return POLICY.equals(policyPack) ? Map.of("ty_le_hsba_dat", "number") : Map.of();
        }
      };
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private FormSchemaService forms;

  @Autowired
  @Qualifier("configDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = PeriodApiIT.CallerConfig.ACTOR;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  @AfterEach
  void reset() {
    PeriodApiIT.CallerConfig.reset();
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    PeriodApiIT.CallerConfig.current = author;
    PeriodApiIT.CallerConfig.acr = "aal2";
    PeriodApiIT.CallerConfig.authTime = Instant.now().getEpochSecond() - 30;
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String ifMatch, String content) {
    b.header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(content);
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  private static final String SCHEMA =
      """
      {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
       "properties":{
         "ty_le_hsba_dat":{"type":"number","x-cel":"self >= 0.0 && self <= 100.0","x-classification":"C1"},
         "su_co_y_khoa":{"type":"string","enum":["CO","KHONG"],"x-classification":"C1"},
         "mo_ta_su_co":{"type":"string","maxLength":2000,"x-classification":"C2"}}}
      """;

  private String update(String schema, String ui) {
    return "{\"schemaJson\":"
        + schema
        + ",\"uiJson\":"
        + ui
        + ",\"celRules\":[{\"field\":\"ty_le_hsba_dat\",\"expr\":\"form.ty_le_hsba_dat <= 100.0\","
        + "\"message\":\"Tỷ lệ tối đa 100\"}]}";
  }

  private <T> T inTenant(java.util.function.Function<JdbcClient, T> query) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> query.apply(jdbc)));
  }

  @Test
  void formLifecycleFollowsContract() throws Exception {
    String base = "/api/v1/ui/forms/phieu-vc-yte/versions";
    MvcResult created =
        perform(
            write(
                post(base),
                null,
                "{\"version\":\"12\",\"policyPackVersionId\":\"" + POLICY + "\"}"));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/ui/forms/{key}/versions", PathItem.HttpMethod.POST);
    String v = base + "/12";

    String ui =
        "{\"mo_ta_su_co\":{\"hideExpression\":\"form.su_co_y_khoa != 'CO'\",\"label\":\"Mô tả\"}}";
    MvcResult saved = perform(write(put(v), "\"0\"", update(SCHEMA, ui)));
    assertThat(saved.getResponse().getStatus())
        .as(saved.getResponse().getContentAsString())
        .isEqualTo(200);
    assertResponseConforms(saved, "/ui/forms/{key}/versions/{v}", PathItem.HttpMethod.PUT);
    assertThat(saved.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(perform(write(put(v), null, update(SCHEMA, ui))).getResponse().getStatus())
        .isEqualTo(428);

    // Đổi kiểu trường tính điểm: 409 MSG-18-07.
    String retyped =
        SCHEMA
            .replace("\"type\":\"number\"", "\"type\":\"string\"")
            .replace("self >= 0.0 && self <= 100.0", "size(self) > 0");
    MvcResult scored = perform(write(put(v), "\"1\"", update(retyped, "{}")));
    assertThat(scored.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(scored).get("code").asText()).isEqualTo("UI-409-SCORED-FIELD");
    // CEL sai: 422 MSG-18-06.
    MvcResult cel =
        perform(write(put(v), "\"1\"", update(SCHEMA.replace("self <= 100.0", "self <="), "{}")));
    assertThat(cel.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(cel).get("code").asText()).isEqualTo("UI-422-CEL-INVALID");
    assertThat(body(cel).get("errors").findValuesAsText("field"))
        .contains("schemaJson.properties.ty_le_hsba_dat.x-cel");
    // Khoá trùng: 422.
    String duplicate =
        SCHEMA.replace(
            "\"mo_ta_su_co\":{",
            "\"nhom\":{\"type\":\"object\",\"properties\":{\"su_co_y_khoa\":{\"type\":\"string\","
                + "\"x-classification\":\"C1\"}}},\"mo_ta_su_co\":{");
    MvcResult dup = perform(write(put(v), "\"1\"", update(duplicate, "{}")));
    assertThat(dup.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(dup).get("errors").findValuesAsText("field"))
        .contains("schemaJson.properties.nhom.properties.su_co_y_khoa");
    // Điều kiện hiển thị vòng: 422.
    String loop =
        "{\"mo_ta_su_co\":{\"hideExpression\":\"form.su_co_y_khoa != 'CO'\"},"
            + "\"su_co_y_khoa\":{\"hideExpression\":\"form.mo_ta_su_co == ''\"}}";
    MvcResult cycle = perform(write(put(v), "\"1\"", update(SCHEMA, loop)));
    assertThat(cycle.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(cycle).get("code").asText()).isEqualTo("COMMON-422-VALIDATION");

    MvcResult detail = perform(get(v));
    assertResponseConforms(detail, "/ui/forms/{key}/versions/{v}", PathItem.HttpMethod.GET);
    assertThat(perform(get(v).header("If-None-Match", "\"1\"")).getResponse().getStatus())
        .isEqualTo(304);
    MvcResult listed = perform(get("/api/v1/ui/forms").param("q", "yte"));
    assertResponseConforms(listed, "/ui/forms", PathItem.HttpMethod.GET);
    assertThat(body(listed).get("items").get(0).get("latestVersion").asText()).isEqualTo("12");
    assertThat(perform(get("/api/v1/ui/forms/phieu-vc-yte/current")).getResponse().getStatus())
        .isEqualTo(404);

    // Gắn vào UI Pack nháp: chưa đạt bộ test thì 422, đạt thì gắn được.
    String packId = body(perform(get("/api/v1/ui/packs"))).get("items").get(0).get("id").asText();
    String packBase = "/api/v1/ui/packs/" + packId + "/versions";
    perform(write(post(packBase), null, "{\"version\":\"1.0.0\"}"));
    String attach = "{\"formKey\":\"phieu-vc-yte\",\"formVersion\":\"12\"}";
    MvcResult untested = perform(write(post(packBase + "/1.0.0/forms"), "\"0\"", attach));
    assertThat(untested.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(untested).get("code").asText()).isEqualTo("UI-422-FORM-TEST-FAILED");

    String hash =
        forms.scoringHash(
            TenantContext.call(tenant, () -> forms.get(tenant, "phieu-vc-yte", "12")));
    ObjectNode run = json.createObjectNode().put("total", 3).put("passed", 3).put("parity", true);
    run.putArray("cases");
    run.put("ranAt", "2027-01-04T01:00:00Z");
    run.put(FormSchemaService.SCORING_HASH, hash);
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE config.form_schema SET last_test_run = ?::jsonb"
                        + " WHERE form_key = 'phieu-vc-yte' AND version = '12'")
                .param(run.toString())
                .update());
    MvcResult attached = perform(write(post(packBase + "/1.0.0/forms"), "\"0\"", attach));
    assertThat(attached.getResponse().getStatus())
        .as(attached.getResponse().getContentAsString())
        .isEqualTo(200);
    assertResponseConforms(attached, "/ui/packs/{id}/versions/{v}/forms", PathItem.HttpMethod.POST);
    assertThat(body(attached).get("forms").get(0).get("formKey").asText())
        .isEqualTo("phieu-vc-yte");
    assertThat(body(perform(get(v))).get("lastTestRun").get("passed").asInt()).isEqualTo(3);

    // Bản hiện hành sau khi phát hành cùng UI Pack (giả lập bằng trạng thái): ETag và 304.
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE config.form_schema SET status = 'DA_PHAT_HANH'"
                        + " WHERE form_key = 'phieu-vc-yte' AND version = '12'")
                .update());
    MvcResult current = perform(get("/api/v1/ui/forms/phieu-vc-yte/current"));
    assertThat(current.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(current, "/ui/forms/{key}/current", PathItem.HttpMethod.GET);
    assertThat(
            perform(
                    get("/api/v1/ui/forms/phieu-vc-yte/current")
                        .header("If-None-Match", current.getResponse().getHeader("ETag")))
                .getResponse()
                .getStatus())
        .isEqualTo(304);
    MvcResult notDraft = perform(write(put(v), "\"1\"", update(SCHEMA, ui)));
    assertThat(body(notDraft).get("code").asText()).isEqualTo("UI-409-NOT-DRAFT");
  }

  @Test
  void testRunsComparesEnginesAndFollowContract() throws Exception {
    String base = "/api/v1/ui/forms/phieu-thu-nghiem/versions";
    perform(write(post(base), null, "{\"version\":\"1\"}"));
    String v = base + "/1";
    assertThat(perform(write(put(v), "\"0\"", update(SCHEMA, "{}"))).getResponse().getStatus())
        .isEqualTo(200);
    String cases =
        "{\"cases\":[{\"caseId\":\"TC-01\",\"input\":{\"ty_le_hsba_dat\":104},"
            + "\"browserValid\":%s,\"browserErrors\":[]},"
            + "{\"caseId\":\"TC-02\",\"input\":{\"ty_le_hsba_dat\":95.5},\"browserValid\":true}],"
            + "\"renderMs50Fields\":31}";
    MvcResult mismatch = perform(write(post(v + "/test-runs"), null, cases.formatted("true")));
    assertThat(mismatch.getResponse().getStatus())
        .as(mismatch.getResponse().getContentAsString())
        .isEqualTo(200);
    assertResponseConforms(
        mismatch, "/ui/forms/{key}/versions/{v}/test-runs", PathItem.HttpMethod.POST);
    JsonNode m = body(mismatch);
    assertThat(m.get("parity").asBoolean()).isFalse();
    assertThat(m.get("passed").asInt()).isEqualTo(1);
    assertThat(m.get("cases").get(0).get("serverErrors").get(0).get("field").asText())
        .isEqualTo("ty_le_hsba_dat");
    assertThat(m.has(FormSchemaService.SCORING_HASH)).isFalse();

    MvcResult ok = perform(write(post(v + "/test-runs"), null, cases.formatted("false")));
    assertThat(body(ok).get("parity").asBoolean()).isTrue();
    // Đổi thuần trình bày không làm mất hiệu lực bộ test (BR-18-07).
    perform(write(put(v), "\"3\"", update(SCHEMA, "{\"mo_ta_su_co\":{\"label\":\"Mô tả\"}}")));
    FormSchema saved = TenantContext.call(tenant, () -> forms.get(tenant, "phieu-thu-nghiem", "1"));
    assertThat(saved.rowVersion()).isEqualTo(4);
    assertThat(forms.testsPassed(saved)).isTrue();
    assertThat(perform(get(v)).getResponse().getContentAsString()).doesNotContain("scoringHash");
  }
}
