package vn.viettel.okrkpi.analytics.application;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.domain.Dashboard;
import vn.viettel.okrkpi.analytics.domain.DashboardDraft;
import vn.viettel.okrkpi.analytics.domain.DashboardPage;
import vn.viettel.okrkpi.analytics.domain.Viewer;

/**
 * Cổng lưu định nghĩa dashboard ở MOD-08 ({@code /api/v1/config/dashboard-defs}, CORE-08-17).
 * MOD-08 quyết định ai thấy định nghĩa nào (người tạo, phạm vi chia sẻ, {@code viewerUnits}), kiểm
 * trùng tên và khoá lạc quan; lỗi nghiệp vụ của MOD-08 chuyển nguyên về người gọi.
 */
public interface DashboardStorePort {

  DashboardPage list(Viewer viewer, List<UUID> viewerUnits, boolean mine, int limit, String cursor);

  Dashboard create(Viewer viewer, DashboardDraft draft, String idempotencyKey);

  Dashboard get(Viewer viewer, List<UUID> viewerUnits, UUID id);

  Dashboard replace(
      Viewer viewer, UUID id, DashboardDraft draft, String idempotencyKey, String ifMatch);
}
