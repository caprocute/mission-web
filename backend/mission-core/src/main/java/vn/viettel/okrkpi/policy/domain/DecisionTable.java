package vn.viettel.okrkpi.policy.domain;

import java.util.Objects;
import java.util.UUID;

/** Bảng quyết định DMN của một phiên bản ({@code policy.decision_table}, QT-15 bước 4). */
public record DecisionTable(
    UUID id,
    UUID versionId,
    String tableKey,
    String dmnXml,
    String hitPolicy,
    byte[] dmnHash,
    String legalRef,
    String rulesJson,
    String explanationTemplatesJson) {

  public DecisionTable {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(tableKey, "tableKey");
    Objects.requireNonNull(dmnXml, "dmnXml");
    Objects.requireNonNull(hitPolicy, "hitPolicy");
    Objects.requireNonNull(dmnHash, "dmnHash");
    Objects.requireNonNull(legalRef, "legalRef");
    Objects.requireNonNull(rulesJson, "rulesJson");
    Objects.requireNonNull(explanationTemplatesJson, "explanationTemplatesJson");
    dmnHash = dmnHash.clone();
  }

  @Override
  public byte[] dmnHash() {
    return dmnHash.clone();
  }

  public DecisionTable copyTo(UUID newId, UUID newVersionId) {
    return new DecisionTable(
        newId,
        newVersionId,
        tableKey,
        dmnXml,
        hitPolicy,
        dmnHash,
        legalRef,
        rulesJson,
        explanationTemplatesJson);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof DecisionTable t
        && id.equals(t.id)
        && versionId.equals(t.versionId)
        && tableKey.equals(t.tableKey)
        && java.util.Arrays.equals(dmnHash, t.dmnHash);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, versionId, tableKey, java.util.Arrays.hashCode(dmnHash));
  }

  @Override
  public String toString() {
    return "DecisionTable[" + tableKey + "@" + versionId + "]";
  }
}
