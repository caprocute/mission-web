package vn.viettel.okrkpi.task.application.lifecycle.port;

import vn.viettel.okrkpi.task.api.TaskStatusChangedEvent;

/** Phát EVT-06 {@code task.task.statuschanged.v1} qua outbox trong giao dịch của lệnh. */
@FunctionalInterface
public interface TaskStatusEvents {

  void statusChanged(TaskStatusChangedEvent event);
}
