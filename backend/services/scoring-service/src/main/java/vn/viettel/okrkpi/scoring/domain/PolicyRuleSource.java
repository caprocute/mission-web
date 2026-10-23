package vn.viettel.okrkpi.scoring.domain;

import java.util.Objects;

/**
 * Một quy tắc CEL của phiên bản Policy Pack ({@code PolicyRule} của {@code listPolicyRules}).
 *
 * @param resultType {@code DECIMAL}, {@code BOOLEAN} hoặc {@code STRING}
 * @param roundingDigits số chữ số làm tròn, {@code null} khi quy tắc không làm tròn
 * @param roundingMode cách làm tròn ({@code NUA_LEN} mặc định)
 */
public record PolicyRuleSource(
    String ruleKey,
    String expression,
    String resultType,
    int evalOrder,
    Integer roundingDigits,
    String roundingMode,
    String legalRef) {

  public PolicyRuleSource {
    Objects.requireNonNull(ruleKey, "ruleKey");
    Objects.requireNonNull(expression, "expression");
    resultType = resultType == null ? "DECIMAL" : resultType;
    roundingMode = roundingMode == null ? "NUA_LEN" : roundingMode;
  }
}
