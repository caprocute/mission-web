package vn.viettel.okrkpi.analytics.domain;

import java.time.Instant;
import java.util.List;

/** Số liệu kèm dashboard khi {@code includeData}: mỗi widget một bảng cột, dòng. */
public record WidgetData(Instant dataAsOf, boolean stale, List<Item> widgets) {

  public record Item(String widgetId, List<Column> columns, List<List<Object>> rows) {}
}
