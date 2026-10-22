package vn.viettel.okrkpi.analytics.domain;

import java.util.List;

/** Bộ lọc trên một chiều công bố ({@code AnalyticsFilter}). */
public record Filter(String member, String operator, List<Object> values) {

  public Filter {
    values = values == null ? List.of() : List.copyOf(values);
  }
}
