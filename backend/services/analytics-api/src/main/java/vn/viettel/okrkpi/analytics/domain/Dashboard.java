package vn.viettel.okrkpi.analytics.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Định nghĩa dashboard lưu ở {@code config.dashboard_def} (MOD-08, CORE-08-17). */
public record Dashboard(
    UUID id,
    String title,
    UUID ownerUserId,
    List<String> measures,
    List<String> dimensions,
    List<DashboardWidget> layout,
    String shareScope,
    List<UUID> sharedUnitIds,
    String semanticModelVersion,
    long rowVersion,
    Instant updatedAt) {

  public Dashboard {
    measures = measures == null ? List.of() : List.copyOf(measures);
    dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    layout = layout == null ? List.of() : List.copyOf(layout);
    sharedUnitIds = sharedUnitIds == null ? List.of() : List.copyOf(sharedUnitIds);
  }
}
