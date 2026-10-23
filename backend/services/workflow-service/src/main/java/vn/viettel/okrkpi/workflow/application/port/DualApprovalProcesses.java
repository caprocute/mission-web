package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval.Branch;

/** Cổng ra tới engine cho bước chờ ký số của mẫu duyệt kép (DV-06-12). */
public interface DualApprovalProcesses {

  /**
   * Một nhánh đang chờ chữ ký.
   *
   * @param executionId execution đang chờ message ký số
   * @param branch nhánh
   * @param approverId người đã duyệt nhánh, có thể {@code null}
   */
  record SignWait(String executionId, Branch branch, UUID approverId) {

    public SignWait {
      Objects.requireNonNull(executionId, "executionId");
      Objects.requireNonNull(branch, "branch");
    }
  }

  /** Các nhánh đang chờ ký của lượt duyệt kép trên đối tượng {@code objectId} của tenant. */
  List<SignWait> waitingSignatures(UUID tenantId, UUID objectId);

  /** Báo chữ ký đã hoàn tất cho nhánh đang chờ. */
  void deliverSigned(SignWait wait);
}
