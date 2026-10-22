package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Định nghĩa dashboard người dùng tự dựng ({@code config.dashboard_def}, QT-16, SCR-1602,
 * CORE-08-17). Chỉ giữ bố cục và tên chỉ số, chiều; số liệu luôn lấy từ analytics-api (SVC-15).
 * {@code layoutJson} là đối tượng {@code {"widgets":[...],"semanticModelVersion":"..."}} vì bảng
 * không có cột riêng cho phiên bản lớp ngữ nghĩa.
 */
public record DashboardDefinition(
    UUID id,
    UUID ownerUserId,
    String title,
    List<String> measures,
    List<String> dimensions,
    String layoutJson,
    ShareScope shareScope,
    List<UUID> sharedUnitIds,
    Instant updatedAt,
    int rowVersion) {

  /** SCR-1602 trường 1. */
  public static final int MAX_TITLE_LENGTH = 100;

  /** SCR-1602 trường 4, BR-16-02. */
  public static final int MAX_WIDGETS = 20;

  /** SCR-1602 trường 5. */
  public static final int MAX_DIMENSIONS_PER_WIDGET = 2;

  public static final int MAX_SHARED_UNITS = 200;

  /**
   * Cú pháp tên chỉ số, chiều công bố của lớp ngữ nghĩa ({@code okr.tien_do_o}); việc tên có thật
   * hay không do analytics-api kiểm.
   */
  public static final Pattern MEMBER =
      Pattern.compile("^[a-z][a-z0-9_]{0,63}(\\.[a-z][a-z0-9_]{0,63}){1,3}$");

  /** Phạm vi chia sẻ (SCR-1602 trường 9). */
  public enum ShareScope {
    RIENG,
    DON_VI,
    TENANT
  }

  public DashboardDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ownerUserId, "ownerUserId");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(layoutJson, "layoutJson");
    Objects.requireNonNull(shareScope, "shareScope");
    measures = List.copyOf(measures);
    dimensions = List.copyOf(dimensions);
    sharedUnitIds = List.copyOf(sharedUnitIds);
    if (shareScope == ShareScope.DON_VI ? sharedUnitIds.isEmpty() : !sharedUnitIds.isEmpty()) {
      throw new IllegalArgumentException("Danh sách đơn vị chia sẻ không khớp phạm vi");
    }
  }

  /** Người xem thấy được dashboard: người tạo, toàn tenant, hoặc đơn vị của họ được chia sẻ. */
  public boolean visibleTo(UUID userId, List<UUID> viewerUnits) {
    return ownerUserId.equals(userId)
        || shareScope == ShareScope.TENANT
        || (shareScope == ShareScope.DON_VI
            && viewerUnits.stream().anyMatch(sharedUnitIds::contains));
  }

  /** Bản kế tiếp khi người tạo thay nội dung. */
  public DashboardDefinition replaced(
      String newTitle,
      List<String> newMeasures,
      List<String> newDimensions,
      String newLayout,
      ShareScope newScope,
      List<UUID> newUnits,
      Instant at) {
    return new DashboardDefinition(
        id,
        ownerUserId,
        newTitle,
        newMeasures,
        newDimensions,
        newLayout,
        newScope,
        newUnits,
        at,
        rowVersion + 1);
  }
}
