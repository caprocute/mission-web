package vn.viettel.okrkpi.platform.tenanttest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * NEN-034 Lớp 4 – Kiểm chứng bộ luật Semgrep {@code backend/semgrep/tenant-context.yml}:
 *
 * <ul>
 *   <li>Đầy đủ 3 luật chặn {@code SET app.<biến>} không có {@code LOCAL}, {@code SET SESSION} và
 *       {@code set_config(..., false)} cho Java, Go, Python và SQL.
 *   <li>Mọi tệp mẫu sai trong {@code backend/semgrep/testdata/bad/} (Java, Go, Python, SQL) đều bị
 *       cả 3 luật bắt lỗi.
 *   <li>Mọi tệp mẫu chuẩn trong {@code backend/semgrep/testdata/good/} không bị báo dương tính giả.
 *   <li>Quét toàn bộ cây mã nguồn {@code backend/} (ngoài {@code testdata/bad}) đảm bảo không có vi
 *       phạm nào.
 * </ul>
 */
class SemgrepTenantRuleTest {

  private static Path backendRoot;
  private static Path semgrepConfig;
  private static List<CompiledSemgrepRule> rules;

  @BeforeAll
  static void loadRules() throws IOException {
    backendRoot = locateBackendRoot();
    semgrepConfig = backendRoot.resolve("semgrep/tenant-context.yml");
    assertThat(semgrepConfig).exists().isRegularFile();
    rules = parseRules(semgrepConfig);
  }

  @Test
  void cauHinhSemgrepDuBaLuatVaHoTroBonNgonNgu() {
    assertThat(rules)
        .extracting(CompiledSemgrepRule::id)
        .containsExactly(
            "mission-no-session-set-app-param",
            "mission-no-set-session",
            "mission-no-set-config-is-local-false");

    for (CompiledSemgrepRule rule : rules) {
      assertThat(rule.severity()).as(rule.id()).isEqualTo("ERROR");
      assertThat(rule.includeGlobs())
          .as("languages covered by " + rule.id())
          .containsExactlyInAnyOrder("*.java", "*.go", "*.py", "*.sql");
    }
  }

  @Test
  void mauViPhamBiBatTrenMoiNgonNguJavaGoPythonSql() throws IOException {
    Path badDir = backendRoot.resolve("semgrep/testdata/bad");
    List<String> badFiles =
        List.of(
            "BadTenantContext.java",
            "bad_tenant_context.go",
            "bad_tenant_context.py",
            "bad_tenant_context.sql");

    for (String fileName : badFiles) {
      Path file = badDir.resolve(fileName);
      assertThat(file).as(fileName).exists();
      Map<String, List<String>> hitsByRule = scanFile(file, false);
      assertThat(hitsByRule.keySet())
          .as("Các luật phải bắt được vi phạm trong " + fileName)
          .containsExactlyInAnyOrder(
              "mission-no-session-set-app-param",
              "mission-no-set-session",
              "mission-no-set-config-is-local-false");
    }
  }

  @Test
  void mauAnToanKhongBiBaoLoiGia() throws IOException {
    Path goodDir = backendRoot.resolve("semgrep/testdata/good");
    List<String> goodFiles =
        List.of(
            "GoodTenantContext.java",
            "good_tenant_context.go",
            "good_tenant_context.py",
            "good_tenant_context.sql");

    for (String fileName : goodFiles) {
      Path file = goodDir.resolve(fileName);
      assertThat(file).as(fileName).exists();
      Map<String, List<String>> hitsByRule = scanFile(file, false);
      assertThat(hitsByRule).as("Không được báo lỗi giả trên " + fileName).isEmpty();
    }
  }

  @Test
  void toanBoMaNguonBackendKhongViPhamLuatDatTenant() throws IOException {
    List<String> violations = new ArrayList<>();
    Path badDir = backendRoot.resolve("semgrep/testdata/bad").normalize();

    try (Stream<Path> walk = Files.walk(backendRoot)) {
      List<Path> candidates =
          walk.filter(Files::isRegularFile)
              .filter(p -> !p.normalize().startsWith(badDir))
              .filter(p -> !isBuildOutput(p))
              .filter(SemgrepTenantRuleTest::isSupportedExtension)
              .toList();

      assertThat(candidates).isNotEmpty();
      for (Path file : candidates) {
        Map<String, List<String>> hits = scanFile(file, true);
        if (!hits.isEmpty()) {
          Path rel = backendRoot.relativize(file);
          hits.forEach((ruleId, matches) -> violations.add(rel + " [" + ruleId + "]: " + matches));
        }
      }
    }

    assertThat(violations)
        .as("Mã nguồn backend/ không được chứa lệnh đặt ngữ cảnh tenant mức phiên")
        .isEmpty();
  }

