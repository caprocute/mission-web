package vn.viettel.okrkpi.dossier.application.specialcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.Treatment;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsServiceTest;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService.CaseView;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService.Page;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService.Verification;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseFactsSource;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRepository;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.dossier.domain.specialcase.AssignmentFact;
import vn.viettel.okrkpi.dossier.domain.specialcase.PersonPeriodFacts;
import vn.viettel.okrkpi.dossier.domain.specialcase.SpecialCaseDetectorTest;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị trường hợp đặc thù (CORE-06-05, BR-11-05). */
class SpecialCaseServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID PERSON = SpecialCaseDetectorTest.PERSON;
  static final UUID UNIT = UUID.randomUUID();
  static final UUID PARENT = UUID.randomUUID();
  static final UUID OTHER_UNIT = UUID.randomUUID();
  static final UUID OFFICER = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-12-20T03:00:00Z");
  static final PeriodSnapshot QUARTER =
      new PeriodSnapshot(
          PeriodId.of(PERIOD),
          TENANT,
          "2026-Q4",
          "Quý IV năm 2026",
          SpecialCaseDetectorTest.Q_START,
          SpecialCaseDetectorTest.Q_END,
          PeriodStatus.MO);

  private final FakeCases cases = new FakeCases();
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  private final List<AssignmentFact> assignments = new ArrayList<>();
  private PersonPeriodFacts.Supplement supplement = PersonPeriodFacts.Supplement.NONE;
  private final SpecialCaseFactsSource facts =
      (tenant, person, start, end, overlay) -> {
        Map<UUID, AssignmentFact> all = new LinkedHashMap<>();
        assignments.forEach(a -> all.put(a.id(), a));
        overlay.forEach(a -> all.put(a.id(), a));
        return new PersonPeriodFacts(person, start, end, List.copyOf(all.values()), supplement);
      };
  private OrgDirectoryApi directory = org;
  private final SpecialCaseService service =
      new SpecialCaseService(
          cases,
          (tenant, period) -> SpecialCaseDetectorTest.TABLE,
          facts,
          periods,
          () -> directory,
          MeetingProceedingsServiceTest.SEALER,
          new ObjectMapper(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.of(QUARTER));
    when(periods.findCurrent(TENANT)).thenReturn(Optional.of(QUARTER));
    when(periods.findCurrent(eq(TENANT), any())).thenReturn(Optional.of(QUARTER));
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  private void transferred(String day) {
    LocalDate d = LocalDate.parse(day);
    assignments.add(
        new AssignmentFact(
            UUID.randomUUID(), OTHER_UNIT, "CHINH", false, d.minusYears(5), d.minusDays(1)));
    assignments.add(new AssignmentFact(UUID.randomUUID(), UNIT, "CHINH", false, d, null));
  }

  @Test
  void phatHienGhiMotLanVaCapNhatKhiDuKienDoi() {
    transferred("2026-11-15");
    List<SpecialCaseRepository.SpecialCaseRow> rows =
        service.detect(TENANT, QUARTER, PERSON, List.of());
    assertThat(rows).hasSize(1);
    SpecialCaseRepository.SpecialCaseRow r = rows.get(0);
    assertThat(r.caseCode()).isEqualTo("DIEU_DONG_DUOI_2_THANG");
    assertThat(r.detectedBy()).isEqualTo("TU_DONG");
    assertThat(r.periodBucket()).isEqualTo(20264);
    assertThat(new String(r.detailCipher())).contains("donViMoi");
    assertThat(cases.inserts).isEqualTo(1);

    service.detect(TENANT, QUARTER, PERSON, List.of());
    assertThat(cases.inserts).isEqualTo(1);
    assertThat(cases.updates).isZero();

    assignments.clear();
    transferred("2026-10-20");
    List<SpecialCaseRepository.SpecialCaseRow> again =
        service.detect(TENANT, QUARTER, PERSON, List.of());
    assertThat(again)
        .extracting(SpecialCaseRepository.SpecialCaseRow::caseCode)
        .containsExactly("DIEU_DONG_DUOI_2_THANG", "DIEU_DONG_TU_2_THANG");
    assertThat(cases.updates).isZero();
  }

  @Test
  void dongTuPhatHienChuaXacNhanDuocCapNhatDongDaXacNhanGiuNguyen() {
    transferred("2026-11-15");
    service.detect(TENANT, QUARTER, PERSON, List.of());
    assignments.clear();
    transferred("2026-11-20");
    service.detect(TENANT, QUARTER, PERSON, List.of());
    assertThat(cases.updates).isEqualTo(1);
    assertThat(cases.all().get(0).effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 20));

    SpecialCaseRepository.SpecialCaseRow confirmed = cases.all().get(0);
    cases.put(
        new SpecialCaseRepository.SpecialCaseRow(
            confirmed.id(),
            confirmed.periodId(),
            confirmed.periodBucket(),
            confirmed.personId(),
            confirmed.caseCode(),
            confirmed.detectedBy(),
            confirmed.detailCipher(),
            confirmed.dekId(),
            confirmed.effect(),
            confirmed.effectiveFrom(),
            confirmed.effectiveTo(),
            confirmed.legalRef(),
            OFFICER,
            NOW,
            confirmed.rowVersion()));
    service.detect(TENANT, QUARTER, PERSON, List.of());
    assertThat(cases.updates).isEqualTo(1);
  }

  @Test
  void kyDaKhoaChiDocVaKhongCoChiTietThiKhongMaHoa() {
    transferred("2026-11-15");
    PeriodSnapshot locked =
        new PeriodSnapshot(
            QUARTER.id(),
            TENANT,
            QUARTER.code(),
            QUARTER.name(),
            QUARTER.startDate(),
            QUARTER.endDate(),
            PeriodStatus.DA_KHOA);
    assertThat(service.detect(TENANT, locked, PERSON, List.of())).isEmpty();
    assertThat(cases.inserts).isZero();

    supplement =
        new PersonPeriodFacts.Supplement(
            null, null, true, null, null, null, null, null, null, null);
    assignments.clear();
    SpecialCaseRepository.SpecialCaseRow r =
        service.detect(TENANT, QUARTER, PERSON, List.of()).get(0);
    assertThat(r.caseCode()).isEqualTo("DANG_XEM_XET_XAC_MINH");
    assertThat(r.detailCipher()).isNull();
    assertThat(r.dekId()).isNull();
  }

  @Test
  void taoHoiNghiDoiChieuNguoiDuocKiemDiemHoacMoiThanhPhan() {
    transferred("2026-11-15");
    service.meetingCreated(TENANT, meeting(PERSON));
    assertThat(cases.all()).hasSize(1);

    UUID member = UUID.randomUUID();
    service.meetingCreated(TENANT, meeting(null, member));
    assertThat(cases.all())
        .extracting(SpecialCaseRepository.SpecialCaseRow::personId)
        .contains(member);

    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.empty());
    service.meetingCreated(TENANT, meeting(UUID.randomUUID()));
    assertThat(cases.all()).hasSize(3);
  }

  @Test
  void suKienPhanCongTrongKyDoiChieuLaiNgoaiKyBoQua() {
    AssignmentFact old =
        new AssignmentFact(
            UUID.randomUUID(), OTHER_UNIT, "CHINH", false, LocalDate.of(2020, 1, 1), null);
    assignments.add(old);
    AssignmentFact ended =
        new AssignmentFact(
            old.id(),
            OTHER_UNIT,
            "CHINH",
            false,
            LocalDate.of(2020, 1, 1),
            LocalDate.of(2026, 11, 30));
    AssignmentFact next =
        new AssignmentFact(
            UUID.randomUUID(), UNIT, "CHINH", false, LocalDate.of(2026, 12, 1), null);
    service.assignmentChanged(TENANT, PERSON, ended);
    assertThat(cases.all())
        .extracting(SpecialCaseRepository.SpecialCaseRow::caseCode)
        .containsExactly("NGHI_HUU_THOI_VIEC");
    assignments.set(0, ended);
    service.assignmentChanged(TENANT, PERSON, next);
    assertThat(cases.all())
        .extracting(SpecialCaseRepository.SpecialCaseRow::caseCode)
        .contains("DIEU_DONG_DUOI_2_THANG");

    int before = cases.inserts;
    service.assignmentChanged(
        TENANT,
        PERSON,
        new AssignmentFact(
            UUID.randomUUID(), UNIT, "KIEM_NHIEM", false, LocalDate.of(2027, 2, 1), null));
    assertThat(cases.inserts).isEqualTo(before);
  }

  @Test
  void congMod04TraHeQuaAnhHuongLapHoSo() {
    transferred("2026-11-15");
    service.detect(TENANT, QUARTER, PERSON, List.of());
    UUID sick = UUID.randomUUID();
    supplement =
        new PersonPeriodFacts.Supplement(47, null, true, null, null, null, null, null, null, null);
    assignments.clear();
    service.detect(TENANT, QUARTER, sick, List.of());
    List<AppraisalSpecialCases.SpecialCase> found = service.forPeriod(TENANT, PERIOD);
    assertThat(found)
        .extracting(
            AppraisalSpecialCases.SpecialCase::personId,
            AppraisalSpecialCases.SpecialCase::treatment)
        .containsExactlyInAnyOrder(
            tuple(PERSON, Treatment.DON_VI_CU_DANH_GIA), tuple(sick, Treatment.KHONG_XEP_LOAI));
    assertThat(found).allMatch(c -> c.appliesTo("DANG") && c.appliesTo("NHA_NUOC"));
    assertThat(service.forPeriod(TENANT, UUID.randomUUID())).isEmpty();
    assertThat(SpecialCaseService.treatment("CHO_KET_LUAN")).isNull();
  }

  @Test
  void danhSachTheoDonViGomNhomVaKiemPhamVi() {
    transferred("2026-11-15");
    service.detect(TENANT, QUARTER, PERSON, List.of());
    cases.units.put(PERSON, List.of(UNIT));
    Page page = service.list(TENANT, UNIT, null, null, null, null, 21);
    assertThat(page.items())
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.detail()).isNull();
              assertThat(v.handling()).isEqualTo("Cách xử lý §1");
            });
    assertThat(page.groups())
        .singleElement()
        .satisfies(
            g -> {
              assertThat(g.count()).isEqualTo(1);
              assertThat(g.handling()).isEqualTo("Cách xử lý §1");
            });

    when(org.isDescendant(TENANT, OrgTree.DANG, PARENT, UNIT)).thenReturn(true);
    assertThat(service.list(TENANT, PARENT, PERIOD, UNIT, null, null, 21).items()).hasSize(1);

    assertThatThrownBy(() -> service.list(TENANT, OTHER_UNIT, PERIOD, UNIT, null, null, 21))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service.list(TENANT, null, PERIOD, null, null, null, 21))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    directory = null;
    assertThatThrownBy(() -> service.list(TENANT, PARENT, PERIOD, UNIT, null, null, 21))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    when(periods.findCurrent(TENANT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.list(TENANT, UNIT, null, null, null, null, 21))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void danhSachTheoGoiHoSo() {
    transferred("2026-11-15");
    service.detect(TENANT, QUARTER, PERSON, List.of());
    UUID pkg = UUID.randomUUID();
    cases.units.put(PERSON, List.of(UNIT));
    cases.packages.put(pkg, new SpecialCaseRepository.PackageScope(PERIOD, 20264, UNIT, PERSON));
    assertThat(service.list(TENANT, UNIT, null, null, pkg, null, 21).items()).hasSize(1);
    assertThat(cases.lastFilter.personId()).isEqualTo(PERSON);
    assertThatThrownBy(() -> service.list(TENANT, UNIT, null, OTHER_UNIT, pkg, null, 21))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.list(TENANT, UNIT, null, null, UUID.randomUUID(), null, 21))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void xemChiTietGiaiMaTheoPhamViHoiNghiHoacPhanCong() {
    transferred("2026-11-15");
    UUID id = service.detect(TENANT, QUARTER, PERSON, List.of()).get(0).id();
    cases.units.put(PERSON, List.of(UNIT));
    CaseView view = service.get(TENANT, UNIT, id);
    assertThat(view.detail()).contains("\"soThangTaiDonViMoi\":1");

    cases.units.clear();
    when(org.assignmentsOf(TENANT, PERSON, SpecialCaseDetectorTest.Q_END))
        .thenReturn(
            List.of(
                new AssignmentView(
                    UUID.randomUUID(),
                    PERSON,
                    UNIT,
                    null,
                    UUID.randomUUID(),
                    "CHINH",
                    "DANG",
                    false,
                    LocalDate.of(2026, 11, 15),
                    null)));
    assertThat(service.get(TENANT, UNIT, id).row().id()).isEqualTo(id);
    assertThatThrownBy(() -> service.get(TENANT, OTHER_UNIT, id))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service.get(TENANT, null, id))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    directory = null;
    assertThatThrownBy(() -> service.get(TENANT, OTHER_UNIT, id))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.get(TENANT, UNIT, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void xacMinhGhiNguoiXacNhanDoiMaVaChiTiet() {
    transferred("2026-11-15");
    UUID id = service.detect(TENANT, QUARTER, PERSON, List.of()).get(0).id();
    cases.units.put(PERSON, List.of(UNIT));

    CaseView v =
        service.verify(
            TENANT,
            OFFICER,
            UNIT,
            id,
            0,
            new Verification(
                true, "DIEU_DONG_TU_2_THANG", Map.of("ghiChu", "Đã đối chiếu"), "Đúng"));
    assertThat(v.row().caseCode()).isEqualTo("DIEU_DONG_TU_2_THANG");
    assertThat(v.row().effect()).isEqualTo("XEP_LOAI_BINH_THUONG");
    assertThat(v.row().legalRef()).isEqualTo("PL 6 §2");
    assertThat(v.row().confirmedBy()).isEqualTo(OFFICER);
    assertThat(v.row().confirmedAt()).isEqualTo(NOW);
    assertThat(v.row().rowVersion()).isEqualTo(1);
    assertThat(v.detail()).contains("Đã đối chiếu");
    assertThat(v.handling()).isEqualTo("Cách xử lý §2");

    CaseView undone =
        service.verify(TENANT, OFFICER, UNIT, id, 1, new Verification(false, null, null, null));
    assertThat(undone.row().confirmedBy()).isNull();
    assertThat(undone.row().confirmedAt()).isNull();
    assertThat(undone.detail()).contains("Đã đối chiếu");
  }

  @Test
  void xacMinhTuChoiKhiSaiPhienBanDuLieuTrungMaHoacKyKhoa() {
    transferred("2026-11-15");
    UUID id = service.detect(TENANT, QUARTER, PERSON, List.of()).get(0).id();
    supplement =
        new PersonPeriodFacts.Supplement(
            null, null, true, null, null, null, null, null, null, null);
    service.detect(TENANT, QUARTER, PERSON, List.of());
    cases.units.put(PERSON, List.of(UNIT));

    assertThatThrownBy(
            () ->
                service.verify(
                    TENANT, OFFICER, UNIT, id, 5, new Verification(true, null, null, null)))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () ->
                service.verify(
                    TENANT,
                    OFFICER,
                    UNIT,
                    id,
                    0,
                    new Verification(null, "MA_LA", null, "x".repeat(2001))))
        .satisfies(
            t -> {
              assertThat(code(t)).isEqualTo("DOSSIER-422-VALIDATION");
              assertThat(((BusinessException) t).errors()).hasSize(3);
            });
    assertThatThrownBy(
            () ->
                service.verify(
                    TENANT,
                    OFFICER,
                    UNIT,
                    id,
                    0,
                    new Verification(true, "DANG_XEM_XET_XAC_MINH", null, null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-INVALID-STATE"));
    assertThat(
            service
                .verify(
                    TENANT,
                    OFFICER,
                    UNIT,
                    id,
                    0,
                    new Verification(true, "DIEU_DONG_DUOI_2_THANG", Map.of(), null))
                .row()
                .caseCode())
        .isEqualTo("DIEU_DONG_DUOI_2_THANG");

    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(
            () ->
                service.verify(
                    TENANT, OFFICER, UNIT, id, 1, new Verification(true, null, null, null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-PERIOD-LOCKED"));

    cases.failUpdates = true;
    reset(periods);
    assertThatThrownBy(
            () ->
                service.verify(
                    TENANT, OFFICER, UNIT, id, 1, new Verification(true, null, null, null)))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () ->
                service.verify(
                    TENANT,
                    OFFICER,
                    UNIT,
                    UUID.randomUUID(),
                    0,
                    new Verification(true, null, null, null)))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void khoaPhanVungTheoKy() {
    assertThat(SpecialCaseService.bucket(QUARTER)).isEqualTo(20264);
    PeriodSnapshot year =
        new PeriodSnapshot(
            PeriodId.of(UUID.randomUUID()),
            TENANT,
            "2026",
            "Năm 2026",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            PeriodStatus.MO);
    assertThat(SpecialCaseService.bucket(year)).isEqualTo(20260);
  }

  static MeetingRow meeting(UUID subject, UUID... members) {
    List<Participant> people = new ArrayList<>();
    people.add(new Participant(UUID.randomUUID(), Participant.CHU_TRI, null));
    for (UUID m : members) {
      people.add(new Participant(m, Participant.THANH_VIEN, null));
    }
    return new MeetingRow(
        UUID.randomUUID(),
        PERIOD,
        20264,
        UNIT,
        "KIEM_DIEM_NAM",
        NOW,
        null,
        null,
        people.get(0).personId(),
        people.get(0).personId(),
        MeetingStatus.DU_KIEN,
        null,
        subject,
        people,
        null,
        null,
        null,
        null,
        null,
        0);
  }

  /** Bộ nhớ thay {@code dossier.special_case}. */
  static final class FakeCases implements SpecialCaseRepository {

    private final Map<UUID, SpecialCaseRow> rows = new LinkedHashMap<>();
    final Map<UUID, List<UUID>> units = new LinkedHashMap<>();
    final Map<UUID, PackageScope> packages = new LinkedHashMap<>();
    Filter lastFilter;
    int inserts;
    int updates;
    boolean failUpdates;

    List<SpecialCaseRow> all() {
      return new ArrayList<>(rows.values());
    }

    void put(SpecialCaseRow row) {
      rows.put(row.id(), row);
    }

    @Override
    public void lockPerson(TenantId tenant, UUID periodId, UUID personId) {}

    @Override
    public List<SpecialCaseRow> listForPerson(
        TenantId tenant, int bucket, UUID periodId, UUID personId) {
      return rows.values().stream()
          .filter(r -> r.personId().equals(personId) && r.periodId().equals(periodId))
          .sorted(Comparator.comparing(SpecialCaseRow::caseCode))
          .toList();
    }

    @Override
    public List<SpecialCaseRow> listForPeriod(TenantId tenant, int bucket, UUID periodId) {
      return rows.values().stream().filter(r -> r.periodId().equals(periodId)).toList();
    }

    @Override
    public void insert(TenantId tenant, SpecialCaseRow row, UUID actor) {
      inserts++;
      rows.put(row.id(), row);
    }

    @Override
    public long update(TenantId tenant, SpecialCaseRow row, long expectedVersion, UUID actor) {
      SpecialCaseRow current = rows.get(row.id());
      if (failUpdates || current == null || current.rowVersion() != expectedVersion) {
        return 0;
      }
      updates++;
      rows.put(row.id(), row.withVersion(expectedVersion + 1));
      return expectedVersion + 1;
    }

    @Override
    public Optional<SpecialCaseRow> find(TenantId tenant, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<SpecialCaseRow> lock(TenantId tenant, UUID id) {
      return find(tenant, id);
    }

    @Override
    public List<SpecialCaseRow> list(TenantId tenant, Filter filter, UUID after, int limit) {
      lastFilter = filter;
      return rows.values().stream()
          .filter(r -> units.getOrDefault(r.personId(), List.of()).contains(filter.orgUnitId()))
          .filter(r -> filter.personId() == null || filter.personId().equals(r.personId()))
          .limit(limit)
          .toList();
    }

    @Override
    public List<GroupCount> groups(TenantId tenant, Filter filter) {
      Map<String, Long> counts = new LinkedHashMap<>();
      Map<String, String> refs = new LinkedHashMap<>();
      for (SpecialCaseRow r : list(tenant, filter, null, Integer.MAX_VALUE)) {
        counts.merge(r.caseCode(), 1L, Long::sum);
        refs.put(r.caseCode(), r.legalRef());
      }
      return counts.entrySet().stream()
          .map(e -> new GroupCount(e.getKey(), refs.get(e.getKey()), e.getValue()))
          .toList();
    }

    @Override
    public Optional<PackageScope> packageScope(TenantId tenant, UUID packageId) {
      return Optional.ofNullable(packages.get(packageId));
    }

    @Override
    public List<UUID> meetingUnitsOf(TenantId tenant, int bucket, UUID periodId, UUID personId) {
      return units.getOrDefault(personId, List.of());
    }
  }
}
