package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.config.api.FormValidationApi.Violation;

/**
 * Bộ ca dùng chung {@code data/golden/form-tests/*.yaml} chạy trên cel-java (CORE-08-14, BR-18-05);
 * {@code @vt/form-engine} chạy cùng tệp bằng cel-js.
 */
class FormGoldenSuiteTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  static Path dir() {
    for (String p :
        List.of(
            "data/golden/form-tests",
            "../data/golden/form-tests",
            "../../data/golden/form-tests")) {
      if (Files.isDirectory(Path.of(p))) {
        return Path.of(p);
      }
    }
    throw new IllegalStateException("Không thấy data/golden/form-tests");
  }

  @TestFactory
  Stream<DynamicTest> goldenCases() throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.list(dir())) {
      files = s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
    }
    assertThat(files).isNotEmpty();
    return files.stream().flatMap(FormGoldenSuiteTest::cases);
  }

  @SuppressWarnings("unchecked")
  private static Stream<DynamicTest> cases(Path file) {
    Map<String, Object> doc;
    try (Reader r = Files.newBufferedReader(file)) {
      doc = new Yaml().load(r);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    CompiledForm form =
        new CompiledForm(
            new CelEngine(StaticWorkingCalendar.weekendsOnly(), FormSchemaValidator.LIMITS),
            JSON.valueToTree(doc.get("schema")),
            JSON.valueToTree(doc.getOrDefault("celRules", List.of())));
    assertThat(form.c2Fields())
        .containsExactlyInAnyOrderElementsOf((List<String>) doc.getOrDefault("c2", List.of()));
    return ((List<Map<String, Object>>) doc.get("cases"))
        .stream()
            .map(
                c ->
                    DynamicTest.dynamicTest(
                        file.getFileName() + " " + c.get("id"),
                        () -> {
                          JsonNode input = JSON.valueToTree(c.get("input"));
                          List<Violation> errors = form.check(input);
                          Set<String> fields =
                              errors.stream().map(Violation::field).collect(Collectors.toSet());
                          assertThat(errors.isEmpty())
                              .as(errors.toString())
                              .isEqualTo(c.get("valid"));
                          assertThat(fields)
                              .containsExactlyInAnyOrderElementsOf(
                                  (List<String>) c.getOrDefault("errors", List.of()));
                        }));
  }
}
