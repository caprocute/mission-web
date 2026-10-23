package vn.viettel.okrkpi.workflow.infrastructure.listener;

import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.viettel.okrkpi.workflow.application.replica.AssignmentReplicaService;
import vn.viettel.okrkpi.workflow.application.replica.OrgReplicaService;
import vn.viettel.okrkpi.workflow.domain.replica.AssignmentReplica;
import vn.viettel.okrkpi.workflow.domain.replica.OrgTreeCycleException;
import vn.viettel.okrkpi.workflow.domain.replica.OrgUnitReplica;
import vn.viettel.okrkpi.workflow.domain.replica.PersonReplica;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Consumer bản sao tổ chức của workflow-service (DV-06-10): EVT-14 {@code org.unit.changed.v1} vào
 * {@code org_replica.org_unit_v}, EVT-15 {@code org.assignment.changed.v1} vào {@code
 * org_replica.assignment_v}, EVT-16 {@code org.person.changed.v1} vào {@code org_replica.person_v}
 * và nạp ban đầu {@code org.bootstrap.v1} cho cả ba loại thực thể, theo mẫu consumer bản sao tổ
 * chức của mission-core (CORE-00-03). Inbox {@code flowable.inbox_processed} (NEN-012) lo chống
 * trùng, thứ tự phiên bản theo thực thể gốc, thử lại và thư chết; dữ liệu sai và vòng trên cây tổ
 * chức là lỗi vĩnh viễn, vào thẳng thư chết.
 *
 * <p>Tên consumer cũng là nhóm consumer Kafka, nên phải khác tên của mission-core ({@code
 * org-replica.*}); trùng tên thì hai dịch vụ chia nhau thông điệp và mỗi bên thiếu một phần.
 */
@Component
public class OrgReplicaListeners {

  static final String CONSUMER_UNIT = "workflow.org-replica.unit";
  static final String CONSUMER_ASSIGNMENT = "workflow.org-replica.assignment";
  static final String CONSUMER_PERSON = "workflow.org-replica.person";
  static final String CONSUMER_BOOTSTRAP = "workflow.org-replica.bootstrap";

  private final AssignmentReplicaService assignments;
  private final OrgReplicaService org;

  public OrgReplicaListeners(AssignmentReplicaService assignments, OrgReplicaService org) {
    this.assignments = assignments;
    this.org = org;
  }

  /** EVT-14: trạng thái đầy đủ của một lát hiệu lực đơn vị. */
  @InboxListener(
      topic = EventTopics.EVT_14_DON_VI_DA_THAY_DOI,
      consumer = CONSUMER_UNIT,
      aggregateType = "OrgUnit")
  public void onUnit(InboxEnvelope<DonViDaThayDoi> e) {
    apply(e, e.payload());
  }

  /** EVT-15: trạng thái đầy đủ của một phân công. */
  @InboxListener(
      topic = EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
      consumer = CONSUMER_ASSIGNMENT,
      aggregateType = "Assignment")
  public void onAssignment(InboxEnvelope<PhanCongDaThayDoi> e) {
    apply(e, e.payload());
  }

  /** EVT-16: trạng thái đầy đủ của một nhân sự. */
  @InboxListener(
      topic = EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI,
      consumer = CONSUMER_PERSON,
      aggregateType = "Person")
  public void onPerson(InboxEnvelope<NhanSuDaThayDoi> e) {
    apply(e, e.payload());
  }

  /**
   * Nạp ban đầu (lệnh {@code org-republish} của SVC-04): áp đơn vị, phân công và nhân sự bằng cùng
   * logic upsert theo {@code source_version}; thông điệp đánh dấu đầu, cuối loạt không có thực thể.
   */
  @InboxListener(topic = EventTopics.ORG_BOOTSTRAP_V1, consumer = CONSUMER_BOOTSTRAP)
  public void onBootstrap(InboxEnvelope<OrgBootstrap> e) {
    if (e.payload().getMarker() != null) {
      return;
    }
    Object entity = e.payload().getEntity();
    switch (entity) {
      case null -> throw new PermanentInboxException("org.bootstrap.v1 thiếu thực thể");
      case DonViDaThayDoi unit -> apply(e, unit);
      case PhanCongDaThayDoi assignment -> apply(e, assignment);
      case NhanSuDaThayDoi person -> apply(e, person);
      default ->
          throw new PermanentInboxException(
              "Thực thể nạp ban đầu không hỗ trợ: " + entity.getClass().getSimpleName());
    }
  }

  private void apply(InboxEnvelope<?> e, DonViDaThayDoi payload) {
    OrgUnitReplica unit;
    try {
      unit = unit(payload);
    } catch (IllegalArgumentException invalid) {
      throw invalid("Đơn vị " + payload.getUnitId(), invalid);
    }
    try {
      org.applyUnit(e.tenantId().value(), unit, e.eventId());
    } catch (OrgTreeCycleException cycle) {
      throw new PermanentInboxException("ORG-REPLICA-CYCLE", cycle.getMessage(), cycle);
    }
  }

  private void apply(InboxEnvelope<?> e, PhanCongDaThayDoi payload) {
    AssignmentReplica assignment;
    try {
      assignment = assignment(payload);
    } catch (IllegalArgumentException invalid) {
      throw invalid("Phân công " + payload.getAssignmentId(), invalid);
    }
    assignments.apply(e.tenantId().value(), assignment, e.eventId());
  }

  private void apply(InboxEnvelope<?> e, NhanSuDaThayDoi payload) {
    PersonReplica person;
    try {
      person = person(payload);
    } catch (IllegalArgumentException invalid) {
      throw invalid("Nhân sự " + payload.getPersonId(), invalid);
    }
    org.applyPerson(e.tenantId().value(), person, e.eventId());
  }

  private static PermanentInboxException invalid(String what, IllegalArgumentException cause) {
    return new PermanentInboxException(
        "ORG-REPLICA-INVALID", what + " không hợp lệ: " + cause.getMessage(), cause);
  }

  static OrgUnitReplica unit(DonViDaThayDoi u) {
    return new OrgUnitReplica(
        u.getUnitId(),
        u.getValidFrom(),
        u.getValidTo(),
        u.getCode(),
        u.getName(),
        u.getUnitType(),
        u.getPartyParentId(),
        u.getAdminParentId(),
        u.getSubjectGroupId(),
        u.getSourceVersion());
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

  static PersonReplica person(NhanSuDaThayDoi p) {
    return new PersonReplica(
        p.getPersonId(),
        p.getDisplayName(),
        p.getIsPartyMember(),
        p.getStatus(),
        p.getSourceVersion());
  }
}