  private static boolean isBuildOutput(Path path) {
    for (Path part : path) {
      String s = part.toString();
      if ("target".equals(s) || ".git".equals(s) || "node_modules".equals(s)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSupportedExtension(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".java")
        || name.endsWith(".go")
        || name.endsWith(".py")
        || name.endsWith(".sql");
  }

  private static Map<String, List<String>> scanFile(Path file, boolean respectExcludes)
      throws IOException {
    String content = Files.readString(file, StandardCharsets.UTF_8);
    Path relPath = backendRoot.relativize(file.normalize());
    Map<String, List<String>> hits = new LinkedHashMap<>();

    for (CompiledSemgrepRule rule : rules) {
      if (respectExcludes && rule.isExcluded(relPath)) {
        continue;
      }
      List<String> ruleMatches = rule.findMatches(content);
      if (!ruleMatches.isEmpty()) {
        hits.put(rule.id(), ruleMatches);
      }
    }
    return hits;
  }

  @SuppressWarnings("unchecked")
  private static List<CompiledSemgrepRule> parseRules(Path yamlFile) throws IOException {
    Yaml yaml = new Yaml();
    Map<String, Object> root = yaml.load(Files.readString(yamlFile, StandardCharsets.UTF_8));
    List<Map<String, Object>> rawRules = (List<Map<String, Object>>) root.get("rules");
    List<CompiledSemgrepRule> result = new ArrayList<>();

    for (Map<String, Object> raw : rawRules) {
      String id = (String) raw.get("id");
      String severity = (String) raw.get("severity");
      Map<String, Object> paths = (Map<String, Object>) raw.get("paths");
      List<String> includes =
          paths != null && paths.get("include") != null
              ? (List<String>) paths.get("include")
              : List.of();
      List<String> excludes =
          paths != null && paths.get("exclude") != null
              ? (List<String>) paths.get("exclude")
              : List.of();

      List<Pattern> positive = new ArrayList<>();
      List<Pattern> negative = new ArrayList<>();
      if (raw.containsKey("pattern-regex")) {
        positive.add(Pattern.compile((String) raw.get("pattern-regex")));
      }
      if (raw.containsKey("patterns")) {
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) raw.get("patterns");
        for (Map<String, Object> p : patterns) {
          if (p.containsKey("pattern-regex")) {
            positive.add(Pattern.compile((String) p.get("pattern-regex")));
          }
          if (p.containsKey("pattern-not-regex")) {
            negative.add(Pattern.compile((String) p.get("pattern-not-regex")));
          }
        }
      }
      result.add(
          new CompiledSemgrepRule(
              id, severity, new LinkedHashSet<>(includes), excludes, positive, negative));
    }
    return result;
  }

  private static Path locateBackendRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("semgrep/tenant-context.yml"))) {
        return current;
      }
      if (Files.exists(current.resolve("backend/semgrep/tenant-context.yml"))) {
        return current.resolve("backend");
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Không tìm thấy thư mục backend/ chứa semgrep/tenant-context.yml");
  }

  private record CompiledSemgrepRule(
      String id,
      String severity,
      Set<String> includeGlobs,
      List<String> excludeGlobs,
      List<Pattern> positivePatterns,
      List<Pattern> negativePatterns) {

    boolean isExcluded(Path relativePath) {
      for (String glob : excludeGlobs) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        if (matcher.matches(relativePath)
            || matcher.matches(relativePath.getFileName())
            || (glob.startsWith("**/")
                && FileSystems.getDefault()
                    .getPathMatcher("glob:" + glob.substring(3))
                    .matches(relativePath.getFileName()))) {
          return true;
        }
      }
      return false;
    }

    List<String> findMatches(String content) {
      List<String> matches = new ArrayList<>();
      for (Pattern pos : positivePatterns) {
        Matcher m = pos.matcher(content);
        while (m.find()) {
          String matched = m.group().trim();
          int lineStart = content.lastIndexOf('\n', m.start()) + 1;
          int lineEnd = content.indexOf('\n', m.end());
          if (lineEnd < 0) {
            lineEnd = content.length();
          }
          String fullLine = content.substring(lineStart, lineEnd);
          boolean excludedByNotRegex = false;
          for (Pattern neg : negativePatterns) {
            if (neg.matcher(fullLine).find() || neg.matcher(matched).find()) {
              excludedByNotRegex = true;
              break;
            }
          }
          if (!excludedByNotRegex) {
            matches.add(matched);
          }
        }
      }
      return matches;
    }
  }
}
