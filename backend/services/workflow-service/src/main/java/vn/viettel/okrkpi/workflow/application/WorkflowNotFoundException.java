package vn.viettel.okrkpi.workflow.application;

import java.io.Serial;

/**
 * Không tìm thấy user task hoặc tiến trình, hoặc đối tượng nằm ngoài tenant của người gọi: 404 theo
 * {@code common.yaml} response {@code NotFound} (không lộ sự tồn tại).
 */
public class WorkflowNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public WorkflowNotFoundException() {
    super("WORKFLOW-NOT-FOUND", null, false, false);
  }
}
