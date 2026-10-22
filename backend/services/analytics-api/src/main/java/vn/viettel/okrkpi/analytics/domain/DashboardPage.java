package vn.viettel.okrkpi.analytics.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trang danh sách dashboard ({@code AnalyticsDashboardPage}), phân trang con trỏ. */
public record DashboardPage(List<Summary> items, String nextCursor) {

  public record Summary(
      UUID id,
      String title,
      UUID ownerUserId,
      String shareScope,
      Integer widgetCount,
      Instant updatedAt) {}

  public DashboardPage {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
