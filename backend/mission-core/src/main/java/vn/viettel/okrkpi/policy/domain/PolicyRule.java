package vn.viettel.okrkpi.policy.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Quy tắc CEL của một phiên bản ({@code policy.policy_rule}); {@code overridable} là cờ "cho phép
 * tenant điều chỉnh" của BR-15-13.
 *
 * @param roundingJson quy tắc làm tròn dạng JSON
 * @param paramsJson tham số của quy tắc dạng JSON (QT-15 bước 3)
 */
public record PolicyRule(
    UUID id,
    UUID versionId,
    String ruleKey,
    String expression,
    String resultType,
    String roundingJson,
    String legalRef,
    int evalOrder,
    String paramsJson,
    boolean overridable) {

  public PolicyRule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(ruleKey, "ruleKey");
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(resultType, "resultType");
    Objects.requireNonNull(roundingJson, "roundingJson");
    Objects.requireNonNull(legalRef, "legalRef");
    Objects.requireNonNull(paramsJson, "paramsJson");
  }

  /** Bản sao sang phiên bản nháp mới, giữ nguyên biểu thức, tham số và cờ kế thừa. */
  public PolicyRule copyTo(UUID newId, UUID newVersionId) {
    return new PolicyRule(
        newId,
        newVersionId,
        ruleKey,
        expression,
        resultType,
        roundingJson,
        legalRef,
        evalOrder,
        paramsJson,
        overridable);
  }
}
