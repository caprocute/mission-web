package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.ComplaintReopener;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintConclusion;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScopeItem;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kết luận có điều chỉnh trên kỳ đã khoá lập yêu cầu mở lại kỳ (BR-12-07) qua {@link
 * ReopenRequestService#createInternal}: phạm vi {@code HO_SO} của hồ sơ bị khiếu nại, loại hậu kiểm
 * {@code PHAN_ANH}, căn cứ là khiếu nại, thời hạn mặc định của QT-13 (suy luận, wiki trang 14).
 */
public class ReopenComplaintReopener implements ComplaintReopener {

  static final String AUDIT_KIND = "PHAN_ANH";
  static final String PROFILE_TABLE = "appraisal.appraisal_profile";
  static final int REASON_MAX = 4000;

  private final ReopenRequestService reopen;

  public ReopenComplaintReopener(ReopenRequestService reopen) {
    this.reopen = Objects.requireNonNull(reopen, "reopen");
  }

  @Override
  public UUID open(TenantId tenant, UUID requesterId, Complaint c, ComplaintConclusion k) {
    List<String> fields = k.adjustments().stream().map(a -> a.targetKey()).toList();
    String reason =
        "Thi hành kết luận giải quyết khiếu nại "
            + c.code()
            + ": điều chỉnh "
            + String.join(", ", fields)
            + " của hồ sơ đánh giá thuộc kỳ đã khoá.";
    if (reason.length() > REASON_MAX) {
      reason = reason.substring(0, REASON_MAX - 1) + "…";
    }
    return reopen
        .createInternal(
            tenant,
            requesterId,
            new ReopenRequestService.Draft(
                c.periodId(),
                AUDIT_KIND,
                ReopenScope.HO_SO,
                List.of(c.profileId()),
                List.of(new ReopenScopeItem(PROFILE_TABLE, c.profileId(), null, null, List.of())),
                null,
                reason,
                new ReopenBasis(ReopenBasis.Kind.KHIEU_NAI, c.id(), c.code())))
        .request()
        .id();
  }
}
