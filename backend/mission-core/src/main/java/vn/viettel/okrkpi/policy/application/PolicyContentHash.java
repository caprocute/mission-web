package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.policy.domain.CanonicalJson;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.PolicyRule;

/**
 * {@code content_hash} của phiên bản Policy Pack (CORE-05-09, TL-01 "Phát hành, ký số và vòng
 * đời"): SHA-256 trên dạng chuẩn hoá RFC 8785 của manifest (gồm tham số và tham số luồng duyệt
 * {@code workflow_bindings}), toàn bộ quy tắc sắp theo {@code rule_key} và bảng quyết định sắp theo
 * {@code table_key}. Thứ tự khoá JSON và thứ tự dòng đọc từ CSDL không làm đổi giá trị.
 */
public final class PolicyContentHash {

  private PolicyContentHash() {}

  /** Mã hex chữ thường của SHA-256. */
  public static String of(
      ObjectMapper json, String manifestJson, List<PolicyRule> rules, List<DecisionTable> tables) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("manifest", parse(json, manifestJson));
    List<Map<String, Object>> ruleList = new ArrayList<>();
    rules.stream()
        .sorted(Comparator.comparing(PolicyRule::ruleKey))
        .forEach(
            r -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("key", r.ruleKey());
              m.put("expression", r.expression());
              m.put("result_type", r.resultType());
              m.put("rounding", parse(json, r.roundingJson()));
              m.put("legal_ref", r.legalRef());
              m.put("eval_order", r.evalOrder());
              m.put("params", parse(json, r.paramsJson()));
              m.put("overridable", r.overridable());
              ruleList.add(m);
            });
    content.put("rules", ruleList);
    List<Map<String, Object>> tableList = new ArrayList<>();
    tables.stream()
        .sorted(Comparator.comparing(DecisionTable::tableKey))
        .forEach(
            t -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("key", t.tableKey());
              m.put("hit_policy", t.hitPolicy());
              m.put("dmn_sha256", HexFormat.of().formatHex(t.dmnHash()));
              m.put("legal_ref", t.legalRef());
              m.put("rules", parse(json, t.rulesJson()));
              m.put("explanation_templates", parse(json, t.explanationTemplatesJson()));
              tableList.add(m);
            });
    content.put("decision_tables", tableList);
    return sha256(CanonicalJson.write(content));
  }

  static String sha256(String canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Thiếu SHA-256", e);
    }
  }

  private static Object parse(ObjectMapper json, String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return json.readValue(text, Object.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Nội dung JSON của phiên bản không đọc được", e);
    }
  }
}
