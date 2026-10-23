package vn.viettel.okrkpi.analytics.infrastructure.export;

import java.util.List;
import vn.viettel.okrkpi.analytics.application.ExportPorts;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Việc xuất của SVC-15 lên kênh kiểm toán {@code sys.audit.v1} (loại xuất dữ liệu). */
final class AuditExports implements ExportPorts.Audit {

  private final AuditLogger audit;

  AuditExports(AuditLogger audit) {
    this.audit = audit;
  }

  @Override
  public void exported(
      Viewer viewer,
      String what,
      String objectRef,
      List<String> formats,
      String jobId,
      boolean ok) {
    int sep = objectRef.indexOf(':');
    audit.record(
        AuditEvent.builder(
                AuditEventType.DATA_EXPORT,
                ActorId.of(viewer.userId()),
                "XUAT_" + (what.startsWith("chuỗi") ? "CHUOI_BANG_CHUNG" : "BAO_CAO_PHAN_TICH"))
            .tenant(TenantId.of(viewer.tenant()))
            .outcome(ok ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
            .object(objectRef.substring(0, sep), objectRef.substring(sep + 1))
            .attribute("formats", String.join(",", formats))
            .attribute("jobId", jobId)
            .build());
  }
}
