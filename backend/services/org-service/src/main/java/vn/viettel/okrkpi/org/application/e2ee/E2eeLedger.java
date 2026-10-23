package vn.viettel.okrkpi.org.application.e2ee;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.OutboxEvent;
import vn.viettel.okrkpi.org.application.port.OutboxPort;
import vn.viettel.okrkpi.org.domain.UuidV7;

/**
 * Ghi sổ cái mọi thay đổi danh bạ khoá và phục hồi tổ chức (ADR-27, TL-04 SEC-25): sự kiện {@code sys.audit.v1}
 * vào outbox trong cùng giao dịch, SVC-09 nối vào sổ cái. Sự kiện chỉ mang mã đối tượng, không mang khoá hay chữ ký.
 */
@Component
public class E2eeLedger {

  static final String AUDIT_TOPIC = "sys.audit.v1";

  private final OutboxPort outbox;
  private final Clock clock;

  public E2eeLedger(OutboxPort outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  void record(Actor actor, String action, String objectType, UUID objectId, String aggregateType, int version) {
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("action", action);
    audit.put("objectType", objectType);
    audit.put("objectId", objectId.toString());
    audit.put("outcome", "THANH_CONG");
    audit.put("purpose", null);
    audit.put("actorAssignmentId", null);
    audit.put("actorRole", actor.roles().isEmpty() ? null : String.join(",", actor.roles().stream().sorted().toList()));
    audit.put("system", false);
    audit.put("targets", null);
    outbox.append(new OutboxEvent(UuidV7.next(clock), AUDIT_TOPIC, "AuditEvent", aggregateType, objectId, version,
        actor.tenantId(), actor.actorId(), clock.instant(), actor.traceparent(), "C1", audit));
  }
}
