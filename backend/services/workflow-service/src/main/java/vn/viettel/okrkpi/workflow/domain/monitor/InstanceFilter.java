package vn.viettel.okrkpi.workflow.domain.monitor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Bộ lọc danh sách giám sát tiến trình ({@code listWorkflowInstances}, BS-01 SCR-1506). Phạm vi
 * tenant: quản trị tenant xem tiến trình của tenant mình. Phạm vi cell ({@code tenantId == null}):
 * quản trị nền tảng xem tổng hợp mọi tenant của cell, không thấy nội dung hồ sơ.
 *
 * @param tenantId tenant được xem, {@code null} ở phạm vi cell
 * @param templateCode lọc theo mã mẫu, có thể {@code null}
 * @param stuck chỉ lấy tiến trình kẹt ({@code true}), không kẹt ({@code false}) hoặc mọi tiến trình
 *     ({@code null})
 * @param periodId lọc theo kỳ, có thể {@code null}
 * @param now thời điểm xét quá hạn
 */
public record InstanceFilter(
    UUID tenantId, String templateCode, Boolean stuck, UUID periodId, Instant now) {

  public InstanceFilter {
    Objects.requireNonNull(now, "now");
    if (templateCode != null && templateCode.isBlank()) {
      templateCode = null;
    }
  }

  /** Phạm vi một tenant. */
  public static InstanceFilter tenant(
      UUID tenantId, String templateCode, Boolean stuck, UUID periodId, Instant now) {
    return new InstanceFilter(
        Objects.requireNonNull(tenantId, "tenantId"), templateCode, stuck, periodId, now);
  }

  /** Phạm vi cell (mọi tenant của cell). */
  public static InstanceFilter cell(
      String templateCode, Boolean stuck, UUID periodId, Instant now) {
    return new InstanceFilter(null, templateCode, stuck, periodId, now);
  }

  /** Đúng khi xem tổng hợp cả cell. */
  public boolean cellScope() {
    return tenantId == null;
  }

  /** Phạm vi con trỏ: con trỏ của bộ lọc này không dùng được cho bộ lọc khác. */
  public String cursorScope() {
    return "workflow.instances|"
        + (tenantId == null ? "cell" : tenantId.toString())
        + '|'
        + (templateCode == null ? "" : templateCode)
        + '|'
        + (stuck == null ? "" : stuck.toString())
        + '|'
        + (periodId == null ? "" : periodId.toString());
  }
}
