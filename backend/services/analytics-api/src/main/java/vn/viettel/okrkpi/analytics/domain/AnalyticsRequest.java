package vn.viettel.okrkpi.analytics.domain;

import java.util.List;
import java.util.UUID;

/**
 * Truy vấn đã kiểm cú pháp ({@code AnalyticsQuery}); tên chỉ số và chiều kiểm theo lớp ngữ nghĩa.
 */
public record AnalyticsRequest(
    List<String> measures,
    List<String> dimensions,
    List<Filter> filters,
    UUID periodId,
    UUID unitId,
    String timeGranularity,
    boolean compareToPreviousPeriod,
    int limit) {

  public static final int DEFAULT_LIMIT = 50;

  public AnalyticsRequest {
    measures = measures == null ? List.of() : List.copyOf(measures);
    dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    filters = filters == null ? List.of() : List.copyOf(filters);
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
  }

  public AnalyticsRequest withPeriod(UUID period) {
    return new AnalyticsRequest(
        measures, dimensions, filters, period, unitId, timeGranularity, false, limit);
  }
}
