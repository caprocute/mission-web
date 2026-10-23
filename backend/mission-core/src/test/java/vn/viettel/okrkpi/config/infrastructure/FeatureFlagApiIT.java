package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import vn.viettel.okrkpi.config.api.FeatureFlagApi;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.infrastructure.web.PeriodCaller;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-07 trên PostgreSQL thật: danh sách cờ có mặc định của nền tảng, đổi cờ
 * kèm {@code If-Match}, cờ lạ trả 404, phiên bản cũ trả 409, bật theo đơn vị qua {@code
 * FeatureFlagApi}; ngưỡng bất thường đọc mặc định, ghi phiên bản mới và lưu lịch sử. Sổ cái thay
 * bằng bản ghi nhận trong bộ nhớ. Ngữ cảnh riêng được đóng sau lớp để không giữ thêm kết nối của
 * chín nguồn dữ liệu (container dùng chung hết chỗ kết nối khi quá nhiều ngữ cảnh còn sống).
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class FeatureFlagApiIT extends ContractConformanceTest {

  static final List<String> AUDITS = new CopyOnWriteArrayList<>();

  /** Người gọi và đơn vị của ngữ cảnh phân công đang chọn. */
  static volatile UUID caller = UUID.randomUUID();

  static volatile String callerUnit;

  @TestConfiguration
  static class AuditConfig {

    @Bean
    @Primary
    ConfigAudit testConfigAudit() {
      return (tenant, actor, action, type, objectId, aggregateId, version) ->
          AUDITS.add(action + ":" + objectId + ":" + version);
    }

    @Bean
    @Primary
    PeriodCaller featureFlagCaller() {
      return new PeriodCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(caller);
        }

        @Override
        public Optional<String> unitId() {
          return Optional.ofNullable(callerUnit);
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
  @Autowired private FeatureFlagApi flagApi;
  @Autowired private TenantSettingApi settingApi;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private boolean enabled(String key, FeatureFlagApi.FlagContext context) throws Exception {
    return TenantContext.call(tenant, () -> flagApi.isEnabled(tenant, key, context));
  }

  private static MockHttpServletRequestBuilder putJson(String path, String ifMatch, String body) {
    MockHttpServletRequestBuilder b =
        put(path)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  @Test
  void featureFlagsListDefaultsAndChangeWithOptimisticLock() throws Exception {
    MvcResult list = perform(get("/api/v1/config/feature-flags").param("limit", "2"));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(list, "/config/feature-flags", PathItem.HttpMethod.GET);
    JsonNode page = body(list);
    assertThat(page.get("items")).as(page.toString()).hasSize(2);
    assertThat(page.hasNonNull("nextCursor")).as(page.toString()).isTrue();
    assertThat(page.get("items").get(0).get("rowVersion").asInt()).isZero();
    MvcResult next =
        perform(
            get("/api/v1/config/feature-flags")
                .param("limit", "200")
                .param("cursor", page.get("nextCursor").asText()));
    assertThat(body(next).get("items").get(0).get("flagKey").asText())
        .isGreaterThan(page.get("items").get(1).get("flagKey").asText());

    String path = "/api/v1/config/feature-flags/okr.aiSuggest";
    UUID unit = UUID.randomUUID();
    String on =
        """
        {"enabled":true,"rollout":{"unitIds":["%s"],"percent":100}}
        """
            .formatted(unit);
    assertThat(perform(putJson(path, null, on)).getResponse().getStatus()).isEqualTo(428);
    MvcResult saved = perform(putJson(path, "\"0\"", on));
    assertThat(saved.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(saved, "/config/feature-flags/{key}", PathItem.HttpMethod.PUT);
    assertThat(saved.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(AUDITS).contains("DOI_CO_TINH_NANG:okr.aiSuggest:1");

    MvcResult stale = perform(putJson(path, "\"0\"", "{\"enabled\":false}"));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    MvcResult unknown =
        perform(putJson("/api/v1/config/feature-flags/khong.co", "\"0\"", "{\"enabled\":true}"));
    assertThat(unknown.getResponse().getStatus()).isEqualTo(404);

    FeatureFlagApi.FlagContext inUnit =
        new FeatureFlagApi.FlagContext(UUID.randomUUID(), List.of(UUID.randomUUID(), unit));
    FeatureFlagApi.FlagContext outside =
        new FeatureFlagApi.FlagContext(UUID.randomUUID(), List.of(UUID.randomUUID()));
    assertThat(enabled("okr.aiSuggest", inUnit)).isTrue();
    assertThat(enabled("okr.aiSuggest", outside)).isFalse();
    assertThat(enabled("khong.co", inUnit)).isFalse();

    MvcResult off = perform(putJson(path, "\"1\"", "{\"enabled\":false}"));
    assertThat(body(off).get("enabled").asBoolean()).isFalse();
    assertThat(enabled("okr.aiSuggest", inUnit)).isFalse();
    // Tenant khác không thấy thay đổi.
    TenantId other = TenantId.of(UUID.randomUUID());
    MvcResult otherList =
        TenantContext.call(
            other,
            () ->
                mvc.perform(get("/api/v1/config/feature-flags").param("limit", "200")).andReturn());
    assertThat(body(otherList).get("items"))
        .allSatisfy(f -> assertThat(f.get("rowVersion").asInt()).isZero());
  }

  @Test
  void effectiveFlagsForCurrentUserFollowUnitTargeting() throws Exception {
    UUID unit = UUID.randomUUID();
    String on =
        """
        {"enabled":true,"rollout":{"unitIds":["%s"],"percent":100}}
        """
            .formatted(unit);
    String path = "/api/v1/config/feature-flags/okr.aiSuggest";
    MvcResult current = perform(get("/api/v1/config/feature-flags").param("limit", "200"));
    String version = "0";
    for (JsonNode f : body(current).get("items")) {
      if (f.get("flagKey").asText().equals("okr.aiSuggest")) {
        version = f.get("rowVersion").asText();
      }
    }
    assertThat(perform(putJson(path, "\"" + version + "\"", on)).getResponse().getStatus())
        .isEqualTo(200);

    JsonNode inUnit = effectiveAs(unit);
    assertThat(inUnit.get("flags").get("okr.aiSuggest").asBoolean()).as(inUnit.toString()).isTrue();
    assertThat(inUnit.get("flags").get("okr.alignmentGraph").asBoolean()).isTrue();
    assertThat(inUnit.hasNonNull("evaluatedAt")).isTrue();
    JsonNode outside = effectiveAs(UUID.randomUUID());
    assertThat(outside.get("flags").get("okr.aiSuggest").asBoolean()).isFalse();
  }

  /** Gọi {@code GET /ui/feature-flags} với người dùng chọn ngữ cảnh phân công ở {@code unit}. */
  private JsonNode effectiveAs(UUID unit) throws Exception {
    callerUnit = unit.toString();
    caller = UUID.randomUUID();
    try {
      MvcResult r = perform(get("/api/v1/ui/feature-flags"));
      assertThat(r.getResponse().getStatus())
          .as(r.getResponse().getContentAsString())
          .isEqualTo(200);
      assertResponseConforms(r, "/ui/feature-flags", PathItem.HttpMethod.GET);
      return body(r);
    } finally {
      callerUnit = null;
    }
  }

  @Test
  void anomalyThresholdsReadDefaultsThenVersion() throws Exception {
    String path = "/api/v1/config/anomaly-thresholds";
    MvcResult initial = perform(get(path));
    assertThat(initial.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(initial, "/config/anomaly-thresholds", PathItem.HttpMethod.GET);
    assertThat(initial.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(body(initial).get("signals")).hasSize(8);

    String change =
        """
        {"signals":[{"signalCode":"CAO_BANG","enabled":true,"params":{"sameLevelPercent":85}}],\
        "reason":"Điều chỉnh theo kết quả rà soát quý IV năm 2026"}
        """;
    MvcResult first = perform(putJson(path, "\"0\"", change));
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(first, "/config/anomaly-thresholds", PathItem.HttpMethod.PUT);
    assertThat(body(first).get("version").asInt()).isEqualTo(2);
    MvcResult second = perform(putJson(path, "\"1\"", change));
    assertThat(second.getResponse().getHeader("ETag")).isEqualTo("\"2\"");
    assertThat(body(second).get("version").asInt()).isEqualTo(3);
    assertThat(perform(putJson(path, "\"1\"", change)).getResponse().getStatus()).isEqualTo(409);
    MvcResult shortReason =
        perform(
            putJson(
                path,
                "\"2\"",
                "{\"signals\":[{\"signalCode\":\"CAO_BANG\",\"enabled\":true,\"params\":{}}],"
                    + "\"reason\":\"ngắn\"}"));
    assertThat(shortReason.getResponse().getStatus()).isEqualTo(422);

    JsonNode current = body(perform(get(path)));
    assertThat(current.get("signals").findValuesAsText("signalCode")).contains("CAO_BANG");
    assertThat(
            TenantContext.call(
                tenant,
                () ->
                    settingApi
                        .get(tenant, "anomaly.thresholds.v2", null)
                        .map(v -> v.get("version").asInt())))
        .contains(2);
    assertThat(AUDITS).contains("DOI_NGUONG_BAT_THUONG:anomaly.thresholds:2");
  }
}
