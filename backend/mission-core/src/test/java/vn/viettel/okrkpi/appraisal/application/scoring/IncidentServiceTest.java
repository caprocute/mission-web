package vn.viettel.okrkpi.appraisal.application.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService.IncidentView;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService.NewIncident;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedProfile;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class IncidentServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID PERIOD = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID PERSON = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  private final Repo repo = new Repo();
  private final PeriodApi periods = mock(PeriodApi.class);
  private final PolicyQueryApi policies = mock(PolicyQueryApi.class);
  private final IncidentService service =
      new IncidentService(repo, new PlainSealer(), periods, policies, Clock.systemUtc());

  @Test
  void ghiSuViecMaHoaTomTatVaDocLaiTrongPhamVi() {
    repo.profile = new IncidentRepository.PersonProfile(PERIOD, "DANG", null, UNIT);
    IncidentView v = service.newIncident(TENANT, ACTOR, cmd(PERIOD, "KY_LUAT", "Khiển trách"));

    assertThat(v.summary()).isEqualTo("Khiển trách");
    assertThat(v.row().orgUnitId()).isEqualTo(UNIT);
    assertThat(v.row().createdBy()).isEqualTo(ACTOR);
    assertThat(new String(repo.rows.get(v.row().id()).summary().cipher(), StandardCharsets.UTF_8))
        .isEqualTo("enc:Khiển trách");
    verify(periods).assertWritable(TENANT, PeriodId.of(PERIOD));

    assertThat(service.find(TENANT, UNIT, v.row().id()).summary()).isEqualTo("Khiển trách");
    assertThatThrownBy(() -> service.find(TENANT, UUID.randomUUID(), v.row().id()))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.find(TENANT, null, v.row().id()))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(service.list(TENANT, UNIT, PERSON, null, null, 10)).hasSize(1);
    assertThat(service.list(TENANT, null, PERSON, null, null, 10)).isEmpty();
  }

  @Test
  void loaiNgoaiDanhMucCuaHoSoBi422() {
    repo.profile = new IncidentRepository.PersonProfile(PERIOD, "DANG", null, UNIT);
    when(policies.customerProfile(TENANT, PeriodId.of(PERIOD), "DANG", null, UNIT))
        .thenReturn(
            Optional.of(
                new ResolvedProfile(
                    UUID.randomUUID(),
                    "mau",
                    UUID.randomUUID(),
                    "0.1.0",
                    "h",
                    Map.of(
                        IncidentService.CATALOG_COMPONENT,
                        List.of(Map.of("ma", "KY_LUAT"), "VI_PHAM")),
                    null)));

    assertThatThrownBy(() -> service.newIncident(TENANT, ACTOR, cmd(PERIOD, "KHAC", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("APPRAISAL-422-INCIDENT-TYPE"));
    assertThat(service.newIncident(TENANT, ACTOR, cmd(PERIOD, "VI_PHAM", null)).summary()).isNull();
    assertThat(service.newIncident(TENANT, ACTOR, cmd(PERIOD, "KY_LUAT", "")).row().summary())
        .isNull();
  }

  @Test
  void danhMucDangAnhXaVaMaSaiKhuonDang() {
    repo.profile = new IncidentRepository.PersonProfile(PERIOD, "DANG", null, UNIT);
    when(policies.customerProfile(any(), any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new ResolvedProfile(
                    UUID.randomUUID(),
                    "mau",
                    UUID.randomUUID(),
                    "0.1.0",
                    "h",
                    Map.of(IncidentService.CATALOG_COMPONENT, Map.of("KY_LUAT", "Kỷ luật")),
                    null)));
    assertThat(service.newIncident(TENANT, ACTOR, cmd(PERIOD, "KY_LUAT", null))).isNotNull();
    assertThatThrownBy(() -> service.newIncident(TENANT, ACTOR, cmd(PERIOD, "ky luat", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("APPRAISAL-422-INCIDENT-TYPE"));
    NewIncident badImpact =
        new NewIncident(
            PERSON, PERIOD, "KY_LUAT", LocalDate.parse("2027-02-01"), "1", null, "x y", null);
    assertThatThrownBy(() -> service.newIncident(TENANT, ACTOR, badImpact))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void kyDaKhoaVaNguoiChuaCoHoSo() {
    assertThatThrownBy(() -> service.newIncident(TENANT, ACTOR, cmd(PERIOD, "KY_LUAT", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION"));

    repo.profile = new IncidentRepository.PersonProfile(PERIOD, "DANG", null, UNIT);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(() -> service.newIncident(TENANT, ACTOR, cmd(PERIOD, "KY_LUAT", null)))
        .isInstanceOf(PeriodLockedException.class);
    assertThat(repo.rows).isEmpty();
  }

  @Test
  void khongGhiKyThiKiemKhoaKyChuaNgayKetLuan() {
    repo.profile = new IncidentRepository.PersonProfile(PERIOD, "DANG", null, UNIT);
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    TENANT,
                    "Q1",
                    "Quý I",
                    LocalDate.parse("2027-01-01"),
                    LocalDate.parse("2027-03-31"),
                    PeriodStatus.MO)));
    service.newIncident(TENANT, ACTOR, cmd(null, "KY_LUAT", null));
    verify(periods).assertWritable(TENANT, PeriodId.of(PERIOD));

    NewIncident outside =
        new NewIncident(
            PERSON, null, "KY_LUAT", LocalDate.parse("2027-06-01"), "2", null, null, null);
    Optional<PeriodSnapshot> q1 = periods.findById(TENANT, PeriodId.of(PERIOD));
    PeriodApi strict = mock(PeriodApi.class);
    when(strict.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(q1);
    new IncidentService(repo, new PlainSealer(), strict, null, Clock.systemUtc())
        .newIncident(TENANT, ACTOR, outside);
    verify(strict, never()).assertWritable(any(), any());

    Map<UUID, List<IncidentRepository.IncidentRow>> facts =
        service.forPersons(TENANT, PERIOD, List.of(PERSON));
    assertThat(repo.lastFrom).isEqualTo(LocalDate.parse("2027-01-01"));
    assertThat(repo.lastTo).isEqualTo(LocalDate.parse("2027-03-31"));
    assertThat(facts.get(PERSON)).hasSize(2);
    assertThat(service.forPersons(TENANT, PERIOD, List.of())).isEmpty();
  }

  private static NewIncident cmd(UUID period, String type, String summary) {
    return new NewIncident(
        PERSON,
        period,
        type,
        LocalDate.parse("2027-02-01"),
        " 12-QĐ/UBKT ",
        null,
        "LOAI_TRU",
        summary);
  }

  /** Mã hoá giả: thêm tiền tố để phân biệt bản rõ với bản lưu. */
  private static final class PlainSealer implements TextSealer {
    @Override
    public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
      return plain == null || plain.isEmpty()
          ? null
          : new SealedText(("enc:" + plain).getBytes(StandardCharsets.UTF_8), UUID.randomUUID());
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
      return sealed == null
          ? null
          : new String(sealed.cipher(), StandardCharsets.UTF_8).substring("enc:".length());
    }
  }

  private static final class Repo implements IncidentRepository {
    final Map<UUID, IncidentRow> rows = new LinkedHashMap<>();
    PersonProfile profile;
    LocalDate lastFrom;
    LocalDate lastTo;

    @Override
    public void insert(TenantId tenant, IncidentRow row) {
      rows.put(row.id(), row);
    }

    @Override
    public Optional<IncidentRow> find(TenantId tenant, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<IncidentRow> list(
        TenantId tenant, UUID unitScope, UUID personId, UUID periodId, UUID beforeId, int limit) {
      return rows.values().stream()
          .filter(r -> r.orgUnitId().equals(unitScope))
          .filter(r -> personId == null || personId.equals(r.personId()))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean withinUnit(TenantId tenant, UUID rootUnitId, UUID unitId) {
      return rootUnitId.equals(unitId);
    }

    @Override
    public Map<UUID, List<IncidentRow>> forPersons(
        TenantId tenant, UUID periodId, LocalDate from, LocalDate to, Collection<UUID> personIds) {
      lastFrom = from;
      lastTo = to;
      Map<UUID, List<IncidentRow>> out = new LinkedHashMap<>();
      rows.values().stream()
          .filter(r -> personIds.contains(r.personId()))
          .forEach(r -> out.computeIfAbsent(r.personId(), k -> new ArrayList<>()).add(r));
      return out;
    }

    @Override
    public Optional<PersonProfile> personProfile(TenantId tenant, UUID personId, UUID periodId) {
      return Optional.ofNullable(profile);
    }
  }
}
