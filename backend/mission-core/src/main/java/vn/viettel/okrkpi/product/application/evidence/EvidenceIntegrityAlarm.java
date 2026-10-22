package vn.viettel.okrkpi.product.application.evidence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cảnh báo an ninh mức cao khi tệp minh chứng mất toàn vẹn lúc mở nghiệm thu (CORE-03-06,
 * MSG-07-13): EVT-21 ưu tiên cao vào {@code product.outbox_event} và sổ cái kiểm toán ({@code
 * sys.audit.v1}, kênh SOC). Chạy trong giao dịch riêng vì lần mở bị chặn trả lỗi và giao dịch của
 * yêu cầu (kể cả giao dịch của {@code Idempotency-Key}) quay lui.
 */
public class EvidenceIntegrityAlarm {

  static final String AUDIT_TAMPERED = "EVIDENCE_INTEGRITY_FAILED";

  private final EvidenceEvents events;
  private final AuditLogger audit;

  public EvidenceIntegrityAlarm(EvidenceEvents events, AuditLogger audit) {
    this.events = Objects.requireNonNull(events, "events");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  /** Dữ liệu cảnh báo: phiên bản, người mở, người nhận và các đối tượng không khớp. */
  public record Alert(
      TenantId tenant,
      UUID evidenceId,
      UUID versionId,
      int versionNo,
      long aggregateVersion,
      String taskCode,
      boolean e2ee,
      UUID actorId,
      List<UUID> recipientIds,
      List<UUID> objectIds) {}

  @Transactional(value = EvidenceLifecycleService.TX, propagation = Propagation.REQUIRES_NEW)
  public void raise(Alert a) {
    events.integrityFailed(
        a.tenant(),
        a.recipientIds(),
        a.taskCode(),
        a.evidenceId(),
        a.versionNo(),
        a.actorId(),
        a.aggregateVersion());
    audit.record(
        AuditEvent.builder(
                AuditEventType.SENSITIVE_DATA_ACCESS, ActorId.of(a.actorId()), AUDIT_TAMPERED)
            .outcome(AuditOutcome.DENIED)
            .tenant(a.tenant())
            .object("evidence_version", a.versionId().toString())
            .classification(a.e2ee() ? Classification.C3 : Classification.C2)
            .attribute("severity", "CAO")
            .attribute(
                "objectIds", String.join(",", a.objectIds().stream().map(UUID::toString).toList()))
            .build());
  }
}
