package vn.viettel.okrkpi.config.api;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng cờ tính năng của MOD-08 cho các mô-đun khác (CORE-08-07, OPS-04): hỏi "cờ này có bật cho
 * tenant, đơn vị, người dùng này không". Xét {@code enabled}, khoảng hiệu lực và quy tắc bật dần;
 * đọc qua đệm ngắn hạn. Cờ không có trong danh mục nền tảng luôn tắt.
 */
public interface FeatureFlagApi {

  /** Ngữ cảnh đánh giá cờ: người dùng và chuỗi đơn vị từ đơn vị của họ lên cấp trên. */
  record FlagContext(UUID userId, List<UUID> unitAncestry) {

    public static final FlagContext TENANT = new FlagContext(null, List.of());

    public FlagContext {
      unitAncestry = unitAncestry == null ? List.of() : List.copyOf(unitAncestry);
    }
  }

  boolean isEnabled(TenantId tenantId, String flagKey, FlagContext context);
}
