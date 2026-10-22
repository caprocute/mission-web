package vn.viettel.okrkpi.analytics.domain;

import java.time.Instant;
import java.util.List;

/** Kết quả truy vấn ({@code AnalyticsQueryResult}); {@code stale} khi dữ liệu trễ quá ngưỡng. */
public record QueryResult(
    String semanticModelVersion,
    Instant dataAsOf,
    boolean stale,
    List<Column> columns,
    List<List<Object>> rows) {

  public QueryResult {
    columns = List.copyOf(columns);
    rows =
        rows.stream()
            .map(r -> java.util.Collections.unmodifiableList(new java.util.ArrayList<>(r)))
            .toList();
  }
}
