package vn.viettel.okrkpi.workflow.application.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng đọc bản sao tổ chức của workflow-service ({@code org_replica.assignment_v}, {@code
 * org_replica.person_v}) để kiểm người nhận khi chuyển người xử lý (TL-03 schema org_replica).
 */
public interface AssigneeDirectory {

  /**
   * Đúng khi người có phân công còn hiệu lực vào ngày {@code on} ở đơn vị {@code unitId}, hoặc ở
   * bất kỳ đơn vị nào của tenant khi {@code unitId == null}.
   */
  boolean activeIn(UUID tenantId, UUID personId, UUID unitId, LocalDate on);

  /**
   * Tình trạng công tác của người trong {@code org_replica.person_v} (giá trị thô của EVT-16, ví dụ
   * {@code DANG_CONG_TAC}, {@code NGHI_HUU}); rỗng khi bản sao chưa có dòng của người này.
   */
  Optional<String> employmentStatus(UUID tenantId, UUID personId);
}
