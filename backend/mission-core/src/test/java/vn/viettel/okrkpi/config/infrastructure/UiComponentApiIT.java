package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.application.ComponentRegistryService;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-09 trên PostgreSQL thật: seed registry 2026.3, {@code listUiComponents}
 * khớp {@code config.yaml}, phân trang con trỏ, nạp manifest ngoài ngữ cảnh tenant không ghi đè.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class UiComponentApiIT extends ContractConformanceTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private ComponentRegistryService registry;

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

  @Test
  void listsSeededRegistryFollowingContract() throws Exception {
    MvcResult all = perform(get("/api/v1/ui/components"));
    assertThat(all.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(all, "/ui/components", PathItem.HttpMethod.GET);
    JsonNode items = body(all).get("items");
    assertThat(items.findValuesAsText("componentKey"))
        .containsExactly(
            "vt.layout.grid",
            "vt.widget.announcement",
            "vt.widget.myTasksToday",
            "vt.widget.okrProgress",
            "vt.widget.pendingApprovals");
    assertThat(items.get(0).get("container").asBoolean()).isTrue();
    assertThat(items.get(0).get("registryVersion").asText()).isEqualTo("2026.3");
    assertThat(items.get(2).get("mobile").asBoolean()).isTrue();
    assertThat(items.get(2).at("/propsSchema/additionalProperties").asBoolean(true)).isFalse();

    MvcResult first = perform(get("/api/v1/ui/components").param("limit", "2"));
    String cursor = body(first).get("nextCursor").asText();
    MvcResult second =
        perform(get("/api/v1/ui/components").param("limit", "2").param("cursor", cursor));
    assertResponseConforms(second, "/ui/components", PathItem.HttpMethod.GET);
    assertThat(body(second).get("items").findValuesAsText("componentKey"))
        .containsExactly("vt.widget.myTasksToday", "vt.widget.okrProgress");

    MvcResult filtered =
        perform(get("/api/v1/ui/components").param("registry", "2026.3").param("status", "NGUNG"));
    assertResponseConforms(filtered, "/ui/components", PathItem.HttpMethod.GET);
    assertThat(body(filtered).get("items")).isEmpty();
    assertThat(
            perform(get("/api/v1/ui/components").param("registry", "2099.1"))
                .getResponse()
                .getStatus())
        .isEqualTo(422);

    // Lệnh nạp chạy lúc khởi động, không có tenant: phiên bản cũ không ghi đè, phiên bản mới nạp
    // được.
    String manifest =
        """
        {"registryVersion":"0.0.1","components":[{"name":"vt.widget.thuNghiem","since":"0.0.1",
        "propsSchema":{"type":"object","additionalProperties":false,"properties":{}}}]}
        """;
    ComponentRegistryService.LoadResult loaded = registry.load(manifest);
    assertThat(registry.load(manifest).loaded()).isFalse();
    assertThat(registry.latestVersion()).contains("2026.3");
    assertThat(body(perform(get("/api/v1/ui/components").param("registry", "0.0.1"))).get("items"))
        .hasSize(1);
    assertThat(loaded.registryVersion()).isEqualTo("0.0.1");
  }
}
