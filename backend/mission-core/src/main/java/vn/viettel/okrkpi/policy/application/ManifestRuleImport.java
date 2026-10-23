package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Dựng dòng {@code policy.policy_rule} từ {@code definitions} và {@code definitions_nam} của
 * manifest khi nhập gói (CORE-05-02, bổ sung 23/10/2026), để thẻ Công thức của SCR-1501 có quy tắc
 * để sửa. Mỗi định nghĩa thành một quy tắc cùng khoá, biểu thức, kiểu, làm tròn ({@code {}} khi
 * định nghĩa không làm tròn), thứ tự và cờ {@code overridable}; {@code params} là các tham số mà
 * biểu thức đọc trực tiếp qua {@code tham_so.<khoá>}, lấy giá trị {@code mac_dinh} của manifest.
 * Quy tắc cùng khoá ghi đè định nghĩa khi tính (PolicyEvaluator), nên kết quả không đổi so với chỉ
 * dùng manifest.
 */
final class ManifestRuleImport {

  private static final Pattern PARAM =
      Pattern.compile("tham_so\\.([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)");
  private static final int LEGAL_REF_MAX = 200;

  private ManifestRuleImport() {}

  static List<PolicyRule> rules(UUID versionId, Map<String, ?> manifest, ObjectMapper json) {
    Map<?, ?> params = manifest.get("params") instanceof Map<?, ?> p ? p : Map.of();
    List<PolicyRule> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String block : List.of("definitions", "definitions_nam")) {
      if (!(manifest.get(block) instanceof Map<?, ?> scopes)) {
        continue;
      }
      for (Object defs : scopes.values()) {
        if (!(defs instanceof Map<?, ?> m)) {
          continue;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
          String key = String.valueOf(e.getKey());
          if (!(e.getValue() instanceof Map<?, ?> d) || !seen.add(key)) {
            continue;
          }
          String expression = String.valueOf(d.get("expression")).strip();
          String legal = d.get("legal_ref") == null ? "" : String.valueOf(d.get("legal_ref"));
          out.add(
              new PolicyRule(
                  Ids.newUuidV7(),
                  versionId,
                  key,
                  expression,
                  String.valueOf(d.get("result_type")),
                  write(json, d.get("rounding") instanceof Map<?, ?> r ? r : Map.of()),
                  legal.length() > LEGAL_REF_MAX ? legal.substring(0, LEGAL_REF_MAX) : legal,
                  d.get("eval_order") instanceof Number n ? n.intValue() : 0,
                  write(json, referenced(expression, params)),
                  Boolean.TRUE.equals(d.get("overridable"))));
        }
      }
    }
    return out;
  }

  /** Tham số biểu thức đọc trực tiếp: khoá dài nhất của {@code params} là tiền tố của đường dẫn. */
  private static Map<String, Object> referenced(String expression, Map<?, ?> params) {
    Map<String, Object> used = new LinkedHashMap<>();
    Matcher m = PARAM.matcher(expression);
    while (m.find()) {
      String path = m.group(1);
      while (!path.isEmpty()) {
        if (params.get(path) instanceof Map<?, ?> decl) {
          used.putIfAbsent(path, decl.get("mac_dinh"));
          break;
        }
        int dot = path.lastIndexOf('.');
        path = dot < 0 ? "" : path.substring(0, dot);
      }
    }
    return used;
  }

  private static String write(ObjectMapper json, Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
