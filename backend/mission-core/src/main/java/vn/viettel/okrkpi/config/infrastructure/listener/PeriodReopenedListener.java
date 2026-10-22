package vn.viettel.okrkpi.config.infrastructure.listener;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.PeriodReopenService;
import vn.viettel.okrkpi.config.domain.ReopenScope;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * Consumer EVT-22 {@code oversight.period.reopened.v1} của MOD-08 (CORE-08-06): chuyển kỳ sang
 * {@code MO_LAI_HAU_KIEM} trong phạm vi được duyệt. Được gọi qua {@code InboxMessageProcessor} của
 * schema config, nên inbox khử trùng lặp và ghi kỳ nằm trong cùng giao dịch.
 */
public class PeriodReopenedListener {

  /** Định danh consumer trong {@code config.inbox_processed}. */
  public static final String CONSUMER = "config.period-reopen";

  /** Loại thực thể gốc để kiểm thứ tự phiên bản. */
  public static final String AGGREGATE_TYPE = "ReopenRequest";

  private final PeriodReopenService reopens;

  public PeriodReopenedListener(PeriodReopenService reopens) {
    this.reopens = Objects.requireNonNull(reopens, "reopens");
  }

  public void on(KyDaMoLaiHauKiem event, InboxEnvelope<?> envelope) {
    TenantId tenant = envelope.tenantId();
    List<UUID> approvers = event.getApproverIds();
    UUID actor =
        envelope.actorId() != null
            ? envelope.actorId().value()
            : approvers.isEmpty()
                ? event.getReopenRequestId()
                : approvers.get(approvers.size() - 1);
    Instant executedAt = event.getExecutedAt();
    reopens.onReopened(
        tenant,
        PeriodId.of(event.getPeriodId()),
        event.getReopenRequestId(),
        ReopenScope.of(event.getScope(), event.getScopeIds()),
        executedAt,
        null,
        actor);
  }
}
