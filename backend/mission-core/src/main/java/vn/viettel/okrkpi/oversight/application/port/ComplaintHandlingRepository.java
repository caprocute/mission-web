package vn.viettel.okrkpi.oversight.application.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintHandling;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRecordKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResponse;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phần xử lý của {@code oversight.complaint} và bản ghi chỉ thêm {@code
 * oversight.complaint_response} (CORE-07-03, CORE-07-04).
 */
public interface ComplaintHandlingRepository {

  /** Khiếu nại kèm phần xử lý. */
  record Handled(Complaint complaint, ComplaintHandling handling) {}

  Optional<Handled> find(TenantId tenant, UUID id);

  /** {@code SELECT ... FOR UPDATE}. */
  Optional<Handled> lock(TenantId tenant, UUID id);

  /**
   * Ghi trạng thái, phần xử lý và {@code row_version} của {@code changed} khi {@code row_version}
   * còn bằng {@code expected}.
   */
  boolean update(
      TenantId tenant, Complaint changed, ComplaintHandling handling, int expected, UUID actorId);

  /** Khiếu nại theo yêu cầu ký văn bản trả lời ở SVC-10. */
  Optional<Handled> byResponseSignRequest(TenantId tenant, UUID signRequestId);

  /**
   * Khiếu nại đã gửi của các đơn vị xử lý ({@code units} rỗng là mọi đơn vị), sắp theo hạn trả lời
   * rồi mã.
   *
   * @param handler chỉ khiếu nại người này đã tiếp nhận; {@code null} là mọi người
   * @param complainant thêm khiếu nại đã gửi của người này dù ngoài các đơn vị ({@code null} là
   *     không thêm), để người xử lý vẫn theo dõi khiếu nại của chính mình
   */
  List<Complaint> inScope(
      TenantId tenant,
      Collection<UUID> units,
      UUID handler,
      UUID complainant,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      ComplaintKind kind,
      DeadlineAfter after,
      int limit);

  /** Con trỏ theo {@code (response_deadline, id)} tăng dần. */
  record DeadlineAfter(LocalDate deadline, UUID id) {}

  void insertResponse(TenantId tenant, ComplaintResponse r, UUID actorId);

  /** Bản ghi của khiếu nại theo thời gian; {@code kind} {@code null} là mọi loại. */
  List<ComplaintResponse> responses(
      TenantId tenant,
      Complaint complaint,
      ComplaintRecordKind kind,
      ResponseAfter after,
      int limit);

  /** Con trỏ theo {@code (responded_at, id)} tăng dần. */
  record ResponseAfter(java.time.Instant respondedAt, UUID id) {}

  /**
   * Khiếu nại {@code DANG_GIAI_TRINH} mà mọi yêu cầu giải trình đã có trả lời hoặc đã quá hạn
   * ({@code due_date < today}).
   */
  List<UUID> explanationsSettled(TenantId tenant, LocalDate today, int limit);
}
