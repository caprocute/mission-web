package vn.viettel.okrkpi.workflow.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;

/**
 * Kiểm tách vai trò của người nhận mới qua OPA (PL 4 V.1, BR-02-08): người nhận đặt vào vị trí
 * người xử lý bước rồi hỏi OPA như khi chính người đó duyệt; vi phạm thì ném {@code
 * BusinessException} 403.
 */
public interface AssigneeSeparation {

  void check(MonitoredTask task, UUID newAssigneeId);
}
