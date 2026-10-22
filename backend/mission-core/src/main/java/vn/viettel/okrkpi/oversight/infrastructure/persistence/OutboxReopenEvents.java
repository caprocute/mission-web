package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.port.ReopenEvents;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * EVT-22 {@code oversight.period.reopened.v1} vào {@code oversight.outbox_event} cùng giao dịch
 * chuyển {@code DA_DUYET}; aggregate {@code ReopenRequest}, phiên bản là {@code row_version} mới.
 * MOD-08 ({@code PeriodReopenedListener}) chuyển kỳ sang {@code MO_LAI_HAU_KIEM}.
 */
public class OutboxReopenEvents implements ReopenEvents {

  static final String AGGREGATE_TYPE = "ReopenRequest";

  private final OutboxWriter outbox;

  public OutboxReopenEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void reopened(TenantId tenant, ReopenRequest r, List<UUID> approverIds, Instant at) {
    KyDaMoLaiHauKiem payload =
        KyDaMoLaiHauKiem.newBuilder()
            .setPeriodId(r.periodId())
            .setReopenRequestId(r.id())
            .setScope(r.scope().name())
            .setScopeIds(r.scopeIds())
            .setApproverIds(approverIds)
            .setExecutedAt(at)
            .setAggregateVersion(r.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
            tenant,
            AGGREGATE_TYPE,
            r.id(),
            r.rowVersion(),
            payload,
            Classification.C1));
  }
}
