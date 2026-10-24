package vn.viettel.okrkpi.product.infrastructure.listener;

import java.util.Objects;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService;
import vn.viettel.okrkpi.task.api.events.NhiemVuDaGiao;

/**
 * Nhiệm vụ vừa giao thì khai báo sản phẩm đầu ra ghi lúc soạn (QT-05 → QT-07, SCR-0701). Sự kiện đi
 * qua Event Publication Registry nên không mất khi pod dừng; nhiệm vụ đã có sản phẩm thì bỏ qua.
 */
@Component
public class TaskAssignedListener {

  private final EvidenceSubmissionService submissions;

  public TaskAssignedListener(EvidenceSubmissionService submissions) {
    this.submissions = Objects.requireNonNull(submissions, "submissions");
  }

  @ApplicationModuleListener
  public void on(NhiemVuDaGiao event) {
    submissions.declareAssigned(
        event.tenantId(),
        event.taskId(),
        event.productName(),
        event.unit(),
        event.quantity(),
        event.assignerId());
  }
}
