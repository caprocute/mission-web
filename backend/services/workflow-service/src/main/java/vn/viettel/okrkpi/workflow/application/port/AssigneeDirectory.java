package vn.viettel.okrkpi.workflow.application.port;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Cổng đọc bản sao phân công {@code org_replica.assignment_v} của workflow-service để kiểm người
 * nhận khi chuyển người xử lý (TL-03 schema org_replica).
 */
public interface AssigneeDirectory {

  /**
   * Đúng khi người có phân công còn hiệu lực vào ngày {@code on} ở đơn vị {@code unitId}, hoặc ở
   * bất kỳ đơn vị nào của tenant khi {@code unitId == null}.
   */
  boolean activeIn(UUID tenantId, UUID personId, UUID unitId, LocalDate on);
}
