package vn.viettel.okrkpi.oversight.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService.Command;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService.View;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService.Viewer;
import vn.viettel.okrkpi.oversight.application.port.AlertHandlers;
import vn.viettel.okrkpi.oversight.application.port.AlertNotices;
import vn.viettel.okrkpi.oversight.application.port.AlertSubjects;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository.Filter;
import vn.viettel.okrkpi.oversight.domain.alert.AlertWorkflow;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-07-11: phạm vi, ẩn với đối tượng, tách vai trò, luồng trạng thái, thông báo, chỉ số. */
class AlertCaseServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID KY = UUID.randomUUID();
  static final UUID PHONG = UUID.randomUUID();
  static final UUID PHONG_CON = UUID.randomUUID();
  static final UUID PHONG_KHAC = UUID.randomUUID();
  static final UUID CAN_BO = UUID.randomUUID();
  static final UUID THAM_DINH = UUID.randomUUID();
  static final UUID THAM_DINH_2 = UUID.randomUUID();
  static final UUID THANH_TRA = UUID.randomUUID();
  static final String NOTE = "Đã đối chiếu ba báo cáo tháng với hồ sơ gốc của đơn vị.";

  final Clock clock = Clock.fixed(Instant.parse("2026-12-01T03:00:00Z"), ZoneOffset.UTC);
  final FakeAlertRepository repo = new FakeAlertRepository();
  final Map<UUID, AlertSubjects.Subject> profiles = new HashMap<>();
  final List<String> audits = new ArrayList<>();
  final List<String> sent = new ArrayList<>();
  final List<String> decided = new ArrayList<>();
  PeriodStatus periodStatus = PeriodStatus.DA_KHOA;
  AlertCaseService service;

  final Viewer appraiser = new Viewer(THAM_DINH, List.of("appraiser"), PHONG);
  final Viewer inspector = new Viewer(THANH_TRA, List.of("inspector"), PHONG);

  @BeforeEach
  void setUp() {
    AlertSubjects subjects =
        new AlertSubjects() {
          @Override
          public Map<UUID, Subject> resolve(TenantId t, Collection<AnomalyAlert> alerts) {
            Map<UUID, Subject> out = new HashMap<>();
            for (AnomalyAlert a : alerts) {
              out.put(
                  a.id(),
                  switch (a.subjectType()) {
                    case "CA_NHAN" -> profiles.get(a.subjectId());
                    case "DON_VI" -> new Subject(a.subjectId(), Set.of(), "Phòng");
                    default -> new Subject(null, Set.of(), null);
                  });
            }
            return out;
          }

          @Override
          public Set<UUID> scopeOf(TenantId t, UUID unit) {
            return unit.equals(PHONG) ? new HashSet<>(Set.of(PHONG, PHONG_CON)) : Set.of(unit);
          }

          @Override
          public Map<UUID, String> personNames(TenantId t, Collection<UUID> people) {
            return Map.of(THAM_DINH, "Người thẩm định");
          }
        };
    AlertHandlers handlers =
        new AlertHandlers() {
          @Override
          public Set<UUID> ofProfiles(TenantId t, PeriodId p, Collection<UUID> ids) {
            return Set.of(THAM_DINH, THAM_DINH_2);
          }

          @Override
          public Set<UUID> ofUnits(TenantId t, PeriodId p, Collection<UUID> ids) {
            return Set.of(THAM_DINH);
          }

          @Override
          public UUID parentOf(TenantId t, UUID unit) {
            return null;
          }

          @Override
          public Collection<UUID> subunitsOf(TenantId t, UUID unit) {
            return List.of();
          }
        };
    PeriodApi periods =
        new PeriodApi() {
          @Override
          public Optional<PeriodSnapshot> findCurrent(TenantId t) {
            return Optional.empty();
          }

          @Override
          public Optional<PeriodSnapshot> findById(TenantId t, PeriodId id) {
            return Optional.of(
                new PeriodSnapshot(
                    id,
                    t,
                    "2026-Q4",
                    "Quý IV/2026",
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 12, 31),
                    periodStatus));
          }

          @Override
          public void assertOpen(TenantId t, PeriodId p) {}
        };
    AlertNotices notices =
        new AlertNotices() {
          @Override
          public void newHighAlerts(TenantId t, UUID r, int n, UUID b) {}

          @Override
          public void assigned(TenantId t, UUID r, UUID alert, UUID action) {
            sent.add("giao:" + r);
          }

          @Override
          public void validated(TenantId t, Collection<UUID> rs, UUID alert, int n, UUID action) {
            sent.add("hople:" + rs + ":" + n);
          }
        };
    service =
        new AlertCaseService(
            repo,
            subjects,
            handlers,
            periods,
            notices,
            (t, actor, action, type, id, purpose, role) -> audits.add(action + ":" + role),
            (signal, outcome) -> decided.add(signal + ":" + outcome),
            clock);
  }

  AnomalyAlert alert(String severity, String type, UUID subject, String status, int minutes) {
    AnomalyAlert a =
        new AnomalyAlert(
            UUID.randomUUID(),
            KY,
            20264,
            "MINH_CHUNG_TRUNG",
            severity,
            type,
            subject,
            List.of("ky:" + KY, "evidence:" + UUID.randomUUID(), "task:" + UUID.randomUUID()),
            Instant.parse("2026-11-20T00:00:00Z").plusSeconds(60L * minutes),
            UUID.randomUUID(),
            "2026.10.3",
            status,
            0);
    repo.rows.put(a.id(), a);
    repo.insertAction(TENANT, a, "TU_DONG", "Tạm chưa tính điểm", true, null, clock.instant());
    return a;
  }

  String tag(UUID id) {
    return "\"" + repo.rows.get(id).rowVersion() + "\"";
  }

  static Filter all() {
    return new Filter(null, null, List.of(), List.of(), List.of());
  }

  @Test
  void listKeepsScopeHidesSubjectAndSortsBySeverityThenTime() {
    UUID hoSo = UUID.randomUUID();
    profiles.put(hoSo, new AlertSubjects.Subject(PHONG_CON, Set.of(THAM_DINH), "Cán bộ"));
    AnomalyAlert own = alert("CAO", "CA_NHAN", hoSo, "MOI", 1);
    AnomalyAlert low = alert("THAP", "DON_VI", PHONG, "MOI", 9);
    AnomalyAlert highOld = alert("CAO", "DON_VI", PHONG_CON, "MOI", 2);
    AnomalyAlert highNew = alert("CAO", "DON_VI", PHONG, "MOI", 5);
    alert("CAO", "DON_VI", PHONG_KHAC, "MOI", 7);
    AnomalyAlert approver = alert("VUA", "NGUOI_DUYET", UUID.randomUUID(), "MOI", 3);

    assertThat(service.list(TENANT, appraiser, all(), null, 10))
        .extracting(v -> v.alert().id())
        .containsExactly(highNew.id(), highOld.id(), low.id());
    assertThat(service.list(TENANT, inspector, all(), null, 10))
        .extracting(v -> v.alert().id())
        .containsExactly(highNew.id(), highOld.id(), own.id(), approver.id(), low.id());
    assertThat(service.list(TENANT, appraiser, all(), null, 2)).hasSize(2);
    Filter branch = new Filter(null, PHONG_CON, List.of(), List.of(), List.of());
    assertThat(service.list(TENANT, appraiser, branch, null, 10))
        .extracting(v -> v.alert().id())
        .containsExactly(highOld.id());
    assertThat(service.list(TENANT, new Viewer(CAN_BO, List.of("member"), PHONG), all(), null, 5))
        .isEmpty();
    assertThatThrownBy(() -> service.get(TENANT, appraiser, own.id()))
        .isInstanceOf(AlertCaseService.NotFoundException.class);

    var counts = service.counts(TENANT, appraiser);
    assertThat(counts.newCount()).isEqualTo(3);
    assertThat(counts.windowDays()).isEqualTo(90);
  }

  @Test
  void firstOpenByAppraiserMarksViewedOnceWithoutNewVersion() {
    AnomalyAlert a = alert("CAO", "DON_VI", PHONG, "MOI", 1);
    View first = service.get(TENANT, appraiser, a.id());
    View second = service.get(TENANT, appraiser, a.id());
    assertThat(first.alert().status()).isEqualTo("DANG_XEM");
    assertThat(second.alert().rowVersion()).isEqualTo(first.alert().rowVersion()).isZero();
    assertThat(repo.viewed).isEqualTo(1);
    assertThat(audits).containsExactly("XEM_CANH_BAO_BAT_THUONG:appraiser");
    assertThat(first.actions()).hasSize(1);

    AnomalyAlert b = alert("VUA", "DON_VI", PHONG, "MOI", 2);
    assertThat(service.get(TENANT, inspector, b.id()).alert().status()).isEqualTo("MOI");
  }

  @Test
  void validateClosesUnblocksNotifiesAndCountsMetric() {
    AnomalyAlert a = alert("CAO", "DON_VI", PHONG, "DANG_XEM", 1);
    View v =
        service.act(
            TENANT, appraiser, a.id(), "\"0\"", new Command("XAC_NHAN_HOP_LE", NOTE, null, null));
    assertThat(v.alert().status()).isEqualTo("DONG");
    assertThat(v.alert().rowVersion()).isEqualTo(2);
    assertThat(repo.blockedEvidence(TENANT, List.of(UUID.randomUUID()))).isEmpty();
    assertThat(repo.actions).noneMatch(FakeAlertRepository.Action::active);
    // Người xử lý khác của đơn vị: không có (chỉ chính người xác nhận), nên không gửi.
    assertThat(sent).isEmpty();
    assertThat(decided).containsExactly("MINH_CHUNG_TRUNG:XAC_NHAN_HOP_LE");
    assertThat(audits).containsExactly("XAC_NHAN_HOP_LE:appraiser");
    assertThat(service.counts(TENANT, appraiser).validCount()).isEqualTo(1);

    UUID hoSo = UUID.randomUUID();
    profiles.put(hoSo, new AlertSubjects.Subject(PHONG, Set.of(CAN_BO), "Cán bộ"));
    AnomalyAlert p = alert("VUA", "CA_NHAN", hoSo, "DANG_XEM", 2);
    service.act(
        TENANT, appraiser, p.id(), tag(p.id()), new Command("XAC_NHAN_HOP_LE", NOTE, null, null));
    assertThat(sent).containsExactly("hople:[" + THAM_DINH_2 + "]:1");
  }

  @Test
  void assignThenOnlyInspectorConcludes() {
    AnomalyAlert a = alert("CAO", "DON_VI", PHONG, "DANG_XEM", 1);
    UUID ubkt = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT,
                    appraiser,
                    a.id(),
                    tag(a.id()),
                    new Command("GIAO_KIEM_TRA", NOTE, null, null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("COMMON-422-VALIDATION"));
    View v =
        service.act(
            TENANT, appraiser, a.id(), tag(a.id()), new Command("GIAO_KIEM_TRA", NOTE, ubkt, null));
    assertThat(v.alert().status()).isEqualTo("DA_GIAO_KIEM_TRA");
    assertThat(sent).containsExactly("giao:" + ubkt);
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT,
                    appraiser,
                    a.id(),
                    tag(a.id()),
                    new Command("KET_LUAN", NOTE, null, "CO_VI_PHAM")))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES"));
    // Kỳ đã khoá, chưa có yêu cầu mở lại: giữ CO_VI_PHAM; lập yêu cầu thì đóng.
    View c =
        service.act(
            TENANT, inspector, a.id(), "\"1\"", new Command("KET_LUAN", NOTE, null, "CO_VI_PHAM"));
    assertThat(c.alert().status()).isEqualTo("CO_VI_PHAM");
    assertThat(service.counts(TENANT, inspector).violations()).isEqualTo(1);
    assertThat(service.closeOnReopen(TENANT, a.id(), THANH_TRA)).isTrue();
    assertThat(repo.rows.get(a.id()).status()).isEqualTo("DONG");
    assertThat(service.closeOnReopen(TENANT, a.id(), THANH_TRA)).isFalse();
    assertThat(service.closeOnReopen(TENANT, UUID.randomUUID(), THANH_TRA)).isFalse();
    assertThat(decided)
        .containsExactly("MINH_CHUNG_TRUNG:GIAO_KIEM_TRA", "MINH_CHUNG_TRUNG:CO_VI_PHAM");
  }

  @Test
  void violationClosesAtOnceWhenPeriodOpenOrReopenFiled() {
    periodStatus = PeriodStatus.MO;
    AnomalyAlert a = alert("CAO", "DON_VI", PHONG, "DA_GIAO_KIEM_TRA", 1);
    assertThat(
            service
                .act(
                    TENANT,
                    inspector,
                    a.id(),
                    tag(a.id()),
                    new Command("KET_LUAN", NOTE, null, "CO_VI_PHAM"))
                .alert()
                .status())
        .isEqualTo("DONG");
    periodStatus = PeriodStatus.DA_KHOA;
    AnomalyAlert b = alert("VUA", "DON_VI", PHONG, "DA_GIAO_KIEM_TRA", 2);
    repo.reopened.add(b.id());
    assertThat(
            service
                .act(
                    TENANT,
                    inspector,
                    b.id(),
                    tag(b.id()),
                    new Command("KET_LUAN", NOTE, null, "CO_VI_PHAM"))
                .alert()
                .status())
        .isEqualTo("DONG");
    AnomalyAlert c = alert("THAP", "DON_VI", PHONG, "DA_GIAO_KIEM_TRA", 3);
    assertThat(
            service
                .act(
                    TENANT,
                    inspector,
                    c.id(),
                    tag(c.id()),
                    new Command("KET_LUAN", NOTE, null, "KHONG_VI_PHAM"))
                .alert()
                .status())
        .isEqualTo("DONG");
  }

  @Test
  void rejectsSubjectShortNoteWrongStateStaleVersionAndOutsiders() {
    UUID hoSo = UUID.randomUUID();
    profiles.put(hoSo, new AlertSubjects.Subject(PHONG, Set.of(THAM_DINH), "Cán bộ"));
    AnomalyAlert own = alert("CAO", "CA_NHAN", hoSo, "DANG_XEM", 1);
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT,
                    appraiser,
                    own.id(),
                    tag(own.id()),
                    new Command("GHI_CHU", NOTE, null, null)))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES"));
    AnomalyAlert a = alert("CAO", "DON_VI", PHONG, "MOI", 2);
    String nineteen = "Mười chín ký tự đây";
    assertThat(nineteen.codePointCount(0, nineteen.length())).isEqualTo(19);
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT,
                    appraiser,
                    a.id(),
                    tag(a.id()),
                    new Command("GHI_CHU", nineteen, null, null)))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT,
                    appraiser,
                    a.id(),
                    tag(a.id()),
                    new Command("XAC_NHAN_HOP_LE", NOTE, null, null)))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT, appraiser, a.id(), "\"7\"", new Command("GHI_CHU", NOTE, null, null)))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT,
                    new Viewer(CAN_BO, List.of("member"), PHONG),
                    a.id(),
                    tag(a.id()),
                    new Command("GHI_CHU", NOTE, null, null)))
        .isInstanceOf(AlertCaseService.NotFoundException.class);
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT, appraiser, a.id(), tag(a.id()), new Command("XOA", NOTE, null, null)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT,
                    inspector,
                    a.id(),
                    tag(a.id()),
                    new Command("KET_LUAN", NOTE, null, "SAI")))
        .isInstanceOf(BusinessException.class);
    View noted =
        service.act(
            TENANT, appraiser, a.id(), tag(a.id()), new Command("GHI_CHU", NOTE, null, null));
    assertThat(noted.alert().status()).isEqualTo("MOI");
    assertThat(service.names(TENANT, noted)).containsEntry(THAM_DINH, "Người thẩm định");
    AnomalyAlert closed = alert("CAO", "DON_VI", PHONG, "DONG", 3);
    assertThatThrownBy(
            () ->
                service.act(
                    TENANT,
                    appraiser,
                    closed.id(),
                    tag(closed.id()),
                    new Command("GHI_CHU", NOTE, null, null)))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    assertThat(AlertWorkflow.noteValid(null)).isFalse();
  }
}
