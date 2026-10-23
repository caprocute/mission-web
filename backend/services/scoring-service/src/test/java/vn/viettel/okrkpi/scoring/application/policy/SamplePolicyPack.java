package vn.viettel.okrkpi.scoring.application.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.scoring.domain.DecisionTableSource;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.PolicyRuleSource;

/**
 * Gói mẫu {@code data/policy-pack/khoi-dang/quy-dinh-moi} đọc như MOD-05 cấp qua API: manifest là
 * toàn bộ {@code policy.yaml}, quy tắc là các định nghĩa {@code definitions} và {@code
 * definitions_nam}, bảng là các tệp DMN của {@code classification.bang_quyet_dinh}. {@code
 * overrides} thay biểu thức theo {@code rule_key} để giả lập phiên bản đổi công thức; băm nội dung
 * là SHA-256 của tệp gói cộng phần thay.
 */
public final class SamplePolicyPack {

  public static final Path DIR = Path.of("../../../data/policy-pack/khoi-dang/quy-dinh-moi");

  private SamplePolicyPack() {}

  /** Gói luồng Nhà nước {@code khoi-chinh-quyen/nd335}. */
  public static final Path ND335 = Path.of("../../../data/policy-pack/khoi-chinh-quyen/nd335");

  public static PolicyContent content(
      UUID packId, UUID versionId, String version, Map<String, String> overrides) {
    return content(DIR, "khoi-dang/quy-dinh-moi", packId, versionId, version, overrides);
  }

  /** Gói luồng Nhà nước, phiên bản 1.4.0. */
  public static PolicyContent nd335() {
    return content(
        ND335,
        "khoi-chinh-quyen/nd335",
        UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4a02"),
        UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4b21"),
        "1.4.0",
        Map.of());
  }

  static PolicyContent content(
      Path dir,
      String code,
      UUID packId,
      UUID versionId,
      String version,
      Map<String, String> overrides) {
    String yaml = read(dir.resolve("policy.yaml"));
    Map<String, Object> manifest = new Yaml(new LoaderOptions()).load(yaml);
    List<PolicyRuleSource> rules = new ArrayList<>();
    for (String block : List.of("definitions", "definitions_nam")) {
      for (Object scope : map(manifest.get(block)).values()) {
        for (Map.Entry<String, Object> e : map(scope).entrySet()) {
          Map<String, Object> d = map(e.getValue());
          Map<String, Object> rounding = map(d.get("rounding"));
          rules.add(
              new PolicyRuleSource(
                  e.getKey(),
                  overrides.getOrDefault(e.getKey(), (String) d.get("expression")),
                  (String) d.get("result_type"),
                  ((Number) d.get("eval_order")).intValue(),
                  (Integer) rounding.get("digits"),
                  (String) rounding.get("mode"),
                  (String) d.get("legal_ref")));
        }
      }
    }
    List<DecisionTableSource> tables = new ArrayList<>();
    for (Object t : (List<?>) map(manifest.get("classification")).get("bang_quyet_dinh")) {
      Map<String, Object> m = map(t);
      tables.add(
          new DecisionTableSource(
              (String) m.get("table_key"), read(dir.resolve((String) m.get("dmn")))));
    }
    String hash = sha256(yaml + overrides);
    return new PolicyContent(
        new PinnedPolicy(packId, code, versionId, version, hash, false), manifest, rules, tables);
  }

  public static PolicyContent content() {
    return content(
        UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4a01"),
        UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4b20"),
        "2.1.0",
        Map.of());
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  static String read(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
