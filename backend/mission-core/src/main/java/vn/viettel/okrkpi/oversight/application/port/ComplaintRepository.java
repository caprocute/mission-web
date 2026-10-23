package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
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

  /**
   * Khiếu nại đã gửi có đơn vị xử lý thuộc {@code unitIds} hoặc đã do {@code handlerId} tiếp nhận
   * (SCR-1202 trường 1), sắp theo hạn trả lời; {@code assignedOnly} chỉ lấy của {@code handlerId}.
   */
  List<Complaint> forHandler(
      TenantId tenant,
      Collection<UUID> unitIds,
      UUID handlerId,
      boolean assignedOnly,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      ComplaintKind kind,
      HandlerAfter after,
      int limit);

  /** Còn khiếu nại khác (khác {@code exceptId}) giữ hồ sơ ở nhánh {@code KHIEU_NAI}. */
  boolean otherOpenReopening(TenantId tenant, UUID profileId, UUID exceptId);

  /**
   * Đánh dấu nháp đang mở yêu cầu ký ở SVC-10 ({@code at}), hoặc gỡ dấu khi {@code at} rỗng; chỉ
   * khi phiên bản dòng vẫn là {@code c.rowVersion()}. {@link #update} luôn gỡ dấu.
   */
  boolean markSignPending(TenantId tenant, Complaint c, Instant at);

  /** Nháp đang mở yêu cầu ký (đã đánh dấu, chưa gắn mã yêu cầu ký). */
  boolean signPending(TenantId tenant, UUID id);

  /**
   * Ghi người có thẩm quyền đang mở yêu cầu ký văn bản trả lời ({@code signer}), hoặc gỡ khi rỗng;
   * chỉ khi phiên bản dòng vẫn là {@code c.rowVersion()}.
   */
  boolean markResponseSigner(TenantId tenant, Complaint c, UUID signer);

  /** Người đang mở yêu cầu ký văn bản trả lời (đã ghi, chưa gắn mã yêu cầu ký). */
  Optional<UUID> responseSigner(TenantId tenant, UUID id);

  /** Mã khiếu nại theo yêu cầu ký ở SVC-10. */
  Optional<Complaint> bySignRequest(TenantId tenant, UUID signRequestId);

  /** Con trỏ phân trang theo {@code (created_at, id)} giảm dần. */
  record After(Instant createdAt, UUID id) {}

  /** Con trỏ danh sách người xử lý theo {@code (response_deadline, id)} tăng dần. */
  record HandlerAfter(LocalDate deadline, UUID id) {}
}
