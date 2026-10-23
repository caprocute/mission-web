package vn.viettel.okrkpi.scoring.application.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.cel.application.CelCompiledProgram;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.scoring.application.port.DecisionTables;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.PolicyRuleSource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * Biên dịch một phiên bản Policy Pack (DV-07-02): kiểm kiểu tĩnh mọi biểu thức CEL theo khai báo
 * biến của manifest ({@code inputs}, {@code inputs_nam}, {@code ngu_canh}, {@code tham_so} và kết
 * quả các quy tắc, BR-15-01; cùng quy ước với MOD-05) rồi nạp các bảng DMN. Biểu thức sai kiểu làm
 * cả phiên bản bị từ chối với {@code POLICY-422-CEL-TYPE}.
 */
public class PolicyCompiler {

  static final String CEL_TYPE = "POLICY-422-CEL-TYPE";

  private final CelEngine cel;
  private final DecisionTables tables;

  public PolicyCompiler(CelEngine cel, DecisionTables tables) {
    this.cel = Objects.requireNonNull(cel, "cel");
    this.tables = Objects.requireNonNull(tables, "tables");
  }

  public CompiledPolicy compile(PolicyContent content) {
    Map<String, CelValueType> env = environment(content.manifest());
    content.rules().forEach(r -> env.put(r.ruleKey(), resultType(r.resultType())));
    List<CompiledPolicy.Rule> rules = new ArrayList<>();
    List<ProblemFieldError> errors = new ArrayList<>();
    content.rules().stream()
        .sorted(
            Comparator.comparingInt(PolicyRuleSource::evalOrder)
                .thenComparing(PolicyRuleSource::ruleKey))
        .forEach(
            r -> {
              try {
                CelCompiledProgram program =
                    cel.compile(r.expression(), env, resultType(r.resultType()));
                rules.add(
                    new CompiledPolicy.Rule(
                        r.ruleKey(),
                        r.evalOrder(),
                        program,
                        r.resultType(),
                        r.roundingDigits(),
                        r.roundingMode(),
                        r.legalRef()));
              } catch (CelCompilationException e) {
                String detail = e.issues().isEmpty() ? e.getMessage() : e.issues().get(0).message();
                errors.add(
                    new ProblemFieldError(
                        "rules." + r.ruleKey(),
                        "Biểu thức không hợp lệ: " + detail + " tại khối " + r.ruleKey(),
                        "MSG-15-01"));
              }
            });
    if (!errors.isEmpty()) {
      throw new BusinessException(
          CEL_TYPE,
          "Phiên bản quy tắc " + content.pinned().version() + " có biểu thức không hợp lệ",
          errors);
    }
    DecisionTables.Compiled decisions =
        tables.compile(content.pinned().contentHash(), content.tables());
    return new CompiledPolicy(content.pinned(), content.manifest(), rules, decisions, cel);
  }

  /** Biến được phép của gói theo manifest (TL-01 "Cấu trúc gói"). */
  static Map<String, CelValueType> environment(Map<String, Object> manifest) {
    Map<String, CelValueType> env = new HashMap<>();
    env.put("tham_so", CelValueType.MAP_STRING_DYN);
    for (String block : List.of("inputs", "inputs_nam", "ngu_canh")) {
      if (manifest.get(block) instanceof Map<?, ?> vars) {
        vars.forEach(
            (name, decl) ->
                env.put(
                    String.valueOf(name),
                    declaredType(decl instanceof Map<?, ?> d ? d.get("type") : decl)));
      }
    }
    for (String block : List.of("definitions", "definitions_nam")) {
      if (manifest.get(block) instanceof Map<?, ?> scopes) {
        for (Object scope : scopes.values()) {
          if (scope instanceof Map<?, ?> defs) {
            defs.forEach(
                (name, d) -> {
                  if (d instanceof Map<?, ?> def) {
                    env.put(
                        String.valueOf(name), resultType(String.valueOf(def.get("result_type"))));
                  }
                });
          }
        }
      }
    }
    return env;
  }

  static CelValueType declaredType(Object raw) {
    String t = String.valueOf(raw).toLowerCase(Locale.ROOT);
    if (t.startsWith("list")) {
      return CelValueType.LIST_DYN;
    }
    return switch (t) {
      case "double" -> CelValueType.DOUBLE;
      case "int" -> CelValueType.INT;
      case "bool" -> CelValueType.BOOL;
      case "string" -> CelValueType.STRING;
      default -> CelValueType.MAP_STRING_DYN;
    };
  }

  static CelValueType resultType(String resultType) {
    return switch (resultType == null ? "" : resultType) {
      case "BOOLEAN" -> CelValueType.BOOL;
      case "STRING" -> CelValueType.STRING;
      default -> CelValueType.DECIMAL;
    };
  }
}
