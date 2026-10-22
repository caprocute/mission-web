package vn.viettel.okrkpi.platform.retention;

import java.util.List;
import java.util.UUID;

/**
 * Danh sách tenant đặt trên CSDL đang xử lý. RLS FORCE không cho liệt kê chéo tenant, nên danh sách
 * đến từ bên ngoài (tenant-registry, ConfigMap); xem wiki 14 mục DL-14.
 */
@FunctionalInterface
public interface TenantSource {
  List<UUID> tenants();
}
