package vn.viettel.okrkpi.scoring.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Nội dung bất biến của một phiên bản Policy Pack: manifest (khai báo kiểu biến, tham số), quy tắc
 * CEL và bảng DMN. {@code pinned.contentHash} là băm MOD-05 công bố cho phiên bản.
 */
public record PolicyContent(
    PinnedPolicy pinned,
    Map<String, Object> manifest,
    List<PolicyRuleSource> rules,
    List<DecisionTableSource> tables) {

  public PolicyContent {
    Objects.requireNonNull(pinned, "pinned");
    manifest = manifest == null ? Map.of() : manifest;
    rules = List.copyOf(rules);
    tables = List.copyOf(tables);
  }
}
