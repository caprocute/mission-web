package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Seed registry 2026.3 khớp registry phía web (CORE-08-09): cùng tên component, cùng {@code since},
 * cùng cờ {@code container}. Bỏ qua khi không có mã web cạnh repo backend.
 */
class ComponentRegistrySeedTest {

  private static final Path WEB = Path.of("../../frontend/projects");
  private static final Pattern ENTRY =
      Pattern.compile(
          "name:\\s*'(vt\\.[^']+)'(?:,\\s*container:\\s*(true|false))?[\\s\\S]*?since:\\s*'([^']+)'");

  @Test
  void seedMatchesWebRegistry() throws Exception {
    Path builtin = WEB.resolve("sdui-renderer/src/lib/builtin-components.ts");
    Path shell = WEB.resolve("fe-shell/src/app/widgets/shell-widgets.ts");
    assumeTrue(Files.exists(builtin) && Files.exists(shell), "không có mã web");
    Map<String, String> web = new TreeMap<>();
    for (Path p : new Path[] {builtin, shell}) {
      Matcher m = ENTRY.matcher(Files.readString(p, StandardCharsets.UTF_8));
      while (m.find()) {
        web.put(m.group(1), "true".equals(m.group(2)) + "@" + m.group(3));
      }
    }
    Map<String, String> seed = new TreeMap<>();
    JsonNode manifest = new ObjectMapper().readTree(ComponentRegistryServiceTest.seed());
    for (JsonNode c : manifest.path("components")) {
      seed.put(
          c.path("name").asText(),
          c.path("container").asBoolean(false) + "@" + c.path("since").asText());
    }
    assertThat(seed).isEqualTo(web);
    String registry =
        Files.readString(
            WEB.resolve("sdui-renderer/src/lib/component-registry.ts"), StandardCharsets.UTF_8);
    assertThat(registry)
        .contains("REGISTRY_VERSION = '" + manifest.path("webRegistryVersion").asText() + "'");
  }
}
