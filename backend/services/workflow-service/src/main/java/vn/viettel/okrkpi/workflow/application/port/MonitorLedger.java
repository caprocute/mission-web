package vn.viettel.okrkpi.workflow.application.port;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;

/**
 * Ghi sổ cái cho thao tác giám sát qua outbox trong cùng giao dịch (BS-01: "Mọi thao tác đều ghi sổ
 * cái"), kèm yêu cầu thông báo người nhận mới (EVT-21).
 */
public interface MonitorLedger {

  void reassigned(MonitoredTask task, UUID newAssigneeId, UUID actorId, int newRevision);

  void extended(MonitoredTask task, Instant newDueAt, UUID actorId, int newRevision);
}
