package vn.viettel.okrkpi.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelExecutionException;
import vn.viettel.okrkpi.cel.domain.CelRounding;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;

/**
 * Thực thi bộ kiểm thử chuẩn {@code data/golden/cel/*.yaml} trên {@code cel-java} (NEN-041).
 *
 * <p>Cùng bộ tệp YAML này được chạy trên {@code cel-js} ở {@code @vt/form-engine} để chứng minh kết
 * quả khớp 100% giữa máy chủ và trình duyệt.
 */
class CelGoldenSuiteTest {

  @Test
  void goldenDirectoryShouldContainAtLeastFiftyCases() throws IOException {
    List<Path> yamlFiles = findGoldenYamlFiles();
    assertThat(yamlFiles).isNotEmpty();

    int totalCases = 0;
    Yaml yaml = new Yaml();
    for (Path file : yamlFiles) {
      try (InputStream in = Files.newInputStream(file)) {
        Map<String, Object> doc = yaml.load(in);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) doc.get("cases");
        totalCases += cases.size();
      }
    }
    assertThat(totalCases)
        .as("Golden CEL suite must contain at least 50 expressions")
        .isGreaterThanOrEqualTo(50);
  }

  @TestFactory
  Stream<DynamicTest> allGoldenYamlCasesShouldMatchExpectedOutcome() throws IOException {
    List<Path> yamlFiles = findGoldenYamlFiles();
    List<DynamicTest> dynamicTests = new ArrayList<>();
    Yaml yaml = new Yaml();

    for (Path file : yamlFiles) {
      final Map<String, Object> doc;
      try (InputStream in = Files.newInputStream(file)) {
        doc = yaml.load(in);
      }
      WorkingCalendar calendar = parseCalendar(doc);
      CelEngine engine = new CelEngine(calendar);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> cases = (List<Map<String, Object>>) doc.get("cases");
      for (Map<String, Object> testCase : cases) {
        String id = (String) testCase.get("id");
        String description = (String) testCase.get("description");
        dynamicTests.add(
            DynamicTest.dynamicTest(
                id + ": " + description, () -> executeGoldenCase(engine, testCase)));
      }
    }
    return dynamicTests.stream();
  }

  @SuppressWarnings("unchecked")
  private static void executeGoldenCase(CelEngine engine, Map<String, Object> testCase) {
    String id = (String) testCase.get("id");
    String expression = (String) testCase.get("expression");
    CelValueType returnType = CelValueType.fromName((String) testCase.get("returnType"));

    Map<String, String> rawVarTypes =
        (Map<String, String>) testCase.getOrDefault("variableTypes", Map.of());
    Map<String, CelValueType> variableTypes = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : rawVarTypes.entrySet()) {
      variableTypes.put(entry.getKey(), CelValueType.fromName(entry.getValue()));
    }

    Map<String, Object> inputs = (Map<String, Object>) testCase.getOrDefault("inputs", Map.of());
    String expectedError = (String) testCase.get("expectedError");

    if (expectedError != null) {
      try {
        engine.evaluate(expression, variableTypes, returnType, inputs);
        throw new AssertionError(
            "Case " + id + " expected error " + expectedError + " but succeeded");
      } catch (CelCompilationException ex) {
        assertThat(ex.errorCode())
            .as("Case %s compilation error code", id)
            .isEqualTo(expectedError);
        assertThat(ex.issues()).as("Case %s issue details", id).isNotEmpty();
      } catch (CelExecutionException ex) {
        assertThat(ex.errorCode()).as("Case %s execution error code", id).isEqualTo(expectedError);
      }
      return;
    }

    Object actual = engine.evaluate(expression, variableTypes, returnType, inputs);
    Map<String, Object> rounding = (Map<String, Object>) testCase.get("rounding");
    if (rounding != null && actual instanceof Number num) {
      int scale = ((Number) rounding.getOrDefault("scale", 2)).intValue();
      String mode = (String) rounding.getOrDefault("mode", "NUA_LEN");
      RoundingMode roundingMode = CelRounding.parseRoundingMode(mode);
      actual = CelRounding.round(num, scale, roundingMode);
    }

    Object expected = testCase.get("expected");
    if (expected instanceof Number expectedNum) {
      assertThat(actual).as("Case %s numeric result", id).isInstanceOf(Number.class);
      if (returnType == CelValueType.INT) {
        assertThat(((Number) actual).longValue())
            .as("Case %s int result", id)
            .isEqualTo(expectedNum.longValue());
      } else {
        assertThat(((Number) actual).doubleValue())
            .as("Case %s double result", id)
            .isCloseTo(expectedNum.doubleValue(), within(1e-9));
      }
    } else {
      assertThat(actual).as("Case %s result", id).isEqualTo(expected);
    }
  }

  @SuppressWarnings("unchecked")
  private static WorkingCalendar parseCalendar(Map<String, Object> doc) {
    Map<String, Object> calMap = (Map<String, Object>) doc.get("calendar");
    if (calMap == null) {
      return StaticWorkingCalendar.weekendsOnly();
    }
    List<String> holidaysList = (List<String>) calMap.getOrDefault("holidays", List.of());
    List<String> compList =
        (List<String>) calMap.getOrDefault("compensatoryWorkingDays", List.of());
    Set<LocalDate> holidays =
        holidaysList.stream().map(LocalDate::parse).collect(Collectors.toSet());
    Set<LocalDate> compDays = compList.stream().map(LocalDate::parse).collect(Collectors.toSet());
    return StaticWorkingCalendar.of(holidays, compDays);
  }

  private static List<Path> findGoldenYamlFiles() throws IOException {
    List<Path> candidates =
        List.of(
            Path.of("data/golden/cel"),
            Path.of("../data/golden/cel"),
            Path.of("../../data/golden/cel"),
            Path.of("../../../data/golden/cel"));
    for (Path dir : candidates) {
      if (Files.isDirectory(dir)) {
        try (Stream<Path> stream = Files.list(dir)) {
          return stream
              .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
              .sorted()
              .toList();
        }
      }
    }
    throw new IllegalStateException("Cannot locate data/golden/cel directory");
  }
}
