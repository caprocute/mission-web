package vn.viettel.okrkpi.replica.infrastructure.listener;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.replica.application.OrgReplicaService;
import vn.viettel.okrkpi.replica.domain.AppliedEvent;
import vn.viettel.okrkpi.replica.domain.AssignmentSnapshot;
import vn.viettel.okrkpi.replica.domain.EntityType;
import vn.viettel.okrkpi.replica.domain.OrgTreeCycleException;
import vn.viettel.okrkpi.replica.domain.PersonSnapshot;
import vn.viettel.okrkpi.replica.domain.UnitSnapshot;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Consumer EVT-14, EVT-15, EVT-16 và {@code org.bootstrap.v1} vào {@code org_replica} (CORE-00-03).
 * Inbox (NEN-012) lo chống trùng, thứ tự phiên bản, thử lại và DLQ; vòng trên cây tổ chức là lỗi
 * vĩnh viễn nên vào thẳng DLQ. Xuất {@code org_replica_lag_ms} theo tenant và loại thực thể.
 */
@Component
public class OrgReplicaListeners {

  static final String CONSUMER_UNIT = "org-replica.unit";
  static final String CONSUMER_ASSIGNMENT = "org-replica.assignment";
  static final String CONSUMER_PERSON = "org-replica.person";
  static final String CONSUMER_BOOTSTRAP = "org-replica.bootstrap";

  private final OrgReplicaService service;
  private final MeterRegistry meters;
  private final Map<String, AtomicLong> lags = new ConcurrentHashMap<>();

  public OrgReplicaListeners(OrgReplicaService service, ObjectProvider<MeterRegistry> meters) {
    this.service = service;
    this.meters = meters.getIfAvailable();
  }

  @InboxListener(
      topic = EventTopics.EVT_14_DON_VI_DA_THAY_DOI,
      consumer = CONSUMER_UNIT,
      aggregateType = "OrgUnit")
  public void onUnit(InboxEnvelope<DonViDaThayDoi> e) {
    apply(e, e.payload());
  }

  @InboxListener(
      topic = EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
      consumer = CONSUMER_ASSIGNMENT,
      aggregateType = "Assignment")
  public void onAssignment(InboxEnvelope<PhanCongDaThayDoi> e) {
    apply(e, e.payload());
  }

  @InboxListener(
      topic = EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI,
      consumer = CONSUMER_PERSON,
      aggregateType = "Person")
  public void onPerson(InboxEnvelope<NhanSuDaThayDoi> e) {
    apply(e, e.payload());
  }

  /**
   * Nạp ban đầu (TL-03 lệnh {@code org-republish}): cùng logic upsert theo {@code source_version}.
   */
  @InboxListener(topic = EventTopics.ORG_BOOTSTRAP_V1, consumer = CONSUMER_BOOTSTRAP)
  public void onBootstrap(InboxEnvelope<OrgBootstrap> e) {
    if (e.payload().getMarker() != null) {
      // Thông điệp đánh dấu đầu, cuối loạt (DV-04-08): không có thực thể để áp; đối chiếu số
      // lượng và băm bucket thuộc job đối soát DL-12.
      return;
    }
    if (!(e.payload().getEntity() instanceof SpecificRecord entity)) {
      throw new PermanentInboxException("org.bootstrap.v1 thiếu thực thể");
    }
    apply(e, entity);
  }

  private void apply(InboxEnvelope<?> e, SpecificRecord payload) {
    AppliedEvent event =
        new AppliedEvent(
            e.tenantId(), e.eventId(), e.occurredAt() == null ? Instant.now() : e.occurredAt());
    try {
      switch (payload) {
        case DonViDaThayDoi u -> record(e, EntityType.ORG_UNIT, service.applyUnit(unit(u), event));
        case PhanCongDaThayDoi a ->
            record(e, EntityType.ASSIGNMENT, service.applyAssignment(assignment(a), event));
        case NhanSuDaThayDoi p ->
            record(e, EntityType.PERSON, service.applyPerson(person(p), event));
        default ->
            throw new PermanentInboxException(
                "Thực thể không hỗ trợ: " + payload.getSchema().getFullName());
      }
    } catch (OrgTreeCycleException cycle) {
      throw new PermanentInboxException("ORG-REPLICA-CYCLE", cycle.getMessage(), cycle);
    }
  }

  static UnitSnapshot unit(DonViDaThayDoi u) {
    return new UnitSnapshot(
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

  static AssignmentSnapshot assignment(PhanCongDaThayDoi a) {
    return new AssignmentSnapshot(
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
        a.getSourceVersion(),
        a.getJobTitleName());
  }

  static PersonSnapshot person(NhanSuDaThayDoi p) {
    return new PersonSnapshot(
        p.getPersonId(),
        p.getDisplayName(),
        p.getIsPartyMember(),
        p.getStatus(),
        p.getSourceVersion());
  }

  private void record(InboxEnvelope<?> e, EntityType type, long lagMs) {
    if (meters == null) {
      return;
    }
    String tenant = e.tenantId().toString();
    lags.computeIfAbsent(
            tenant + '|' + type,
            k -> {
              AtomicLong holder = new AtomicLong();
              Gauge.builder("org_replica_lag_ms", holder, AtomicLong::get)
                  .description("Độ trễ áp dụng bản sao tổ chức (ms)")
                  .tag("tenant_id", tenant)
                  .tag("entity_type", type.name())
                  .register(meters);
              return holder;
            })
        .set(lagMs);
  }
}
