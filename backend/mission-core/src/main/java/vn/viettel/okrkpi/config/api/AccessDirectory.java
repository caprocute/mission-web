package vn.viettel.okrkpi.config.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng tra danh bạ mà quản trị vai trò và uỷ quyền (CORE-08-16) cần: tên hiển thị cho {@code Ref}
 * và cây đơn vị cho phạm vi. MOD-08 chỉ được phụ thuộc {@code shared}, nên cổng này do mô-đun bản
 * sao tổ chức ({@code replica}, được phép phụ thuộc {@code config :: api}) hiện thực trên {@code
 * org_replica}. Không có bean thì tên rỗng và phạm vi chỉ gồm chính đơn vị.
 */
public interface AccessDirectory {

  /** Họ tên theo mã người; mã không biết thì vắng trong kết quả. */
  Map<UUID, String> personNames(TenantId tenant, Collection<UUID> personIds);

  /**
   * Chức danh hiện hành theo mã người (phân công chính trước); người chưa có tên chức danh thì vắng
   * trong kết quả.
   */
  default Map<UUID, String> personTitles(TenantId tenant, Collection<UUID> personIds) {
    return Map.of();
  }

  /** Tên đơn vị theo mã. */
  Map<UUID, String> unitNames(TenantId tenant, Collection<UUID> unitIds);

  /** Mọi đơn vị cấp dưới (không gồm chính nó) trên mọi cây tổ chức. */
  List<UUID> subunitsOf(TenantId tenant, UUID orgUnitId);

  /** Các đơn vị cấp trên, gần trước (không gồm chính nó). */
  List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId);
}
