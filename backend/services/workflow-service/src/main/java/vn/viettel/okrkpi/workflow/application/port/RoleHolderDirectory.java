package vn.viettel.okrkpi.workflow.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Người giữ vai trò có hiệu lực theo đơn vị, đọc từ bản sao {@code org_replica.role_holder_v} của
 * workflow-service (bảng bổ sung, bản sao của {@code config.role_binding}; suy luận, wiki trang
 * 14).
 */
public interface RoleHolderDirectory {

  /** Người giữ {@code role} tại từng đơn vị vào ngày {@code on}; đơn vị không có người thì vắng. */
  Map<UUID, List<UUID>> holders(UUID tenantId, String role, List<UUID> units, LocalDate on);
}
