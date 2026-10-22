package vn.viettel.okrkpi.oversight.infrastructure.listener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.AuditIndexingService;
import vn.viettel.okrkpi.oversight.application.AuditIndexingService.AuditRecord;
import vn.viettel.okrkpi.oversight.application.AuditIndexingService.Target;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexUnavailableException;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.TransientInboxException;
import vn.vtokr.sys.v1.AuditEvent;
import vn.vtokr.sys.v1.AuditTarget;

/**
 * Consumer {@code oversight.audit-indexer} của {@code sys.audit.v1} (CORE-07-12): đi qua inbox của
 * schema oversight theo {@code event_id}, ghi tài liệu chỉ mục kiểm toán. Chỉ mục không nhận ghi
 * thì báo lỗi tạm thời để inbox thử lại, hết lượt thì vào DLQ {@code sys.audit.v1.dlq.<consumer>}.
 */
public class AuditIndexerListener {

  /** Định danh consumer trong {@code oversight.inbox_processed}. */
  public static final String CONSUMER = "oversight.audit-indexer";

  private final AuditIndexingService indexing;

  public AuditIndexerListener(AuditIndexingService indexing) {
    this.indexing = Objects.requireNonNull(indexing, "indexing");
  }

  public void on(AuditEvent event, InboxEnvelope<?> envelope) {
    try {
      indexing.index(record(event, envelope));
    } catch (AuditIndexUnavailableException e) {
      throw new TransientInboxException("Chỉ mục kiểm toán chưa nhận ghi", e);
    }
  }

  static AuditRecord record(AuditEvent e, InboxEnvelope<?> env) {
    List<Target> targets = new ArrayList<>();
    if (e.getTargets() != null) {
      for (AuditTarget t : e.getTargets()) {
        targets.add(
            new Target(
                str(t.getObjectType()),
                str(t.getObjectId()),
                str(t.getColumn()),
                str(t.getDekPurpose()),
                t.getSelf()));
      }
    }
    Instant at = env.occurredAt() == null ? Instant.now() : env.occurredAt();
    return new AuditRecord(
        env.eventId(),
        env.tenantId(),
        at,
        env.actorId() == null ? null : env.actorId().value(),
        uuid(e.getActorAssignmentId()),
        str(e.getActorRole()),
        str(e.getAction()),
        str(e.getOutcome()),
        str(e.getObjectType()),
        str(e.getObjectId()),
        str(e.getPurpose()),
        e.getSystem(),
        env.traceparent(),
        targets);
  }

  private static String str(CharSequence s) {
    return s == null ? null : s.toString();
  }

  private static UUID uuid(Object v) {
    if (v == null) {
      return null;
    }
    return v instanceof UUID u ? u : UUID.fromString(v.toString());
  }
}
