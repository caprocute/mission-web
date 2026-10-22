package vn.viettel.okrkpi.workflow.application.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;

/**
 * Cổng đọc cây tổ chức từ bản sao {@code org_replica} của workflow-service (TL-01 G2: phân giải
 * phạm vi duyệt bằng dữ liệu {@code org_replica} tại thời điểm khởi tạo, không gọi đồng bộ SVC-04).
 */
public interface OrgDirectory {

  /** Đơn vị của tenant có hiệu lực vào ngày {@code on}; rỗng khi không có. */
  Optional<OrgUnit> unit(UUID tenantId, UUID unitId, LocalDate on);
}
