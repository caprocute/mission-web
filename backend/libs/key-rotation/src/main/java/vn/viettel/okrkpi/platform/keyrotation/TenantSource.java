package vn.viettel.okrkpi.platform.keyrotation;

import java.util.List;
import java.util.UUID;

/**
 * Nguồn danh sách tenant của một CSDL. {@code keyring.dek} bật RLS FORCE cho mọi vai trò (kể cả
 * {@code svc_keyring}), nên job không tự liệt kê được tenant; danh sách lấy từ tenant-registry
 * (SVC-03) hoặc cấu hình của cell (suy luận, DL-14).
 */
@FunctionalInterface
public interface TenantSource {

  List<UUID> tenants();
}
