package vn.viettel.okrkpi.workflow.infrastructure.listener;

import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.viettel.okrkpi.workflow.application.replica.AssignmentReplicaService;
import vn.viettel.okrkpi.workflow.domain.replica.AssignmentReplica;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Consumer EVT-15 {@code org.assignment.changed.v1} và phần phân công của {@code org.bootstrap.v1}
 * vào {@code org_replica.assignment_v} của workflow-service (DV-06-10), theo mẫu consumer bản sao
 * tổ chức của mission-core (CORE-00-03). Inbox {@code flowable.inbox_processed} (NEN-012) lo chống
 * trùng, thứ tự phiên bản theo {@code Assignment}, thử lại và thư chết.
 *
 * <p>Tên consumer cũng là nhóm consumer Kafka, nên phải khác tên của mission-core ({@code
 * org-replica.*}); trùng tên thì hai dịch vụ chia nhau thông điệp và mỗi bên thiếu một phần.
 */
@Component
public class OrgAssignmentListeners {

  static final String CONSUMER_ASSIGNMENT = "workflow.org-replica.assignment";
  static final String CONSUMER_BOOTSTRAP = "workflow.org-replica.bootstrap";

  private final AssignmentReplicaService service;

  public OrgAssignmentListeners(AssignmentReplicaService service) {
    this.service = service;
  }

  /** EVT-15: trạng thái đầy đủ của một phân công. */
  @InboxListener(
      topic = EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
      consumer = CONSUMER_ASSIGNMENT,
      aggregateType = "Assignment")
  public void onAssignment(InboxEnvelope<PhanCongDaThayDoi> e) {
    apply(e, e.payload());
  }

  /**
   * Nạp ban đầu (lệnh {@code org-republish} của SVC-04): chỉ áp thực thể phân công, cùng logic
   * upsert theo {@code source_version}. Đơn vị và nhân sự bỏ qua vì consumer EVT-14, EVT-16 của
   * workflow-service chưa có; thông điệp đánh dấu đầu, cuối loạt không có thực thể.
   */
  @InboxListener(topic = EventTopics.ORG_BOOTSTRAP_V1, consumer = CONSUMER_BOOTSTRAP)
  public void onBootstrap(InboxEnvelope<OrgBootstrap> e) {
    if (e.payload().getMarker() != null) {
      return;
    }
    Object entity = e.payload().getEntity();
    if (entity == null) {
      throw new PermanentInboxException("org.bootstrap.v1 thiếu thực thể");
    }
    if (entity instanceof PhanCongDaThayDoi assignment) {
      apply(e, assignment);
    }
  }

  private void apply(InboxEnvelope<?> e, PhanCongDaThayDoi payload) {
    AssignmentReplica assignment;
    try {
      assignment = assignment(payload);
    } catch (IllegalArgumentException invalid) {
      throw new PermanentInboxException(
          "ORG-REPLICA-INVALID",
          "Phân công " + payload.getAssignmentId() + " không hợp lệ: " + invalid.getMessage(),
          invalid);
    }
    service.apply(e.tenantId().value(), assignment, e.eventId());
  }

  static AssignmentReplica assignment(PhanCongDaThayDoi a) {
    return new AssignmentReplica(
        a.getAssignmentId(),
        a.getPersonId(),
        a.getUnitId(),
        a.getPositionId(),
        a.getJobTitleId(),
        a.getKind(),
        a.getIsPrimary(),
        a.getIsHead(),
        a.getValidFrom(),
        a.getValidTo(),
        a.getSourceVersion());
  }
}
