package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CNH-02 trên PostgreSQL thật: tuỳ chọn cá nhân tạo không cần {@code If-Match}
 * (201), sửa thiếu header trả 428, lệch phiên bản trả 409, sai lược đồ trả 422, đọc lại có 304 theo
 * ETag; người khác không thấy và không xoá được (404); bộ lọc đã lưu tối đa 20 trên một màn hình,
 * từ chối văn bản tự do, chỉ một bộ lọc mặc định; lớp phủ trang chủ sắp xếp, ẩn widget không bắt
 * buộc, giữ widget bắt buộc và đổi ETag của {@code getHomeLayout}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import(FeatureFlagApiIT.AuditConfig.class)
@DirtiesContext
class UserPreferenceApiIT extends ContractConformanceTest {

  private static final UUID OWNER = PeriodApiIT.CallerConfig.ACTOR;
  private static final UUID OTHER = UUID.randomUUID();

  /**
   * Người gọi lấy từ {@code PeriodApiIT.CallerConfig}: lớp cấu hình lồng đó được quét chung cho các
   * IT cùng gói, nên khai báo thêm một bean {@code periodCaller} sẽ trùng tên.
   */
  private static final class CallerConfig {
    static void set(UUID user) {
      PeriodApiIT.CallerConfig.current = user;
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  @AfterEach
  void resetCaller() {
    CallerConfig.set(OWNER);
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private MvcResult as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
    CallerConfig.set(user);
    try {
      return perform(request);
    } finally {
      CallerConfig.set(OWNER);
    }
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String ifMatch, String content) {
    b.header("Idempotency-Key", UUID.randomUUID().toString());
    if (ifMatch != null) {
      b.header("If-Match", ifMatch);
    }
    return content == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(content);
  }

  private static int status(MvcResult r) {
    return r.getResponse().getStatus();
  }

  @Test
  void preferencesAreOwnedVersionedAndValidated() throws Exception {
    String path = "/api/v1/config/me/preferences/ui.display";
    String elderly = "{\"value\":{\"mode\":\"elderly\",\"reducedSteps\":true,\"fontScale\":1.25}}";

    MvcResult empty = perform(get("/api/v1/config/me/preferences"));
    assertThat(status(empty)).isEqualTo(200);
    assertResponseConforms(empty, "/config/me/preferences", PathItem.HttpMethod.GET);
    assertThat(body(empty).get("items").size()).isZero();
    String emptyTag = empty.getResponse().getHeader("ETag");

    MvcResult created = perform(write(put(path), null, elderly));
    assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
    assertResponseConforms(created, "/config/me/preferences/{prefKey}", PathItem.HttpMethod.PUT);
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

    assertThat(status(perform(write(put(path), null, elderly)))).isEqualTo(428);
    MvcResult updated =
        perform(
            write(put(path), "\"1\"", "{\"value\":{\"mode\":\"default\",\"reducedSteps\":false}}"));
    assertThat(status(updated)).isEqualTo(200);
    assertThat(updated.getResponse().getHeader("ETag")).isEqualTo("\"2\"");
    assertThat(status(perform(write(put(path), "\"1\"", elderly)))).isEqualTo(409);

    MvcResult wrongMode =
        perform(write(put(path), "\"2\"", "{\"value\":{\"mode\":\"dark\",\"reducedSteps\":true}}"));
    assertThat(status(wrongMode)).isEqualTo(422);
    MvcResult extraField =
        perform(
            write(
                put(path),
                "\"2\"",
                "{\"value\":{\"mode\":\"default\",\"reducedSteps\":true,\"ghiChu\":\"x\"}}"));
    assertThat(status(extraField)).isEqualTo(422);
    assertThat(
            status(
                perform(
                    write(put("/api/v1/config/me/preferences/ui.theme"), null, "{\"value\":{}}"))))
        .isEqualTo(422);

    MvcResult all = perform(get("/api/v1/config/me/preferences"));
    String tag = all.getResponse().getHeader("ETag");
    assertThat(tag).isNotEqualTo(emptyTag);
    assertThat(body(all).at("/items/ui.display/value/mode").asText()).isEqualTo("default");
    assertThat(status(perform(get("/api/v1/config/me/preferences").header("If-None-Match", tag))))
        .isEqualTo(304);

    // Người khác: không thấy, không sửa, không xoá được tuỳ chọn của chủ.
    assertThat(body(as(OTHER, get("/api/v1/config/me/preferences"))).get("items").size()).isZero();
    assertThat(status(as(OTHER, write(delete(path), "\"2\"", null)))).isEqualTo(404);
    MvcResult otherCreates = as(OTHER, write(put(path), null, elderly));
    assertThat(status(otherCreates)).isEqualTo(201);
    assertThat(
            body(perform(get("/api/v1/config/me/preferences")))
                .at("/items/ui.display/value/mode")
                .asText())
        .isEqualTo("default");

    assertThat(status(perform(write(delete(path), "\"1\"", null)))).isEqualTo(409);
    assertThat(status(perform(write(delete(path), "\"2\"", null)))).isEqualTo(204);
    assertThat(body(perform(get("/api/v1/config/me/preferences"))).get("items").size()).isZero();
  }

  @Test
  void savedFiltersLimitDefaultAndOwnership() throws Exception {
    String base = "/api/v1/config/me/saved-filters";
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      MvcResult r =
          perform(
              write(
                  post(base),
                  null,
                  """
                  {"screenCode":"SCR-0201","name":"Bộ lọc %d","displayOrder":%d,
                   "criteria":{"statusCodes":["DA_GIAO"],"assigneeScope":"ME",
                               "dateRanges":{"dueDate":{"relativePreset":"THIS_WEEK"}}}}
                  """
                      .formatted(i, i)));
      assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
      if (i == 0) {
        assertResponseConforms(r, "/config/me/saved-filters", PathItem.HttpMethod.POST);
      }
      ids.add(body(r).get("id").asText());
    }
    MvcResult over =
        perform(
            write(
                post(base),
                null,
                "{\"screenCode\":\"SCR-0201\",\"name\":\"Thừa\",\"criteria\":{}}"));
    assertThat(status(over)).isEqualTo(422);
    MvcResult otherScreen =
        perform(
            write(
                post(base),
                null,
                "{\"screenCode\":\"SCR-0301\",\"name\":\"Thừa\",\"criteria\":{}}"));
    assertThat(status(otherScreen)).isEqualTo(201);
    MvcResult freeText =
        perform(
            write(
                post(base),
                null,
                "{\"screenCode\":\"SCR-0301\",\"name\":\"Tìm\",\"criteria\":{\"freeText\":\"họ tên\"}}"));
    assertThat(status(freeText)).isEqualTo(422);
    MvcResult duplicate =
        perform(
            write(
                post(base),
                null,
                "{\"screenCode\":\"SCR-0301\",\"name\":\"thừa\",\"criteria\":{}}"));
    assertThat(status(duplicate)).isEqualTo(422);

    String first = base + "/" + ids.get(0);
    String last = base + "/" + ids.get(19);
    String makeDefault =
        "{\"name\":\"Bộ lọc %s\",\"criteria\":{},\"isDefault\":true,\"displayOrder\":%s}";
    assertThat(status(perform(write(put(first), null, makeDefault.formatted(0, 0)))))
        .isEqualTo(428);
    MvcResult firstDefault = perform(write(put(first), "\"1\"", makeDefault.formatted(0, 0)));
    assertThat(status(firstDefault)).isEqualTo(200);
    assertResponseConforms(firstDefault, "/config/me/saved-filters/{id}", PathItem.HttpMethod.PUT);
    assertThat(status(perform(write(put(last), "\"1\"", makeDefault.formatted(19, 19)))))
        .isEqualTo(200);
    MvcResult list = perform(get(base).param("screenCode", "SCR-0201"));
    assertResponseConforms(list, "/config/me/saved-filters", PathItem.HttpMethod.GET);
    JsonNode items = body(list).get("items");
    assertThat(items).hasSize(20);
    assertThat(items.get(0).get("id").asText()).isEqualTo(ids.get(19));
    assertThat(items.findValues("isDefault").stream().filter(JsonNode::asBoolean).count())
        .isEqualTo(1);
    // Bộ lọc mặc định cũ được bỏ cờ nên phiên bản tăng: If-Match cũ trả 409.
    assertThat(status(perform(write(put(first), "\"2\"", makeDefault.formatted(0, 0)))))
        .isEqualTo(409);

    assertThat(body(as(OTHER, get(base).param("screenCode", "SCR-0201"))).get("items").size())
        .isZero();
    assertThat(status(as(OTHER, write(put(last), "\"2\"", makeDefault.formatted(19, 19)))))
        .isEqualTo(404);
    assertThat(status(as(OTHER, write(delete(last), "\"2\"", null)))).isEqualTo(404);
    assertThat(status(perform(write(delete(last), "\"2\"", null)))).isEqualTo(204);
    assertThat(status(perform(get(base).param("screenCode", "SCR-21")))).isEqualTo(422);
  }

