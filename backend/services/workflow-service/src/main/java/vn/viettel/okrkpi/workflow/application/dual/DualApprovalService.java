package vn.viettel.okrkpi.workflow.application.dual;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.workflow.application.port.DualApprovalProcesses;
import vn.viettel.okrkpi.workflow.application.port.DualApprovalProcesses.SignWait;
import vn.viettel.okrkpi.workflow.application.stage.StageProcessService.Outcome;

/**
 * Nhận EVT-18 cho bước ký số tuỳ chọn của từng nhánh duyệt kép (DV-06-12): chữ ký của người đã
 * duyệt nhánh nào thì nhánh đó đi tiếp tới cổng gộp. Sự kiện không khớp nhánh nào đang chờ thì bỏ
 * qua (sự kiện trùng, hoặc chữ ký của nghiệp vụ khác).
 */
@Service
public class DualApprovalService {

  private final DualApprovalProcesses processes;

  public DualApprovalService(DualApprovalProcesses processes) {
    this.processes = processes;
  }

  /**
   * Chữ ký trên đối tượng {@code objectId} đã hoàn tất.
   *
   * @param signerId người ký, có thể {@code null} (ký lô của hệ thống)
   */
  @Transactional
  public Outcome onSigned(UUID tenantId, UUID objectId, UUID signerId) {
    List<SignWait> waiting = processes.waitingSignatures(tenantId, objectId);
    if (waiting.isEmpty()) {
      return Outcome.NO_PROCESS;
    }
    List<SignWait> matched =
        signerId == null
            ? (waiting.size() == 1 ? waiting : List.of())
            : waiting.stream().filter(w -> signerId.equals(w.approverId())).toList();
    if (matched.isEmpty()) {
      return Outcome.IGNORED;
    }
    matched.forEach(processes::deliverSigned);
    return Outcome.SIGNALLED;
  }
}
