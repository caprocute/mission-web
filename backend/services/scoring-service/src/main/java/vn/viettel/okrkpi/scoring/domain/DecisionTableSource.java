package vn.viettel.okrkpi.scoring.domain;

import java.util.Objects;

/** Bảng quyết định của phiên bản Policy Pack dạng DMN XML ({@code exportDecisionTableDmn}). */
public record DecisionTableSource(String tableKey, String dmnXml) {

  public DecisionTableSource {
    Objects.requireNonNull(tableKey, "tableKey");
    Objects.requireNonNull(dmnXml, "dmnXml");
  }
}