  @Test
  void homeOverlayReordersHidesOptionalWidgetsAndChangesEtag() throws Exception {
    seedCurrentPack();
    MvcResult before = perform(get("/api/v1/ui/layouts/home"));
    assertThat(status(before)).as(before.getResponse().getContentAsString()).isEqualTo(200);
    assertResponseConforms(before, "/ui/layouts/home", PathItem.HttpMethod.GET);
    assertThat(widgets(body(before)))
        .containsExactly("vt.widget.myTasksToday", "vt.widget.krCheckinDue", "tin-don-vi");
    assertThat(body(before).at("/personalizationMeta/hasOverlay").asBoolean()).isFalse();
    assertThat(body(before).at("/personalizationMeta/mandatoryWidgetIds/0").asText())
        .isEqualTo("vt.widget.myTasksToday");

    String overlay =
        """
        {"value":{"pinnedWidgets":["tin-don-vi"],
                  "hiddenWidgets":["vt.widget.myTasksToday","vt.widget.krCheckinDue","vt.widget.daBo"],
                  "widgetOrder":[{"id":"vt.widget.myTasksToday","order":0}]}}
        """;
    MvcResult saved =
        perform(write(put("/api/v1/config/me/preferences/ui.home.layout"), null, overlay));
    assertThat(status(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(201);
    assertThat(
            status(
                perform(
                    get("/api/v1/ui/layouts/home")
                        .header("If-None-Match", before.getResponse().getHeader("ETag")))))
        .isEqualTo(200);
    MvcResult after = perform(get("/api/v1/ui/layouts/home"));
    assertResponseConforms(after, "/ui/layouts/home", PathItem.HttpMethod.GET);
    assertThat(after.getResponse().getHeader("ETag"))
        .isNotEqualTo(before.getResponse().getHeader("ETag"));
    // Widget bắt buộc không ẩn được; widget đã bỏ khỏi UI Pack bị bỏ qua.
    assertThat(widgets(body(after))).containsExactly("tin-don-vi", "vt.widget.myTasksToday");
    assertThat(body(after).at("/personalizationMeta/hasOverlay").asBoolean()).isTrue();
    assertThat(body(after).at("/personalizationMeta/overlayVersionNo").asInt()).isEqualTo(1);

    MvcResult display =
        perform(
            write(
                put("/api/v1/config/me/preferences/ui.display"),
                null,
                "{\"value\":{\"mode\":\"elderly\",\"reducedSteps\":true}}"));
    assertThat(status(display)).isEqualTo(201);
    assertThat(body(perform(get("/api/v1/ui/layouts/home"))).get("mode").asText())
        .isEqualTo("elderly");
    // Lớp phủ của chủ không ảnh hưởng người khác.
    assertThat(widgets(body(as(OTHER, get("/api/v1/ui/layouts/home"))))).hasSize(3);
  }

  private static List<String> widgets(JsonNode home) {
    List<String> out = new ArrayList<>();
    home.at("/layout/children")
        .forEach(c -> out.add(c.has("id") ? c.get("id").asText() : c.get("component").asText()));
    return out;
  }

  /** Gói hiện hành tối thiểu chèn thẳng bằng quyền quản trị (không qua luồng phát hành). */
  private void seedCurrentPack() throws Exception {
    PostgreSQLContainer<?> pg = MissionCoreTestDatabase.container();
    UUID pack = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    String layouts =
        """
        {"home":{"component":"vt.layout.grid","children":[
          {"component":"vt.widget.myTasksToday","mandatory":true},
          {"component":"vt.widget.krCheckinDue"},
          {"component":"vt.widget.periodMilestones","id":"tin-don-vi"}]}}
        """;
    try (Connection admin =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      admin.setAutoCommit(false);
      try (PreparedStatement p =
              admin.prepareStatement(
                  "INSERT INTO config.ui_pack (id, tenant_id, code, name, created_by, updated_by,"
                      + " current_version_id) VALUES (?, ?, 'mac-dinh', 'Gói mặc định', ?, ?, ?)");
          PreparedStatement v =
              admin.prepareStatement(
                  "INSERT INTO config.ui_pack_version (id, tenant_id, created_by, updated_by,"
                      + " ui_pack_id, version, tokens, layouts, component_registry_version,"
                      + " bundle_sha256, status, published_at)"
                      + " VALUES (?, ?, ?, ?, ?, '1.0.0', '{}'::jsonb, ?::jsonb, '2026.3',"
                      + " sha256('cnh-02'::bytea), 'DA_PHAT_HANH', now())")) {
        p.setObject(1, pack);
        p.setObject(2, tenant.value());
        p.setObject(3, OWNER);
        p.setObject(4, OWNER);
        p.setObject(5, version);
        p.executeUpdate();
        v.setObject(1, version);
        v.setObject(2, tenant.value());
        v.setObject(3, OWNER);
        v.setObject(4, OWNER);
        v.setObject(5, pack);
        v.setString(6, layouts);
        v.executeUpdate();
      }
      admin.commit();
    }
  }
}
