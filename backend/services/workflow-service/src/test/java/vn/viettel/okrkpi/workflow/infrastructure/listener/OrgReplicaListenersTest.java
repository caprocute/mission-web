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
import vn.viettel.okrkpi.workflow.application.port.OrgUnitReplicaStore;
import vn.viettel.okrkpi.workflow.application.port.PersonReplicaStore;
import vn.viettel.okrkpi.workflow.application.replica.AssignmentReplicaService;
import vn.viettel.okrkpi.workflow.application.replica.OrgReplicaService;
import vn.viettel.okrkpi.workflow.domain.replica.AssignmentReplica;
import vn.viettel.okrkpi.workflow.domain.replica.OrgTree;
import vn.viettel.okrkpi.workflow.domain.replica.OrgUnitReplica;
import vn.viettel.okrkpi.workflow.domain.replica.PersonReplica;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/** Ánh xạ EVT-14, EVT-15, EVT-16 và {@code org.bootstrap.v1} sang bản sao tổ chức (DV-06-10). */
class OrgReplicaListenersTest {

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
  private final List<OrgUnitReplica> units = new ArrayList<>();
  private final List<PersonReplica> persons = new ArrayList<>();

  /** Vòng giả: đi lên từ {@code from} gặp {@code target} khi {@link #cycleFrom} khớp. */
  private UUID cycleFrom;

  private final OrgUnitReplicaStore unitStore =
      new OrgUnitReplicaStore() {
        @Override
        public boolean upsert(UUID tenant, OrgUnitReplica unit, UUID event) {
          assertThat(tenant).isEqualTo(TENANT);
          units.add(unit);
          events.add(event);
          return true;
        }

        @Override
        public boolean reachesUpward(UUID tenant, OrgTree tree, UUID from, UUID target) {
          return from.equals(cycleFrom);
        }
      };
  private final PersonReplicaStore personStore =
      (tenant, person, event) -> {
        assertThat(tenant).isEqualTo(TENANT);
        persons.add(person);
        events.add(event);
        return true;
      };
  private final OrgReplicaListeners listeners =
      new OrgReplicaListeners(
          new AssignmentReplicaService(store), new OrgReplicaService(unitStore, personStore));

  static DonViDaThayDoi evt14(UUID party, UUID admin, LocalDate to, String code) {
    return DonViDaThayDoi.newBuilder()
        .setUnitId(UUID.randomUUID())
        .setVersionId(UUID.randomUUID())
        .setSourceVersion(7)
        .setCode(code)
        .setName("Chi bộ Văn phòng")
        .setShortName("CB VP")
        .setUnitType("CHI_BO")
        .setSubjectGroupId(UUID.randomUUID())
        .setLevel("XA")
        .setFlows(List.of("DANG"))
        .setValidFrom(LocalDate.of(2026, 1, 1))
        .setValidTo(to)
        .setAggregateVersion(7)
        .setPartyParentId(party)
        .setAdminParentId(admin)
        .build();
  }

  static NhanSuDaThayDoi evt16(String name, String status) {
    return NhanSuDaThayDoi.newBuilder()
        .setPersonId(UUID.randomUUID())
        .setDisplayName(name)
        .setIsPartyMember(true)
        .setStatus(status)
        .setSourceVersion(3)
        .setAggregateVersion(3)
        .build();
  }

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
  void bootstrapApDonViNhanSuPhanCongBoQuaDanhDau() {
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
    assertThat(units).singleElement().extracting(OrgUnitReplica::id).isEqualTo(unit.getUnitId());
    NhanSuDaThayDoi person = evt16("Nguyễn Văn A", "DANG_CONG_TAC");
    listeners.onBootstrap(envelope(EventTopics.ORG_BOOTSTRAP_V1, bootstrap(person, null)));
    assertThat(persons)
        .singleElement()
        .extracting(PersonReplica::id)
        .isEqualTo(person.getPersonId());
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
        OrgReplicaListeners.class
            .getMethod("onAssignment", InboxEnvelope.class)
            .getAnnotation(InboxListener.class);
    assertThat(evt15.topic()).isEqualTo("org.assignment.changed.v1");
    assertThat(evt15.aggregateType()).isEqualTo("Assignment");
    assertThat(evt15.consumer()).startsWith("workflow.");
    InboxListener boot =
        OrgReplicaListeners.class
            .getMethod("onBootstrap", InboxEnvelope.class)
            .getAnnotation(InboxListener.class);
    assertThat(boot.topic()).isEqualTo("org.bootstrap.v1");
    assertThat(boot.consumer()).startsWith("workflow.").isNotEqualTo(evt15.consumer());
  }

