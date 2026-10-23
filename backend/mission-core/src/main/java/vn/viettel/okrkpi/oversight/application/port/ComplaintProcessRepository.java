package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintConclusion;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRecordKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResponse;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Giải trình và kết luận của khiếu nại (CORE-07-04): bản ghi chỉ thêm {@code
 * oversight.complaint_response} và các cột kết luận của {@code oversight.complaint}. Trạng thái và
 * {@code row_version} của khiếu nại vẫn ghi qua {@link ComplaintRepository#update}, cùng giao dịch.
 */
public interface ComplaintProcessRepository {

  Optional<ComplaintConclusion> conclusion(TenantId tenant, Complaint complaint);

  /** Ghi đè các cột kết luận của khiếu nại ({@code null} là xoá). */
  void saveConclusion(TenantId tenant, Complaint complaint, ComplaintConclusion conclusion);

  /** Mã khiếu nại theo yêu cầu ký văn bản trả lời ở SVC-10. */
  Optional<UUID> byResponseSignRequest(TenantId tenant, UUID signRequestId);

  void insertResponse(TenantId tenant, ComplaintResponse r, UUID actorId);

  Optional<ComplaintResponse> response(TenantId tenant, Complaint complaint, UUID id);

  /** Bản ghi của khiếu nại theo thời gian; {@code kind} {@code null} là mọi loại. */
  List<ComplaintResponse> responses(
      TenantId tenant,
      Complaint complaint,
      ComplaintRecordKind kind,
      ResponseAfter after,
      int limit);

  /** Con trỏ theo {@code (responded_at, id)} tăng dần. */
  record ResponseAfter(Instant respondedAt, UUID id) {}

  /**
   * Khiếu nại {@code DANG_GIAI_TRINH} mà mọi yêu cầu giải trình đã có trả lời hoặc đã quá hạn
   * ({@code due_date < today}).
   */
  List<UUID> explanationsSettled(TenantId tenant, LocalDate today, int limit);
}
