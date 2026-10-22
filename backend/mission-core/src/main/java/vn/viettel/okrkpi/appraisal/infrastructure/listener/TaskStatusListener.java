package vn.viettel.okrkpi.appraisal.infrastructure.listener;

import java.util.Objects;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.task.api.events.TrangThaiNhiemVuDoi;

/**
 * Nhiệm vụ kết thúc thì làm mới đầu vào tính điểm của người được giao (CORE-04-06, TL-01 "Giao tiếp
 * giữa module"). Sự kiện đi qua Event Publication Registry nên không mất khi pod dừng; khử trùng
 * lặp theo khoá sự kiện trong {@code appraisal.inbox_processed}.
 */
@Component
public class TaskStatusListener {

  private final ScoringInputService inputs;

  public TaskStatusListener(ScoringInputService inputs) {
    this.inputs = Objects.requireNonNull(inputs, "inputs");
  }

  @ApplicationModuleListener
  public void on(TrangThaiNhiemVuDoi event) {
    if (!event.isTerminal()) {
      return;
    }
    inputs.forAssignees(
        event.tenantId(),
        event.periodId(),
        ScoringInputService.eventKey(event.taskId(), event.newStatus(), event.occurredAt()),
        event.assigneeIds());
  }
}
