package vn.viettel.okrkpi.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc định nghĩa dashboard tự dựng của MOD-08 ({@code config.dashboard_def}, QT-16, SCR-1602,
 * CORE-08-17) cho module khác trong mission-core. analytics-api (SVC-15) dùng thao tác dịch vụ
 * {@code /api/v1/config/dashboard-defs*} của {@code config.yaml}. Ghi chỉ qua lớp REST đó vì cần
 * kiểm quy tắc trường và chủ sở hữu.
 */
public interface DashboardDefinitionApi {

  /**
   * Định nghĩa người xem thấy được: của chính họ, chia sẻ toàn tenant, hoặc chia sẻ cho một đơn vị
   * trong {@code viewerUnits} (đơn vị công tác và các đơn vị cấp trên, bên gọi tra cây tổ chức).
   */
  Optional<DashboardDefinitionView> find(
      TenantId tenantId, UUID viewerUserId, List<UUID> viewerUnits, UUID id);

  /**
   * Định nghĩa dashboard ở dạng chỉ đọc; {@code layout} gồm {@code widgets} và phiên bản ngữ nghĩa.
   */
  record DashboardDefinitionView(
      UUID id,
      UUID ownerUserId,
      String title,
      String shareScope,
      List<UUID> sharedUnitIds,
      List<String> measures,
      List<String> dimensions,
      JsonNode layout,
      Instant updatedAt,
      int rowVersion) {

    public DashboardDefinitionView {
      sharedUnitIds = List.copyOf(sharedUnitIds);
      measures = List.copyOf(measures);
      dimensions = List.copyOf(dimensions);
    }
  }
}
