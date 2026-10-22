package vn.viettel.okrkpi.analytics.domain;

import java.util.List;

/** Một widget của dashboard ({@code DashboardWidget}): đúng một chỉ số, tối đa hai chiều. */
public record DashboardWidget(
    String id,
    int x,
    int y,
    int w,
    int h,
    String measure,
    List<String> dimensions,
    String chartType,
    List<Filter> filters,
    Boolean drillDown) {

  public DashboardWidget {
    dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    filters = filters == null ? List.of() : List.copyOf(filters);
  }
}
