package vn.viettel.okrkpi.workflow.application.monitor;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Cấu hình giám sát tiến trình (DV-06-10).
 *
 * @param cellCode mã cell nơi dịch vụ chạy (trường {@code cellCode} của danh sách)
 * @param defaultMaxExtensionDays số ngày làm việc tối đa được gia hạn khi tenant chưa khai tham số
 *     {@code gia_han_toi_da_ngay_lam_viec} (suy luận)
 * @param zone múi giờ nghiệp vụ để đổi ngày làm việc
 */
public record MonitorSettings(String cellCode, int defaultMaxExtensionDays, ZoneId zone) {

  public MonitorSettings {
    Objects.requireNonNull(cellCode, "cellCode");
    Objects.requireNonNull(zone, "zone");
    if (cellCode.isBlank()) {
      throw new IllegalArgumentException("Thiếu mã cell");
    }
    if (defaultMaxExtensionDays < 0) {
      throw new IllegalArgumentException("Giới hạn gia hạn không được âm");
    }
  }
}
