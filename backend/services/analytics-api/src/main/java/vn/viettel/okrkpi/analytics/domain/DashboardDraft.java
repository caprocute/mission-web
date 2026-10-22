package vn.viettel.okrkpi.analytics.domain;

import java.util.List;
import java.util.UUID;

/** Nội dung gửi MOD-08 khi tạo hoặc thay định nghĩa ({@code DashboardDefInput}). */
public record DashboardDraft(
    String title,
    List<DashboardWidget> layout,
    String shareScope,
    List<UUID> sharedUnitIds,
    String semanticModelVersion) {

  public static final String RIENG = "RIENG";
  public static final String DON_VI = "DON_VI";
  public static final int MAX_WIDGETS = 20;

  public DashboardDraft {
    layout = layout == null ? List.of() : List.copyOf(layout);
    sharedUnitIds = sharedUnitIds == null ? List.of() : List.copyOf(sharedUnitIds);
  }
}
