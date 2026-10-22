package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.domain.UiComponent;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nạp manifest, danh mục theo phiên bản, widget plugin (CORE-08-09). */
class ComponentRegistryServiceTest {

  static final Path SEED = Path.of("../../data/seed/component-registry/2026.3.json");
  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

  static String seed() throws IOException {
    return Files.readString(SEED, StandardCharsets.UTF_8);
  }

  private final InMemoryComponentRegistry repo = new InMemoryComponentRegistry();
  private final ComponentRegistryService service =
      new ComponentRegistryService(
          repo,
          (tenant, version) ->
              List.of(
                  new UiComponent(
                      "vt.widget.ytePhongKham",
                      version,
                      "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}",
                      "C1",
                      UiComponent.Status.HIEU_LUC,
                      false,
                      null,
                      false,
                      "fe-plugin-yte")),
          new ObjectMapper());

  @Test
  void loadsOnceAndListsLatestWithPlugins() throws IOException {
    assertThat(service.list(TENANT, null, null, null, 10)).isEmpty();
    ComponentRegistryService.LoadResult first = service.load(seed());
    assertThat(first.loaded()).isTrue();
    assertThat(first.components()).isEqualTo(5);
    // Phiên bản đã nạp không bị ghi đè.
    assertThat(service.load(seed().replace("\"C1\"", "\"C2\"")).loaded()).isFalse();
    assertThat(repo.rows).hasSize(5).allMatch(c -> c.allowedClassification().equals("C1"));
    service.load(
        """
        {"registryVersion":"2026.1","components":[{"name":"vt.widget.old",
        "propsSchema":{"type":"object","additionalProperties":false,"properties":{}}}]}
        """);
    assertThat(service.latestVersion()).contains("2026.3");
    List<UiComponent> page = service.list(TENANT, null, null, null, 3);
    assertThat(page)
        .extracting(UiComponent::componentKey)
        .containsExactly("vt.layout.grid", "vt.widget.announcement", "vt.widget.myTasksToday");
    List<UiComponent> rest =
        service.list(TENANT, "2026.3", UiComponent.Status.HIEU_LUC, "vt.widget.myTasksToday", 10);
    assertThat(rest)
        .extracting(UiComponent::componentKey)
        .containsExactly(
            "vt.widget.okrProgress", "vt.widget.pendingApprovals", "vt.widget.ytePhongKham");
    assertThat(rest.get(2).plugin()).isEqualTo("fe-plugin-yte");
    assertThat(service.list(TENANT, "2026.3", UiComponent.Status.NGUNG, null, 10)).isEmpty();
    assertThatThrownBy(() -> service.list(TENANT, "2099.1", null, null, 10))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("COMMON-422-VALIDATION");
    assertThat(service.resolve("2026.1")).contains("2026.1");
    assertThat(service.resolve(">=2026 <2027")).contains("2026.3");
    assertThat(service.resolve(">=2027")).isEmpty();
    assertThat(service.resolve(null)).isEmpty();
  }

  @Test
  void rejectsMalformedManifests() {
    String open =
        """
        {"registryVersion":"2026.4","components":[{"name":"vt.widget.a",
        "propsSchema":{"type":"object","properties":{}}}]}
        """;
    String unknownKeyword =
        """
        {"registryVersion":"2026.4","components":[{"name":"vt.widget.a",
        "propsSchema":{"type":"object","additionalProperties":false,"oneOf":[]}}]}
        """;
    String duplicate =
        """
        {"registryVersion":"2026.4","components":[
        {"name":"vt.widget.a","propsSchema":{"type":"object","additionalProperties":false}},
        {"name":"vt.widget.a","propsSchema":{"type":"object","additionalProperties":false}}]}
        """;
    String badName =
        """
        {"registryVersion":"2026.4","components":[{"name":"acme.a",
        "propsSchema":{"type":"object","additionalProperties":false}}]}
        """;
    for (String m :
        List.of(
            open, unknownKeyword, duplicate, badName, "{\"registryVersion\":\"2026.4\"}", "{")) {
      assertThatThrownBy(() -> service.load(m)).as(m).isInstanceOf(RuntimeException.class);
    }
    assertThat(repo.rows).isEmpty();
  }
}
