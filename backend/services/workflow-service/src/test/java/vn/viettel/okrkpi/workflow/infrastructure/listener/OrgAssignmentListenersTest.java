package vn.viettel.okrkpi.workflow.infrastructure.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.workflow.application.port.AssignmentReplicaStore;
import vn.viettel.okrkpi.workflow.application.replica.AssignmentReplicaService;
import vn.viettel.okrkpi.workflow.domain.replica.AssignmentReplica;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/** Ánh xạ EVT-15 và {@code org.bootstrap.v1} sang bản sao phân công (DV-06-10). */
class OrgAssignmentListenersTest {

  private static final UUID TENANT = UUID.randomUUID();

  private final List<AssignmentReplica> applied = new ArrayList<>();
  private final List<UUID> events = new ArrayList<>();
  private final AssignmentReplicaStore store =
      (tenant, a, event) -> {
        assertThat(tenant).isEqualTo(TENANT);
        applied.add(a);
        events.add(event);
        return true;
      };
  private final OrgAssignmentListeners listeners =
      new OrgAssignmentListeners(new AssignmentReplicaService(store));

  static PhanCongDaThayDoi evt15(LocalDate from, LocalDate to, long version) {
    return PhanCongDaThayDoi.newBuilder()
        .setAssignmentId(UUID.randomUUID())
        .setPersonId(UUID.randomUUID())
        .setUnitId(UUID.randomUUID())
        .setPositionId(null)
        .setJobTitleId(UUID.randomUUID())
        .setKind("DIEU_DONG")
        .setIsPrimary(false)
        .setValidFrom(from)
        .setValidTo(to)
        .setSourceVersion(version)
        .setAggregateVersion((int) version)
        .setIsHead(true)
        .build();
  }

  static <T extends SpecificRecord> InboxEnvelope<T> envelope(String topic, T payload) {
    return new InboxEnvelope<>(
        UUID.randomUUID(),
        topic,
        TENANT + ":" + UUID.randomUUID(),
        TenantId.of(TENANT),
        null,
        1,
        payload.getSchema().getName(),
        Classification.C1,
        null,
        null,
        null,
        Instant.now(),
        0,
        0,
        payload);
  }

  static OrgBootstrap bootstrap(Object entity, String marker) {
    return OrgBootstrap.newBuilder()
        .setBatchId(UUID.randomUUID())
        .setSeq(1)
        .setIsLast(false)
        .setEntity(entity)
        .setMarker(marker)
        .build();
  }

  @Test
  void evt15ChepDuTrangThaiPhanCong() {
    PhanCongDaThayDoi p = evt15(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), 9);
    InboxEnvelope<PhanCongDaThayDoi> e = envelope(EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI, p);
    listeners.onAssignment(e);
    AssignmentReplica a = applied.getFirst();
    assertThat(a.id()).isEqualTo(p.getAssignmentId());
    assertThat(a.personId()).isEqualTo(p.getPersonId());
    assertThat(a.orgUnitId()).isEqualTo(p.getUnitId());
    assertThat(a.jobTitleId()).isEqualTo(p.getJobTitleId());
    assertThat(a.kind()).isEqualTo("DIEU_DONG");
    assertThat(a.branch()).isEqualTo("PHU");
    assertThat(a.head()).isTrue();
    assertThat(a.validTo()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(a.sourceVersion()).isEqualTo(9);
    assertThat(events).containsExactly(e.eventId());
  }

  @Test
  void phanCongSaiLaLoiVinhVienVaoThuChet() {
    PhanCongDaThayDoi p = evt15(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), 1);
    assertThatThrownBy(
            () -> listeners.onAssignment(envelope(EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI, p)))
        .isInstanceOf(PermanentInboxException.class);
    assertThat(applied).isEmpty();
  }

  @Test
  void bootstrapChiApPhanCongBoQuaDanhDauVaThucTheKhac() {
    listeners.onBootstrap(envelope(EventTopics.ORG_BOOTSTRAP_V1, bootstrap(null, "BAT_DAU")));
    DonViDaThayDoi unit =
        DonViDaThayDoi.newBuilder()
            .setUnitId(UUID.randomUUID())
            .setVersionId(UUID.randomUUID())
            .setSourceVersion(1)
            .setCode("DV")
            .setName("Đơn vị")
            .setUnitType("CHI_BO")
            .setLevel("XA")
            .setValidFrom(LocalDate.of(2026, 1, 1))
            .setAggregateVersion(1)
            .build();
    listeners.onBootstrap(envelope(EventTopics.ORG_BOOTSTRAP_V1, bootstrap(unit, null)));
    assertThat(applied).isEmpty();
    PhanCongDaThayDoi p = evt15(LocalDate.of(2026, 1, 1), null, 4);
    listeners.onBootstrap(envelope(EventTopics.ORG_BOOTSTRAP_V1, bootstrap(p, null)));
    assertThat(applied)
        .singleElement()
        .extracting(AssignmentReplica::id)
        .isEqualTo(p.getAssignmentId());
    assertThatThrownBy(
            () ->
                listeners.onBootstrap(
                    envelope(EventTopics.ORG_BOOTSTRAP_V1, bootstrap(null, null))))
        .isInstanceOf(PermanentInboxException.class);
  }

  @Test
  void tenConsumerKhacMissionCoreVaKiemThuTuTheoPhanCong() throws Exception {
    InboxListener evt15 =
        OrgAssignmentListeners.class
            .getMethod("onAssignment", InboxEnvelope.class)
            .getAnnotation(InboxListener.class);
    assertThat(evt15.topic()).isEqualTo("org.assignment.changed.v1");
    assertThat(evt15.aggregateType()).isEqualTo("Assignment");
    assertThat(evt15.consumer()).startsWith("workflow.");
    InboxListener boot =
        OrgAssignmentListeners.class
            .getMethod("onBootstrap", InboxEnvelope.class)
            .getAnnotation(InboxListener.class);
    assertThat(boot.topic()).isEqualTo("org.bootstrap.v1");
    assertThat(boot.consumer()).startsWith("workflow.").isNotEqualTo(evt15.consumer());
  }
}
