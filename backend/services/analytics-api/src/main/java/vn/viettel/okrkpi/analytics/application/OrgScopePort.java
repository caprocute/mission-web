package vn.viettel.okrkpi.analytics.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Đọc cây tổ chức và kỳ của kho phân tích (DV-15-01: dim_org_scope, dim_org_closure, dim_period).
 */
public interface OrgScopePort {

  /**
   * Đường dẫn của đơn vị trên cây Đảng rồi cây chuyên môn (bỏ đường dẫn rỗng); rỗng nếu chưa có.
   */
  List<String> paths(UUID tenant, UUID unit);

  /** Đơn vị và các đơn vị cấp trên trên cả hai cây, gần nhất trước, tối đa {@code limit}. */
  List<UUID> unitAndAncestors(UUID tenant, UUID unit, int limit);

  /** Kỳ liền trước cùng loại và cùng luồng đánh giá (theo ngày bắt đầu). */
  Optional<UUID> previousPeriod(UUID tenant, UUID period);
}
