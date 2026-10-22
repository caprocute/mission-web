package vn.viettel.okrkpi.okr.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * CORE-01-13, BR-17-01: trợ lý AI (vai trò {@code ai_readonly}, AC-12) không gọi được thao tác ghi
 * nào của {@code okr.yaml}, và mọi cột của schema {@code okr} là C0 hoặc C1 nên API đọc OKR trả cho
 * {@code ai_readonly} không chứa trường C2, C3 (BR-17-07).
 */
class OkrAiReadonlyAccessTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Path backend() {
    Path p = Path.of("").toAbsolutePath();
    return Files.isDirectory(p.resolve("opa")) ? p : p.getParent();
  }

  @Test
  void aiReadonlyChiDocOkr() throws IOException {
    JsonNode data = JSON.readTree(backend().resolve("opa/okrkpi/generated/data.json").toFile());
    JsonNode methods =
        data.path("api").path("okr").path("roles").path("ai_readonly").path("methods");
    List<String> allowed = new ArrayList<>();
    methods.forEach(m -> allowed.add(m.asText()));
    assertThat(allowed).containsExactly("GET");
    assertThat(data.path("roles").path("ai_readonly").path("kind").asText()).isEqualTo("ai");

    JsonNode catalog;
    try (InputStream in =
        getClass().getResourceAsStream("/vn/viettel/okrkpi/shared/authz/operation-policies.json")) {
      catalog = JSON.readTree(in);
    }
    int writes = 0;
    for (JsonNode op : catalog.path("operations")) {
      if (!"okr.yaml".equals(op.path("file").asText())
          || "GET".equals(op.path("method").asText())) {
        continue;
      }
      writes++;
      List<String> roles = new ArrayList<>();
      op.path("roles").forEach(r -> roles.add(r.asText()));
      assertThat(roles).as(op.path("operationId").asText()).doesNotContain("ai_readonly");
    }
    assertThat(writes).isGreaterThan(10);
  }

  @Test
  void moiCotSchemaOkrKhongPhaiC2C3() throws IOException {
    Pattern cls = Pattern.compile("\"cls\": \"(C[0-9])\"");
    Path dir = backend().resolve("mission-core/src/main/resources/db/migration/okr");
    int columns = 0;
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : files.filter(x -> x.toString().endsWith(".sql")).toList()) {
        Matcher m = cls.matcher(Files.readString(f));
        while (m.find()) {
          columns++;
          assertThat(m.group(1)).as(f.getFileName().toString()).isIn("C0", "C1");
        }
      }
    }
    assertThat(columns).isGreaterThan(50);
  }
}
