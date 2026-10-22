package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code oversight.reopen_request} và các bảng MOD-07 mà yêu cầu mở lại tham chiếu. */
public interface ReopenRequestRepository {

  /** Khoá tư vấn theo tenant và năm, giữ tới hết giao dịch, để cấp mã {@code MLK-yyyy-nnn}. */
  void lockCodes(TenantId tenant, int year);

  /** Các mã đã cấp trong năm ({@code MLK-yyyy-%}) của tenant, mọi phân vùng. */
  List<String> codesOfYear(TenantId tenant, int year);

  /** Yêu cầu của kỳ, mọi trạng thái. */
  List<ReopenRequest> ofPeriod(TenantId tenant, UUID periodId);

  void insert(TenantId tenant, ReopenRequest request, UUID actorId);

  Optional<ReopenRequest> find(TenantId tenant, UUID id);

  /** Khoá dòng yêu cầu ({@code SELECT … FOR UPDATE}) để tuần tự hoá phê duyệt đồng thời. */
  Optional<ReopenRequest> lock(TenantId tenant, UUID id);

  /**
   * Ghi trạng thái, {@code scope_json}, {@code executed_at} và {@code row_version} mới; {@code
   * false} khi {@code row_version} đã khác {@code expectedVersion}.
   */
  boolean update(TenantId tenant, ReopenRequest changed, int expectedVersion, UUID actorId);

  /** Ghi một dòng {@code oversight.reopen_approval} (chỉ thêm). */
  void insertApproval(TenantId tenant, ReopenRequest request, Approval approval);

  /** Yêu cầu {@code DA_DUYET} chờ MOD-08 mở kỳ, cũ trước. */
  List<ReopenRequest> approvedNotExecuted(TenantId tenant, int limit);

  /** Đối tượng căn cứ thuộc MOD-07 có tồn tại trong kỳ không. */
  boolean basisExists(TenantId tenant, UUID periodId, ReopenBasis basis);

  /** Điều chỉnh của kết luận khiếu nại mới nhất ({@code adjustment_json.items}), có thể rỗng. */
  List<Adjustment> complaintAdjustments(TenantId tenant, UUID complaintId);

  /** Các phê duyệt đã ghi ({@code oversight.reopen_approval}), cũ trước. */
  List<Approval> approvals(TenantId tenant, int periodBucket, UUID requestId);

  /** Một trang theo {@code (created_at, id)} giảm dần. */
  List<ReopenRequest> page(TenantId tenant, Filter filter, After after, int fetchSize);

  /** Điều chỉnh giả định cho mô phỏng (SCR-1202 trường 7). */
  record Adjustment(String targetKey, String oldValue, String newValue, String reason) {}

  record Approval(
      UUID id,
      UUID approverId,
      String approverRole,
      String decision,
      String mfaAcr,
      Instant decidedAt,
      UUID signRequestId,
      String comment) {}

  /**
   * Bộ lọc danh sách. {@code everything} cho cán bộ kiểm tra (toàn tenant); nếu không, chỉ yêu cầu
   * do {@code viewer} lập, được chỉ định cho hoặc có phê duyệt của {@code viewer}, hoặc có đơn vị
   * trong {@code units}. {@code awaitingFor} chỉ giữ yêu cầu đang chờ chính người đó duyệt.
   */
  record Filter(
      UUID periodId,
      Collection<ReopenStatus> statuses,
      boolean everything,
      UUID viewer,
      Collection<UUID> units,
      UUID awaitingFor) {

    public Filter(
        UUID periodId,
        Collection<ReopenStatus> statuses,
        boolean everything,
        UUID viewer,
        Collection<UUID> units) {
      this(periodId, statuses, everything, viewer, units, null);
    }
  }

  record After(Instant createdAt, UUID id) {}
}
