package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.DashboardDefinitionApi;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-17 trên PostgreSQL thật: analytics-api tạo, sửa, chia sẻ định nghĩa
 * dashboard qua {@code /config/dashboard-defs*}; mỗi quy tắc trường của SCR-1602 trả 422 có {@code
 * errors[]}; trùng tên 409; {@code If-Match} lệch 409, thiếu 428; người khác người tạo sửa 403;
 * chia sẻ {@code DON_VI} chỉ hiện với người xem thuộc đơn vị được chia sẻ; hai tenant không thấy
 * dashboard của nhau. Sổ cái thay bằng mock.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class DashboardDefinitionApiIT extends ContractConformanceTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  private static final String BASE = "/api/v1/config/dashboard-defs";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private DashboardDefinitionApi api;

  /** Thay sổ cái (bean chính) để kiểm sự kiện kiểm toán khi đổi phạm vi chia sẻ. */
  @MockitoBean private ConfigAudit audit;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID owner = UUID.randomUUID();
  private final UUID colleague = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn();
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private MockHttpServletRequestBuilder create(TenantId t, UUID actor, String content) {
    return post(BASE)
        .param("tenant", t.value().toString())
        .header("X-Acting-User", actor.toString())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(content);
  }

  private MockHttpServletRequestBuilder replace(
      UUID id, UUID actor, String ifMatch, String content) {
    MockHttpServletRequestBuilder b =
        put(BASE + "/" + id)
            .param("tenant", tenant.value().toString())
            .header("X-Acting-User", actor.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content);
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  private MockHttpServletRequestBuilder list(TenantId t, UUID actor, UUID... units) {
    MockHttpServletRequestBuilder b =
        get(BASE).param("tenant", t.value().toString()).header("X-Acting-User", actor.toString());
    for (UUID u : units) {
      b = b.param("viewerUnit", u.toString());
    }
    return b;
  }

  private static String widget(String id, String measure, String dims) {
    return """
        {"id":"%s","x":0,"y":0,"w":3,"h":2,"measure":"%s","dimensions":%s,"chartType":"COT",
         "filters":[{"member":"don_vi.cap","operator":"in","values":["TINH","HUYEN"]}],"drillDown":true}
        """
        .formatted(id, measure, dims);
  }

  private static String def(String title, String scope, String units, String... widgets) {
    return """
        {"title":"%s","layout":[%s],"shareScope":"%s","sharedUnitIds":%s,
         "semanticModelVersion":"2026.4"}
        """
        .formatted(title, String.join(",", widgets), scope, units);
  }

  private List<String> titles(MvcResult r) throws Exception {
    List<String> out = new ArrayList<>();
    body(r).get("items").forEach(i -> out.add(i.get("title").asText()));
    return out;
  }

  @Test
  void createReplaceShareAndVisibility() throws Exception {
    MvcResult created =
        perform(
            create(
                tenant,
                owner,
                def(
                    "Bảng của Bí thư",
                    "RIENG",
                    "[]",
                    widget("w1", "okr.tien_do_o", "[\"don_vi.ten\"]"),
                    widget("w2", "kpi.diem_tong", "[]"))));
    assertThat(created.getResponse().getStatus())
        .as(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(201);
    assertResponseConforms(created, "/config/dashboard-defs", PathItem.HttpMethod.POST);
    JsonNode c = body(created);
    UUID id = UUID.fromString(c.get("id").asText());
    assertThat(created.getResponse().getHeader("Location")).endsWith("/dashboard-defs/" + id);
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(c.get("measures").toString()).isEqualTo("[\"okr.tien_do_o\",\"kpi.diem_tong\"]");
    assertThat(c.get("dimensions").toString()).isEqualTo("[\"don_vi.ten\"]");
    assertThat(c.get("layout").get(0).get("filters").get(0).get("values").toString())
        .isEqualTo("[\"TINH\",\"HUYEN\"]");
    assertThat(c.get("semanticModelVersion").asText()).isEqualTo("2026.4");
    verify(audit, never())
        .changed(any(), any(), eq("CHIA_SE_DASHBOARD"), any(), eq(id.toString()), any(), anyLong());

    // Đồng nghiệp chưa thấy dashboard riêng.
    assertThat(titles(perform(list(tenant, colleague, unit)))).doesNotContain("Bảng của Bí thư");
    MvcResult hidden =
        perform(
            get(BASE + "/" + id)
                .param("tenant", tenant.value().toString())
                .header("X-Acting-User", colleague.toString()));
    assertThat(hidden.getResponse().getStatus()).isEqualTo(404);

    String shared =
        def(
            "Bảng của Bí thư",
            "DON_VI",
            "[\"" + unit + "\"]",
            widget("w1", "okr.tien_do_o", "[\"don_vi.ten\"]"));
    assertThat(perform(replace(id, owner, null, shared)).getResponse().getStatus()).isEqualTo(428);
    MvcResult notOwner = perform(replace(id, colleague, "\"0\"", shared));
    assertThat(notOwner.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(notOwner).get("code").asText()).isEqualTo("CONFIG-403-NOT-OWNER");

    MvcResult saved = perform(replace(id, owner, "\"0\"", shared));
    assertThat(saved.getResponse().getStatus())
        .as(saved.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(200);
    assertResponseConforms(saved, "/config/dashboard-defs/{id}", PathItem.HttpMethod.PUT);
    assertThat(saved.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(body(saved).get("shareScope").asText()).isEqualTo("DON_VI");
    verify(audit)
        .changed(
            eq(tenant),
            eq(owner),
            eq("CHIA_SE_DASHBOARD"),
            eq("config.dashboard_def"),
            eq(id.toString()),
            eq(id),
            eq(1L));

    MvcResult stale = perform(replace(id, owner, "\"0\"", shared));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);

    // Người xem thuộc đơn vị được chia sẻ thấy; người xem đơn vị khác không thấy.
    MvcResult inUnit = perform(list(tenant, colleague, UUID.randomUUID(), unit));
    assertThat(inUnit.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(inUnit, "/config/dashboard-defs", PathItem.HttpMethod.GET);
    assertThat(titles(inUnit)).contains("Bảng của Bí thư");
    assertThat(body(inUnit).get("items").get(0).get("widgetCount").asInt()).isEqualTo(1);
    assertThat(titles(perform(list(tenant, colleague, UUID.randomUUID()))))
        .doesNotContain("Bảng của Bí thư");
    assertThat(titles(perform(list(tenant, colleague, unit).param("owner", "me")))).isEmpty();
    MvcResult got =
        perform(
            get(BASE + "/" + id)
                .param("tenant", tenant.value().toString())
                .param("viewerUnit", unit.toString())
                .header("X-Acting-User", colleague.toString()));
    assertThat(got.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(got, "/config/dashboard-defs/{id}", PathItem.HttpMethod.GET);
    assertThat(got.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(
            TenantContext.call(tenant, () -> api.find(tenant, colleague, List.of(unit), id))
                .map(DashboardDefinitionApi.DashboardDefinitionView::title))
        .contains("Bảng của Bí thư");

    // Tenant khác không thấy.
    TenantId other = TenantId.of(UUID.randomUUID());
    assertThat(titles(perform(list(other, owner, unit)))).isEmpty();
    MvcResult cross =
        perform(
            get(BASE + "/" + id)
                .param("tenant", other.value().toString())
                .header("X-Acting-User", owner.toString()));
    assertThat(cross.getResponse().getStatus()).isEqualTo(404);

    // Trùng tên trong không gian của cùng người tạo (không phân biệt hoa thường).
    MvcResult duplicate =
        perform(
            create(
                tenant, owner, def("bảng của bí thư", "RIENG", "[]", widget("a", "okr.x", "[]"))));
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(duplicate).get("code").asText()).isEqualTo("CONFIG-409-DUPLICATE-TITLE");
    assertThat(
            perform(
                    create(
                        tenant,
                        colleague,
                        def("Bảng của Bí thư", "TENANT", "[]", widget("a", "okr.x", "[]"))))
                .getResponse()
                .getStatus())
        .isEqualTo(201);
  }

  @Test
  void eachFieldRuleReturns422WithErrors() throws Exception {
    String ok = widget("w", "okr.tien_do_o", "[]");
    List<String> widgets = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      widgets.add(widget("w" + i, "okr.tien_do_o", "[]"));
    }
    assertField(def("", "RIENG", "[]", ok), "title");
    assertField(def("x".repeat(101), "RIENG", "[]", ok), "title");
    assertField(def("Quá nhiều", "RIENG", "[]", widgets.toArray(String[]::new)), "layout");
    assertField("{\"title\":\"Rỗng\",\"layout\":[]}", "layout");
    assertField(
        def("Ba chiều", "RIENG", "[]", widget("w", "okr.tien_do_o", "[\"a.b\",\"a.c\",\"a.d\"]")),
        "layout[0].dimensions");
    assertField(
        def("Chỉ số sai", "RIENG", "[]", widget("w", "Tiến độ; DROP", "[]")), "layout[0].measure");
    assertField(def("Thiếu đơn vị", "DON_VI", "[]", ok), "sharedUnitIds");
    assertField(def("Thừa đơn vị", "TENANT", "[\"" + unit + "\"]", ok), "sharedUnitIds");
    assertField(def("Phạm vi sai", "NHOM", "[]", ok), "shareScope");
  }

  private void assertField(String content, String field) throws Exception {
    MvcResult r = perform(create(tenant, owner, content));
    assertThat(r.getResponse().getStatus())
        .as(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(422);
    List<String> fields = new ArrayList<>();
    body(r).get("errors").forEach(e -> fields.add(e.get("field").asText()));
    assertThat(fields).as(content).contains(field);
  }
}
