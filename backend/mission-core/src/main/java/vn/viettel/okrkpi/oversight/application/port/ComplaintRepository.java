package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code oversight.complaint} (CORE-07-02). */
public interface ComplaintRepository {

  /** Khoá cấp mã {@code KN-yyyy-nnnn} của tenant trong năm đến hết giao dịch. */
  void lockCodes(TenantId tenant, int year);

  List<String> codesOfYear(TenantId tenant, int year);

  void insert(TenantId tenant, Complaint c, UUID actorId);

  Optional<Complaint> find(TenantId tenant, UUID id);

  /** {@code SELECT ... FOR UPDATE}. */
  Optional<Complaint> lock(TenantId tenant, UUID id);

  /** Ghi bản mới khi {@code row_version} còn bằng {@code expected}. */
  boolean update(TenantId tenant, Complaint changed, int expected, UUID actorId);

  /** Khiếu nại của một người, mới nhất trước; {@code statuses} rỗng là mọi trạng thái. */
  List<Complaint> ofComplainant(
      TenantId tenant,
      UUID personId,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      boolean includeDrafts,
      ComplaintKind kind,
      After after,
      int limit);

  /** Mã khiếu nại theo yêu cầu ký ở SVC-10. */
  Optional<Complaint> bySignRequest(TenantId tenant, UUID signRequestId);

  /** Con trỏ phân trang theo {@code (created_at, id)} giảm dần. */
  record After(Instant createdAt, UUID id) {}
}
