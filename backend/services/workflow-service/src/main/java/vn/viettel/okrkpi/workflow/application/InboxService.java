package vn.viettel.okrkpi.workflow.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.workflow.application.port.ApprovalAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.CurrentApprover;
import vn.viettel.okrkpi.workflow.application.port.TaskProjectionStore;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.InboxCounts;
import vn.viettel.okrkpi.workflow.domain.InboxFilter;
import vn.viettel.okrkpi.workflow.domain.InboxPosition;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;

/**
 * Hộp việc cần duyệt ({@code listWorkflowInbox}, SCR-0203): đọc {@code flowable.wf_task_projection}
 * chứ không truy vấn {@code ACT_*} (TL-01 SVC-06), phân trang con trỏ tối đa 200 dòng.
 */
@Service
public class InboxService {

  /** Ngày nghiệp vụ theo giờ Việt Nam để tính "đến hạn hôm nay". */
  static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  /** Giá trị keyset thay cho hạn rỗng (việc không hạn xếp cuối). */
  static final String NO_DUE = "-";

  private final TaskProjectionStore store;
  private final CurrentApprover currentApprover;
  private final ApprovalAccessPolicy policy;
  private final CursorCodec cursors;
  private final Clock clock;

  public InboxService(
      TaskProjectionStore store,
      CurrentApprover currentApprover,
      ApprovalAccessPolicy policy,
      CursorCodec cursors,
      Clock clock) {
    this.store = store;
    this.currentApprover = currentApprover;
    this.policy = policy;
    this.cursors = cursors;
    this.clock = clock;
  }

  /**
   * Một trang hộp việc của người gọi.
   *
   * @param businessType lọc theo loại đối tượng, có thể {@code null}
   * @param periodId lọc theo kỳ, có thể {@code null}
   * @param limit số dòng, {@code null} dùng mặc định 20
   * @param cursor con trỏ trang trước, {@code null} ở trang đầu
   */
  @Transactional(readOnly = true, timeout = 5)
  public InboxPage list(String businessType, UUID periodId, Integer limit, String cursor) {
    Approver approver = currentApprover.current();
    policy.checkView(
        AuthzResource.builder()
            .type("workflow_inbox")
            .tenantId(approver.tenantId().toString())
            .ownerUnitId(approver.unitId() == null ? null : approver.unitId().toString())
            .classification("C1")
            .build());
    InboxFilter filter = new InboxFilter(approver, businessType, periodId);
    PageRequest request = cursors.pageRequest(limit, cursor, filter.cursorScope());
    InboxPosition after =
        request
            .afterKey()
            .map(
                k ->
                    new InboxPosition(
                        NO_DUE.equals(k.getString(0)) ? null : k.getInstant(0), k.getString(1)))
            .orElse(null);
    List<TaskProjection> rows = store.findInbox(filter, after, request.fetchSize());
    CursorPage<TaskProjection> page = cursors.page(rows, request, InboxService::keyOf);
    Instant now = clock.instant();
    Instant endOfToday =
        LocalDate.ofInstant(now, BUSINESS_ZONE).plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
    InboxCounts counts = store.count(filter, now, endOfToday);
    return new InboxPage(page.items(), page.nextCursor(), counts, now);
  }

  private static CursorKey keyOf(TaskProjection row) {
    return CursorKey.of(row.dueAt() == null ? NO_DUE : row.dueAt(), row.taskId());
  }

  /**
   * Trang hộp việc.
   *
   * @param items các dòng
   * @param nextCursor con trỏ trang sau, {@code null} khi hết
   * @param counts số đếm
   * @param evaluatedAt thời điểm tính cờ quá hạn
   */
  public record InboxPage(
      List<TaskProjection> items, String nextCursor, InboxCounts counts, Instant evaluatedAt) {

    public InboxPage {
      items = List.copyOf(items);
    }
  }
}