  @Test
  void evt14ChepDuTrangThaiDonVi() {
    UUID party = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    DonViDaThayDoi u = evt14(party, admin, LocalDate.of(2027, 1, 1), "CB-VP");
    InboxEnvelope<DonViDaThayDoi> e = envelope(EventTopics.EVT_14_DON_VI_DA_THAY_DOI, u);
    listeners.onUnit(e);
    OrgUnitReplica unit = units.getFirst();
    assertThat(unit.id()).isEqualTo(u.getUnitId());
    assertThat(unit.code()).isEqualTo("CB-VP");
    assertThat(unit.name()).isEqualTo("Chi bộ Văn phòng");
    assertThat(unit.unitType()).isEqualTo("CHI_BO");
    assertThat(unit.partyParentId()).isEqualTo(party);
    assertThat(unit.adminParentId()).isEqualTo(admin);
    assertThat(unit.subjectGroupId()).isEqualTo(u.getSubjectGroupId());
    assertThat(unit.validFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(unit.validTo()).isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(unit.sourceVersion()).isEqualTo(7);
    assertThat(events).containsExactly(e.eventId());
  }

  @Test
  void donViSaiHoacVongCayLaLoiVinhVien() {
    DonViDaThayDoi blank = evt14(null, null, null, " ");
    assertThatThrownBy(
            () -> listeners.onUnit(envelope(EventTopics.EVT_14_DON_VI_DA_THAY_DOI, blank)))
        .isInstanceOf(PermanentInboxException.class)
        .hasMessageContaining("không hợp lệ");
    assertThat(units).isEmpty();

    UUID parent = UUID.randomUUID();
    cycleFrom = parent;
    DonViDaThayDoi cyclic = evt14(parent, null, null, "CB-01");
    assertThatThrownBy(
            () -> listeners.onUnit(envelope(EventTopics.EVT_14_DON_VI_DA_THAY_DOI, cyclic)))
        .isInstanceOfSatisfying(
            PermanentInboxException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo("ORG-REPLICA-CYCLE"));
  }

  @Test
  void evt16ChepNhanSuNhanSuSaiVaoThuChet() {
    NhanSuDaThayDoi p = evt16("Trần Thị B", "NGHI_HUU");
    listeners.onPerson(envelope(EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI, p));
    PersonReplica person = persons.getFirst();
    assertThat(person.id()).isEqualTo(p.getPersonId());
    assertThat(person.fullName()).isEqualTo("Trần Thị B");
    assertThat(person.partyMember()).isTrue();
    assertThat(person.employmentStatus()).isEqualTo("NGHI_HUU");
    assertThat(person.sourceVersion()).isEqualTo(3);

    NhanSuDaThayDoi bad = evt16("Lê Văn C", "TINH_TRANG_QUA_DAI_17");
    assertThatThrownBy(
            () -> listeners.onPerson(envelope(EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI, bad)))
        .isInstanceOf(PermanentInboxException.class);
    assertThat(persons).hasSize(1);
  }

  @Test
  void bootstrapThucTheLaVaoThuChet() {
    assertThatThrownBy(
            () ->
                listeners.onBootstrap(
                    envelope(EventTopics.ORG_BOOTSTRAP_V1, bootstrap("khong-ho-tro", null))))
        .isInstanceOf(PermanentInboxException.class);
  }

  @Test
  void consumerDonViNhanSuCoTienToWorkflowVaKiemThuTuTheoThucThe() throws Exception {
    InboxListener unit =
        OrgReplicaListeners.class
            .getMethod("onUnit", InboxEnvelope.class)
            .getAnnotation(InboxListener.class);
    assertThat(unit.topic()).isEqualTo("org.unit.changed.v1");
    assertThat(unit.aggregateType()).isEqualTo("OrgUnit");
    assertThat(unit.consumer()).isEqualTo("workflow.org-replica.unit");
    InboxListener person =
        OrgReplicaListeners.class
            .getMethod("onPerson", InboxEnvelope.class)
            .getAnnotation(InboxListener.class);
    assertThat(person.topic()).isEqualTo("org.person.changed.v1");
    assertThat(person.aggregateType()).isEqualTo("Person");
    assertThat(person.consumer()).isEqualTo("workflow.org-replica.person");
  }
}
