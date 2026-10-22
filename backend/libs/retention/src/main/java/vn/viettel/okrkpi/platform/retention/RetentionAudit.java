package vn.viettel.okrkpi.platform.retention;

import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện {@code sys.audit.v1} cho mỗi lần lưu trữ, xoá, khôi phục theo từng tenant; ledger-service
 * (DV-09) ghi sự kiện kiểm toán vào sổ cái. Không thuộc tính nào chứa dữ liệu nghiệp vụ.
 */
public final class RetentionAudit {

  private final AuditLogger audit;
  private final ActorId jobActor;

  public RetentionAudit(AuditLogger audit, ActorId jobActor) {
    this.audit = audit;
    this.jobActor = jobActor;
  }

  void record(
      String action,
      UUID tenant,
      ActorId actor,
      String objectType,
      String objectId,
      Map<String, String> attrs) {
    AuditEvent.Builder b =
        AuditEvent.builder(AuditEventType.ADMIN_ACTION, actor == null ? jobActor : actor, action)
            .outcome(AuditOutcome.SUCCESS)
            .tenant(TenantId.of(tenant))
            .object(objectType, objectId);
    attrs.forEach(b::attribute);
    audit.record(b.build());
  }
}
