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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.application.port.UiPackNotices;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.infrastructure.web.InMemoryJobRegistry;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-08 trên PostgreSQL thật: soạn, lưu nháp, xem trước, gửi duyệt chạy tác
 * vụ {@code TEST_RUN}, từ chối; {@code If-Match} cũ trả 409, sửa phiên bản không còn nháp trả 409
 * {@code UI-409-NOT-DRAFT}, tự duyệt trả 403 {@code UI-403-SAME-PERSON}; bảy thao tác khớp {@code
 * config.yaml}. Sổ cái và EVT-21 thay bằng bản ghi nhận trong bộ nhớ.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import(FeatureFlagApiIT.AuditConfig.class)
@DirtiesContext
class UiPackApiIT extends ContractConformanceTest {

  static final List<String> NOTICES = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class UiPackNoticeConfig {

    @Bean
    @Primary
    UiPackNotices testUiPackNotices() {
      return new UiPackNotices() {
        @Override
        public void reviewRequested(TenantId t, String name, UiPackVersion v, List<UUID> to) {
          NOTICES.add("review:" + v.version());
        }

        @Override
        public void rejected(
            TenantId t, String name, UiPackVersion v, String comment, List<UUID> to) {
          NOTICES.add("rejected:" + comment);
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

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = PeriodApiIT.CallerConfig.ACTOR;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  @AfterEach
  void reset() {
    PeriodApiIT.CallerConfig.reset();
    InMemoryJobRegistry.clearActorOverride();
  }

  private void as(UUID user) {
    PeriodApiIT.CallerConfig.current = user;
    PeriodApiIT.CallerConfig.acr = "aal2";
    PeriodApiIT.CallerConfig.authTime = Instant.now().getEpochSecond() - 30;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static MockHttpServletRequestBuilder withBody(
      MockHttpServletRequestBuilder b, String ifMatch, String content) {
    b.header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON);
    if (content != null) {
      b.content(content);
    }
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  @Test
  void draftPreviewSubmitAndRejectFollowContract() throws Exception {
    as(author);
    MvcResult packs = perform(get("/api/v1/ui/packs"));
    assertThat(packs.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(packs, "/ui/packs", PathItem.HttpMethod.GET);
    String packId = body(packs).get("items").get(0).get("id").asText();
    String base = "/api/v1/ui/packs/" + packId + "/versions";

    MvcResult created = perform(withBody(post(base), null, "{\"version\":\"1.0.0\"}"));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/ui/packs/{id}/versions", PathItem.HttpMethod.POST);
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    String v = base + "/1.0.0";

    MvcResult detail = perform(get(v));
    assertResponseConforms(detail, "/ui/packs/{id}/versions/{v}", PathItem.HttpMethod.GET);
    ObjectNode draft = (ObjectNode) body(detail);
    ObjectNode update = json.createObjectNode();
    update.set("tokens", draft.get("tokens"));
    update.set("layouts", draft.get("layouts"));
    update.set("assets", draft.get("assets"));
    update.put("componentRegistryVersion", "2026.3");
    update.put("elderMode", true);
    update.put("fewSteps", true);

    assertThat(perform(withBody(put(v), null, update.toString())).getResponse().getStatus())
        .isEqualTo(428);
    MvcResult saved = perform(withBody(put(v), "\"0\"", update.toString()));
    assertThat(saved.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(saved, "/ui/packs/{id}/versions/{v}", PathItem.HttpMethod.PUT);
    assertThat(body(saved).get("fewSteps").asBoolean()).isTrue();
    assertThat(perform(withBody(put(v), "\"0\"", update.toString())).getResponse().getStatus())
        .isEqualTo(409);
    ObjectNode html = update.deepCopy();
    ((ObjectNode) html.at("/layouts/home")).putObject("props").put("title", "<b>Chào</b>");
    MvcResult invalid = perform(withBody(put(v), "\"1\"", html.toString()));
    assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(invalid).get("errors").findValuesAsText("field"))
        .contains("layouts.home.props.title");
    // Bố cục kiểm theo registry 2026.3 (CORE-08-09).
    ObjectNode unknown = update.deepCopy();
    ((ObjectNode) unknown.at("/layouts/home/children/0")).put("component", "vt.widget.kpiTile");
    MvcResult unknownResult = perform(withBody(put(v), "\"1\"", unknown.toString()));
    assertThat(unknownResult.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(unknownResult).get("code").asText()).isEqualTo("UI-422-UNKNOWN-COMPONENT");
    ObjectNode cel = update.deepCopy();
    ((ObjectNode) cel.at("/layouts/home/children/0")).put("visibleWhen", "user.unitId");
    MvcResult celResult = perform(withBody(put(v), "\"1\"", cel.toString()));
    assertThat(body(celResult).get("code").asText()).isEqualTo("UI-422-CEL-INVALID");
    ObjectNode http = update.deepCopy();
    ((ObjectNode) http.at("/layouts/home/children/0/props")).put("limit", 30);
    MvcResult propsResult = perform(withBody(put(v), "\"1\"", http.toString()));
    assertThat(body(propsResult).get("code").asText()).isEqualTo("COMMON-422-VALIDATION");

    MvcResult preview = perform(get(v + "/preview").param("mode", "elderly"));
    assertThat(preview.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(preview, "/ui/packs/{id}/versions/{v}/preview", PathItem.HttpMethod.GET);
    assertThat(body(preview).get("cssVariables").get("--vt-font-size-body").asText())
        .isEqualTo("18px");

    MvcResult submitted = perform(withBody(post(v + "/submit"), "\"1\"", null));
    assertThat(submitted.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(
        submitted, "/ui/packs/{id}/versions/{v}/submit", PathItem.HttpMethod.POST);
    String jobId = body(submitted).get("id").asText();
    InMemoryJobRegistry.setActorOverride(ActorId.of(author));
    JsonNode job = null;
    for (int i = 0; i < 100; i++) {
      job = body(perform(get("/api/v1/jobs/" + jobId)));
      if (job.get("status").asText().equals("HOAN_TAT")
          || job.get("status").asText().equals("THAT_BAI")) {
        break;
      }
      Thread.sleep(50);
    }
    assertThat(job.get("type").asText()).isEqualTo("TEST_RUN");
    assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("HOAN_TAT");
    assertThat(body(perform(get(v))).get("status").asText()).isEqualTo("CHO_DUYET");
    assertThat(NOTICES).contains("review:1.0.0");

    MvcResult listed = perform(get(base).param("status", "CHO_DUYET"));
    assertResponseConforms(listed, "/ui/packs/{id}/versions", PathItem.HttpMethod.GET);
    assertThat(body(listed).get("items")).hasSize(1);

    MvcResult notDraft = perform(withBody(put(v), "\"3\"", update.toString()));
    assertThat(notDraft.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(notDraft).get("code").asText()).isEqualTo("UI-409-NOT-DRAFT");

    String reject = "{\"comment\":\"Màu chữ phụ chưa đạt AA\"}";
    MvcResult self = perform(withBody(post(v + "/reject"), "\"3\"", reject));
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("UI-403-SAME-PERSON");

    as(UUID.randomUUID());
    MvcResult rejected = perform(withBody(post(v + "/reject"), "\"3\"", reject));
    assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        rejected, "/ui/packs/{id}/versions/{v}/reject", PathItem.HttpMethod.POST);
    assertThat(body(rejected).get("status").asText()).isEqualTo("NHAP");
    assertThat(body(rejected).get("reviewComment").asText()).isEqualTo("Màu chữ phụ chưa đạt AA");
    assertThat(NOTICES).contains("rejected:Màu chữ phụ chưa đạt AA");
    assertThat(FeatureFlagApiIT.AUDITS)
        .contains(
            "GUI_DUYET_UI_PACK:1.0.0:2", "KIEM_TRA_UI_PACK_DAT:1.0.0:3", "TU_CHOI_UI_PACK:1.0.0:4");

    // Bản sao từ phiên bản cũ; UI Pack lạ trả 404.
    as(author);
    MvcResult clone =
        perform(
            withBody(post(base), null, "{\"version\":\"1.1.0\",\"cloneFromVersion\":\"1.0.0\"}"));
    assertThat(clone.getResponse().getStatus()).isEqualTo(201);
    assertThat(
            perform(get("/api/v1/ui/packs/" + UUID.randomUUID() + "/versions"))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }
}
