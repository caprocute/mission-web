package vn.viettel.okrkpi.integ.application.anchor;

import java.time.Duration;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cấu hình luồng neo của một cell.
 *
 * @param operatorTenant tenant vận hành cell, nơi khai báo các kết nối tới đích neo
 * @param cellCode mã cell
 * @param zone múi giờ xác định "cuối ngày"
 * @param sealKinds loại niêm phong có thể là bản neo trong ngày (mặc định {@code HOURLY})
 * @param targets mã kết nối theo từng đích neo
 * @param minReceipts số biên nhận tối thiểu một ngày (TL-03: ít nhất hai bên)
 * @param grace thời gian chờ sau cuối ngày rồi mới kiểm đủ biên nhận
 * @param fetchRetry khoảng chờ ban đầu khi ledger-service chưa có gói neo
 */
public record AnchorSettings(
    TenantId operatorTenant,
    String cellCode,
    ZoneId zone,
    Set<String> sealKinds,
    Map<AnchorTarget, String> targets,
    int minReceipts,
    Duration grace,
    Duration fetchRetry) {

  public AnchorSettings {
    Objects.requireNonNull(operatorTenant, "operatorTenant");
    Objects.requireNonNull(cellCode, "cellCode");
    zone = zone == null ? ZoneId.of("Asia/Ho_Chi_Minh") : zone;
    sealKinds = sealKinds == null || sealKinds.isEmpty() ? Set.of("HOURLY") : Set.copyOf(sealKinds);
    targets = targets == null || targets.isEmpty() ? Map.of() : new EnumMap<>(targets);
    if (targets.isEmpty()) {
      throw new IllegalArgumentException("Cần ít nhất một đích neo");
    }
    if (minReceipts < 1) {
      throw new IllegalArgumentException("Số biên nhận tối thiểu phải từ 1");
    }
    grace = grace == null ? Duration.ofHours(6) : grace;
    fetchRetry = fetchRetry == null ? Duration.ofMinutes(1) : fetchRetry;
  }
}
