package vn.viettel.okrkpi.scoring.application.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.cel.application.CelCompiledProgram;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelRounding;
import vn.viettel.okrkpi.scoring.application.port.DecisionTables;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;

/**
 * Một phiên bản Policy Pack đã biên dịch (DV-07-02): chương trình CEL của từng quy tắc theo {@code
 * eval_order} và các bảng DMN đã nạp. Bất biến, dùng chung cho mọi hồ sơ của các kỳ ghim cùng băm
 * nội dung; mỗi lần tính ghi {@link #contentHash()} vào {@code score_result}.
 */
public final class CompiledPolicy {

  /** Quy tắc đã biên dịch; {@code digits} {@code null} khi không làm tròn. */
  public record Rule(
      String key,
      int evalOrder,
      CelCompiledProgram program,
      String resultType,
      Integer digits,
      String mode,
      String legalRef) {}

  private final PinnedPolicy pinned;
  private final Map<String, Object> manifest;
  private final Map<String, Object> params;
  private final Map<String, Rule> rules;
  private final DecisionTables.Compiled decisions;
  private final CelEngine cel;

  @SuppressWarnings("unchecked")
  CompiledPolicy(
      PinnedPolicy pinned,
      Map<String, Object> manifest,
      List<Rule> rules,
      DecisionTables.Compiled decisions,
      CelEngine cel) {
    this.pinned = Objects.requireNonNull(pinned, "pinned");
    this.manifest = Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
    this.params =
        manifest.get("params") instanceof Map<?, ?> p
            ? Collections.unmodifiableMap((Map<String, Object>) p)
            : Map.of();
    Map<String, Rule> byKey = new LinkedHashMap<>();
    rules.forEach(r -> byKey.put(r.key(), r));
    this.rules = Collections.unmodifiableMap(byKey);
    this.decisions = Objects.requireNonNull(decisions, "decisions");
    this.cel = Objects.requireNonNull(cel, "cel");
  }

  public PinnedPolicy pinned() {
    return pinned;
  }

  /** Băm nội dung (hex) ghi vào {@code score_result.policy_content_hash}. */
  public String contentHash() {
    return pinned.contentHash();
  }

  public Map<String, Object> manifest() {
    return manifest;
  }

  /** Tham số {@code params} của manifest, nạp vào biến CEL {@code tham_so}. */
  public Map<String, Object> params() {
    return params;
  }

  /** Quy tắc theo thứ tự đánh giá. */
  public Map<String, Rule> rules() {
    return rules;
  }

  public DecisionTables.Compiled decisions() {
    return decisions;
  }

  /**
   * Đánh giá quy tắc {@code key} trên biến ngữ cảnh; kết quả số được làm tròn theo khai báo của quy
   * tắc (khử sai số dấu phẩy động ở 10 chữ số trước, như MOD-05).
   */
  public Object evaluate(String key, Map<String, Object> variables) {
    Rule rule = rules.get(key);
    if (rule == null) {
      throw new IllegalArgumentException("Phiên bản không có quy tắc " + key);
    }
    Object value = cel.evaluate(rule.program(), variables);
    if (rule.digits() != null && value instanceof Number n) {
      return CelRounding.round(CelRounding.round(n, 10, "NUA_LEN"), rule.digits(), rule.mode());
    }
    return value;
  }

  /** Tra bảng quyết định đã nạp. */
  public Map<String, Object> decide(String tableKey, Map<String, Object> variables) {
    return decisions.evaluate(tableKey, variables);
  }
}
