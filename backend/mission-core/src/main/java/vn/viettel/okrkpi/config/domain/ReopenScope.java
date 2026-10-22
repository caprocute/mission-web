package vn.viettel.okrkpi.config.domain;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phạm vi mở lại hậu kiểm của kỳ (QT-13 bước 8, BR-13-05, EVT-22 trường {@code scope}, {@code
 * scopeIds}): toàn kỳ, một số đơn vị, hoặc một số hồ sơ. Kiểm một lệnh ghi chỉ là tra tập băm.
 */
public record ReopenScope(Kind kind, Set<UUID> ids) {

  /** Giá trị {@code scope} của {@code oversight.reopen_request}. */
  public enum Kind {
    TOAN_KY,
    DON_VI,
    HO_SO
  }

  public ReopenScope {
    Objects.requireNonNull(kind, "kind");
    ids = ids == null ? Set.of() : Set.copyOf(ids);
    if (kind != Kind.TOAN_KY && ids.isEmpty()) {
      throw new IllegalArgumentException("Phạm vi " + kind + " phải có ít nhất một đối tượng");
    }
  }

  public static ReopenScope of(String kind, Collection<UUID> ids) {
    return new ReopenScope(Kind.valueOf(kind), ids == null ? Set.of() : Set.copyOf(ids));
  }

  /**
   * Đối tượng có nằm trong phạm vi được mở không.
   *
   * @param unitId đơn vị sở hữu dữ liệu được ghi, có thể trống
   * @param dossierId hồ sơ đánh giá chứa dữ liệu được ghi, có thể trống
   */
  public boolean covers(UUID unitId, UUID dossierId) {
    return switch (kind) {
      case TOAN_KY -> true;
      case DON_VI -> unitId != null && ids.contains(unitId);
      case HO_SO -> dossierId != null && ids.contains(dossierId);
    };
  }
}
