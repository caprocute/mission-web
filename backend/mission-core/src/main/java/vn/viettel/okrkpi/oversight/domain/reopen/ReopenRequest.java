package vn.viettel.okrkpi.oversight.domain.reopen;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Một dòng {@code oversight.reopen_request}. {@code items}, {@code basis}, {@code units} và {@code
 * cancelReason} được lưu trong {@code scope_json}; {@code units} là các đơn vị của phạm vi, dùng để
 * lọc theo phạm vi {@code Cd} của người lập.
 */
public record ReopenRequest(
    UUID id,
    UUID periodId,
    int periodBucket,
    String code,
    ReopenScope scope,
    List<UUID> scopeIds,
    List<ReopenScopeItem> items,
    List<UUID> units,
    ReopenBasis basis,
    String auditKind,
    String reason,
    UUID requestedBy,
    int approvalsRequired,
    ReopenStatus status,
    int openWorkingDays,
    Instant openUntil,
    Instant executedAt,
    Instant relockedAt,
    String cancelReason,
    ReopenWorkflow workflow,
    Instant createdAt,
    int rowVersion) {

  public ReopenRequest {
    workflow = workflow == null ? ReopenWorkflow.empty(null) : workflow;
    scopeIds = scopeIds == null ? List.of() : List.copyOf(scopeIds);
    items = items == null ? List.of() : List.copyOf(items);
    units = units == null ? List.of() : List.copyOf(units);
  }

  /** Còn hiệu lực: đang chờ duyệt, đã duyệt hoặc đang mở lại chưa khoá lại. */
  public boolean active() {
    return switch (status) {
      case CHO_DUYET_1, CHO_DUYET_2, DA_DUYET -> true;
      case DA_THUC_HIEN -> relockedAt == null;
      case TU_CHOI, DA_DONG -> false;
    };
  }

  /** Trùng phạm vi với một yêu cầu khác cùng kỳ: một bên toàn kỳ, hoặc cùng loại có mã chung. */
  public boolean overlaps(ReopenScope otherScope, List<UUID> otherIds) {
    if (scope == ReopenScope.TOAN_KY || otherScope == ReopenScope.TOAN_KY) {
      return true;
    }
    return scope == otherScope && otherIds.stream().anyMatch(scopeIds::contains);
  }

  public ReopenRequest cancelled(String why, int newVersion) {
    return with(ReopenStatus.DA_DONG, executedAt, why, workflow.withPending(null), newVersion);
  }

  /** Kỳ đã khoá lại (EVT-09): {@code DA_DONG} với {@code relocked_at}. */
  public ReopenRequest relocked(Instant at, int newVersion) {
    return new ReopenRequest(
        id,
        periodId,
        periodBucket,
        code,
        scope,
        scopeIds,
        items,
        units,
        basis,
        auditKind,
        reason,
        requestedBy,
        approvalsRequired,
        ReopenStatus.DA_DONG,
        openWorkingDays,
        openUntil,
        executedAt,
        at,
        cancelReason,
        workflow,
        createdAt,
        newVersion);
  }

  /** Bản sao với trạng thái, thời điểm thực hiện, lý do huỷ, bốn mắt và phiên bản mới. */
  public ReopenRequest with(
      ReopenStatus newStatus,
      Instant newExecutedAt,
      String why,
      ReopenWorkflow newWorkflow,
      int newVersion) {
    return new ReopenRequest(
        id,
        periodId,
        periodBucket,
        code,
        scope,
        scopeIds,
        items,
        units,
        basis,
        auditKind,
        reason,
        requestedBy,
        approvalsRequired,
        newStatus,
        openWorkingDays,
        openUntil,
        newExecutedAt,
        relockedAt,
        why,
        newWorkflow,
        createdAt,
        newVersion);
  }
}
