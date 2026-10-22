package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.infrastructure.web.InMemoryJobRegistry;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-15 trên PostgreSQL thật với danh mục {@code
 * data/seed/plugins/catalog.json}: tenant bật plugin qua cấu hình {@code ui.plugins}; plugin ngoài
 * danh mục làm job kiểm tra thất bại; plugin hợp lệ có widget trong {@code listUiComponents} và nằm
 * trong {@code UiPackRuntime.plugins} của gói đã phát hành, đúng hợp đồng.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.config.ui-pack.plugin-catalog=../../data/seed/plugins/catalog.json"
    })
@AutoConfigureMockMvc(addFilters = false)
@Import({
  FeatureFlagApiIT.AuditConfig.class,
  UiPackApiIT.UiPackNoticeConfig.class,
  UiPackPublishApiIT.PublishConfig.class
})
@DirtiesContext
class UiPackPluginApiIT extends ContractConformanceTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Autowired
  @Qualifier("configDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = PeriodApiIT.CallerConfig.ACTOR;
  private final UUID reviewer = UUID.randomUUID();

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

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String ifMatch) {
    b.header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON);
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  private JsonNode await(String jobId, UUID owner) throws Exception {
    InMemoryJobRegistry.setActorOverride(ActorId.of(owner));
    JsonNode job = null;
    for (int i = 0; i < 200; i++) {
      job = body(perform(get("/api/v1/jobs/" + jobId)));
      String s = job.get("status").asText();
      if (s.equals("HOAN_TAT") || s.equals("THAT_BAI")) {
        break;
      }
      Thread.sleep(50);
    }
    return job;
  }

  private void plugins(String value) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(tm)
                .execute(
                    s ->
                        jdbc.sql(
                                "INSERT INTO config.tenant_setting (id, created_by, updated_by,"
                                    + " setting_key, value, value_schema) VALUES (?, ?, ?,"
                                    + " 'ui.plugins', ?::jsonb, 'json.string-array')"
                                    + " ON CONFLICT (tenant_id, setting_key, scope_unit_id)"
                                    + " DO UPDATE SET value = EXCLUDED.value")
                            .param(UUID.randomUUID())
                            .param(author)
                            .param(author)
                            .param(value)
                            .update()));
  }

  private String submit(String v) throws Exception {
    String tag = perform(get(v)).getResponse().getHeader("ETag");
    return body(perform(write(post(v + "/submit"), tag))).get("id").asText();
  }

  @Test
  void enabledPluginIsCheckedListedAndPublishedInManifest() throws Exception {
    as(author);
    String packId = body(perform(get("/api/v1/ui/packs"))).get("items").get(0).get("id").asText();
    String base = "/api/v1/ui/packs/" + packId + "/versions";
    perform(write(post(base), null).content("{\"version\":\"1.0.0\"}"));
    String v = base + "/1.0.0";

    // Plugin ngoài danh mục: job kiểm tra thất bại.
    plugins("[\"fe-plugin-khong-co\"]");
    JsonNode rejected = await(submit(v), author);
    assertThat(rejected.get("status").asText()).as(rejected.toString()).isEqualTo("THAT_BAI");
    MvcResult preview = perform(get(v + "/preview"));
    assertResponseConforms(preview, "/ui/packs/{id}/versions/{v}/preview", PathItem.HttpMethod.GET);
    assertThat(body(preview).get("checks").toString())
        .contains("Có plugin chuyên ngành chưa đủ điều kiện nạp.");

    plugins("[\"fe-plugin-giaoduc\"]");
    MvcResult components = perform(get("/api/v1/ui/components"));
    assertResponseConforms(components, "/ui/components", PathItem.HttpMethod.GET);
    assertThat(components.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("vt.widget.giaoducLopChuNhiem")
        .contains("fe-plugin-giaoduc");

    JsonNode checked = await(submit(v), author);
    assertThat(checked.get("status").asText()).as(checked.toString()).isEqualTo("HOAN_TAT");
    as(reviewer);
    String tag = perform(get(v)).getResponse().getHeader("ETag");
    JsonNode job =
        await(body(perform(write(post(v + "/publish"), tag))).get("id").asText(), reviewer);
    assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("HOAN_TAT");

    as(author);
    MvcResult current = perform(get("/api/v1/ui/packs/current"));
    assertThat(current.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(current, "/ui/packs/current", PathItem.HttpMethod.GET);
    JsonNode plugin = body(current).get("plugins").get(0);
    assertThat(plugin.get("name").asText()).isEqualTo("fe-plugin-giaoduc");
    assertThat(plugin.get("remoteEntry").asText())
        .isEqualTo("/plugins/giaoduc/1.0.0/remoteEntry.json");
    assertThat(plugin.get("integrity").asText()).startsWith("sha384-");
    // Gói dựng lại từ CSDL vẫn khớp băm đã ký dù cấu hình tenant đổi sau phát hành.
    String etag = current.getResponse().getHeader("ETag");
    plugins("[]");
    assertThat(perform(get("/api/v1/ui/packs/current")).getResponse().getHeader("ETag"))
        .isEqualTo(etag);
  }
}
