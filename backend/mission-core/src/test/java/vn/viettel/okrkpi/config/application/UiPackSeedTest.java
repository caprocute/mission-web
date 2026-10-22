package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * UI Pack mặc định của tenant mới ({@code data/seed/ui-pack/mac-dinh-*.json}, DL-06) qua được lược
 * đồ UI Pack 1.0 và mọi kiểm tra tĩnh mà {@code submitUiPackVersion} chạy (BR-18-01, BR-18-08,
 * BR-18-09). Tài liệu dựng như {@code UiPackService.document}. Bỏ qua khi không có thư mục seed.
 */
class UiPackSeedTest {

  private static final Path SEED = Path.of("../../data/seed/ui-pack");
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void defaultPackPassesSchemaAndStaticChecks() throws Exception {
    assumeTrue(Files.isDirectory(SEED), "không có thư mục seed");
    List<Path> files;
    try (Stream<Path> s = Files.list(SEED)) {
      files = s.filter(p -> p.getFileName().toString().matches("mac-dinh-.*\\.json")).toList();
    }
    assertThat(files).isNotEmpty();
    UiPackSchema schema = UiPackSchema.load(json);
    UiPackStaticChecks checks = new UiPackStaticChecks(json, (t, p) -> Optional.empty(), null);
    for (Path f : files) {
      JsonNode doc = document(json.readTree(f.toFile()));
      assertThat(schema.validate(doc)).as(f.toString()).isEmpty();
      List<UiPackChecker.Result> results = checks.check(TenantId.of(UUID.randomUUID()), doc);
      assertThat(results)
          .as(f.toString())
          .allSatisfy(r -> assertThat(r.passed()).as(r.message()).isTrue());
      assertThat(results)
          .extracting(UiPackChecker.Result::code)
          .contains(
              UiPackChecker.Code.CONTRAST, UiPackChecker.Code.ELDER_MODE, UiPackChecker.Code.SIZE);
    }
  }

  private JsonNode document(JsonNode seed) {
    ObjectNode doc = json.createObjectNode();
    doc.put("pack", "default/ui");
    doc.put("version", seed.path("version").asText());
    doc.putObject("requires").put("registry", seed.path("componentRegistryVersion").asText());
    JsonNode tokens = seed.path("tokens");
    doc.set("tokens", tokens);
    if (seed.path("elderMode").asBoolean()) {
      ObjectNode elderly = doc.putObject("modes").putObject("elderly");
      ObjectNode copy =
          ((ObjectNode) tokens.path("$extensions").path("modes").path("elderly")).deepCopy();
      ObjectNode flags =
          json.createObjectNode().put("reducedSteps", seed.path("fewSteps").asBoolean());
      JsonNode declared = copy.remove("flags");
      if (declared != null && declared.has("minTouchTarget")) {
        flags.set("minTouchTarget", declared.get("minTouchTarget"));
      }
      elderly.set("tokens", copy);
      elderly.set("flags", flags);
    }
    doc.set("layouts", seed.path("layouts"));
    doc.putArray("assets");
    return doc;
  }
}
