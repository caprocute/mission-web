package vn.viettel.okrkpi.workflow.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.workflow.application.port.ApprovalAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.CurrentApprover;
import vn.viettel.okrkpi.workflow.application.port.HistoryReader;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.ProcessHistory;

/**
 * Lịch sử duyệt theo khoá nghiệp vụ ({@code getWorkflowHistory}, SCR-0203 trường 6, SCR-1506): đọc
 * lịch sử engine mức {@code audit}, kiểm quyền đọc đối tượng qua OPA với danh sách người tham gia.
 */
@Service
public class HistoryQueryService {

  private final HistoryReader reader;
  private final CurrentApprover currentApprover;
  private final ApprovalAccessPolicy policy;

  public HistoryQueryService(
      HistoryReader reader, CurrentApprover currentApprover, ApprovalAccessPolicy policy) {
    this.reader = reader;
    this.currentApprover = currentApprover;
    this.policy = policy;
  }

  /**
   * Lịch sử tiến trình gần nhất của đối tượng.
   *
   * @throws WorkflowNotFoundException khoá sai dạng, không có tiến trình, hoặc thuộc tenant khác
   */
  @Transactional(readOnly = true, timeout = 5)
  public ProcessHistory get(String businessKey) {
    Approver approver = currentApprover.current();
    BusinessKey key = BusinessKey.parse(businessKey).orElseThrow(WorkflowNotFoundException::new);
    ProcessHistory history =
        reader.find(approver.tenantId(), key).orElseThrow(WorkflowNotFoundException::new);
    List<String> participants = history.participants().stream().map(UUID::toString).toList();
    AuthzResource.Builder resource =
        AuthzResource.builder()
            .type(key.type().toLowerCase(java.util.Locale.ROOT))
            .id(key.id().toString())
            .tenantId(approver.tenantId().toString())
            .ownerUnitId(approver.unitId() == null ? null : approver.unitId().toString())
            .assigneeIds(participants)
            .classification("C1");
    if (history.initiatorId() != null) {
      resource
          .ownerIds(List.of(history.initiatorId().toString()))
          .participants(Map.of("initiator", List.of(history.initiatorId().toString())));
    }
    policy.checkView(resource.build());
    return history;
  }
}
