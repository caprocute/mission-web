package vn.viettel.okrkpi.oversight.application.reopen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository.Adjustment;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Draft;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.View;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRules;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScopeItem;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-07-07: lập, xem, tính thử và huỷ yêu cầu mở lại kỳ (QT-13 bước 4, BR-13-05). */
class ReopenRequestServiceTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());
  private static final String REASON =
      "Thi hành kết luận khiếu nại về hệ số chất lượng nhiệm vụ then chốt 3.";
  // Thứ Sáu 22/01/2027.
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2027-01-22T03:00:00Z"), ZoneOffset.UTC);

  private final UUID periodId = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID child = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final UUID profile = UUID.randomUUID();
  private final UUID complaint = UUID.randomUUID();
  private final UUID me = UUID.randomUUID();

  private FakeReopenRepository repo;
  private FakePeriods periods;
  private List<String> audits;
  private ReopenRequestService service;
  private ReopenImpactSimulator.Request simulated;

  @BeforeEach
  void setUp() {
    repo = new FakeReopenRepository();
    repo.bases.add(complaint);
    periods = new FakePeriods();
    periods.status = PeriodStatus.DA_KHOA;
    audits = new ArrayList<>();
    service = service(true, true);
  }

  private ReopenRequestService service(boolean withCalendar, boolean withSimulator) {
    OversightAuditLog log =
        (tenant, actor, action, type, id, purpose, role) -> audits.add(action + ":" + role);
    ReopenImpactSimulator sim =
        (tenant, request) -> {
          simulated = request;
          return new ReopenImpactSimulator.Impact(
              "pp-2026.4",
              List.of(new ReopenImpactSimulator.Row(profile, "TONG_DIEM", "91,2", "92,1")),
              2);
        };
    return new ReopenRequestService(
        repo,
        periods,
        withCalendar ? new Calendar() : null,
        new Directory(),
        log,
        withSimulator ? sim : null,
        CLOCK);
  }

  private Actor appraiser() {
    return new Actor(me, List.of("appraiser"), unit, "aal2");
  }

  private Actor inspector() {
    return new Actor(me, List.of("inspector"), null, "aal2");
  }

  @Test
  void lapYeuCauCanXacThucNhieuLop() {
    assertThatThrownBy(
            () -> service.create(T, new Actor(me, List.of("appraiser"), unit), profileDraft(3)))
        .isInstanceOf(vn.viettel.okrkpi.shared.authz.StepUpRequiredException.class);
  }

  private Draft profileDraft(int days) {
    return new Draft(
        periodId,
        "PHAN_ANH",
        ReopenScope.HO_SO,
        List.of(profile),
        List.of(
            new ReopenScopeItem(
                "product.acceptance", profile, child, null, List.of("quality_level"))),
        days,
        REASON,
        new ReopenBasis(ReopenBasis.Kind.KHIEU_NAI, complaint, "KN-2027-0031"));
  }

  @Test
  void lapYeuCauHoSoTrongPhamViDonViCapDuoi() {
    View v = service.create(T, appraiser(), profileDraft(3));
    ReopenRequest r = v.request();
    assertThat(r.code()).isEqualTo("MLK-2027-001");
    assertThat(r.status()).isEqualTo(ReopenStatus.CHO_DUYET_1);
    assertThat(r.periodBucket()).isEqualTo(20264);
    assertThat(r.units()).containsExactly(child);
    assertThat(r.approvalsRequired()).isEqualTo(2);
    // Thứ Sáu 22/01 cộng 3 ngày làm việc: thứ Năm 28/01 (lịch có nghỉ thứ Hai 25/01).
    assertThat(r.openUntil()).isEqualTo(Instant.parse("2027-01-28T16:59:59.999Z"));
    assertThat(repo.locked).containsExactly(2027);
    assertThat(audits).containsExactly("TAO_YEU_CAU_MO_LAI_KY:appraiser");

    View second =
        service.create(
            T,
            inspector(),
            new Draft(
                periodId,
                "KE_HOACH",
                ReopenScope.DON_VI,
                List.of(other),
                List.of(
                    new ReopenScopeItem(
                        "appraisal.appraisal_profile", null, null, null, List.of())),
                null,
                REASON,
                new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ 12/QĐ-UBKT")));
    assertThat(second.request().code()).isEqualTo("MLK-2027-002");
    assertThat(second.request().openWorkingDays()).isEqualTo(3);
    assertThat(second.request().units()).containsExactly(other);
  }

  @Test
  void kyChuaKhoaBiTuChoi409() {
    periods.status = PeriodStatus.MO;
    assertThatThrownBy(() -> service.create(T, appraiser(), profileDraft(3)))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-PERIOD-NOT-LOCKED"));
  }

  @Test
  void kyKhongTonTai422() {
    periods.missing = true;
    assertThatThrownBy(() -> service.create(T, appraiser(), profileDraft(3)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void toanKyKhongCoQuyetDinhHauKiemToanDien422() {
    Draft d =
        new Draft(
            periodId,
            "DOT_XUAT",
            ReopenScope.TOAN_KY,
            List.of(),
            List.of(
                new ReopenScopeItem("appraisal.appraisal_profile", null, null, null, List.of())),
            3,
            REASON,
            new ReopenBasis(ReopenBasis.Kind.KHIEU_NAI, complaint, null));
    assertThatThrownBy(() -> service.create(T, inspector(), d))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(fields(e)).contains("scope"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));

    Draft decided =
        new Draft(
            periodId,
            "DOT_XUAT",
            ReopenScope.TOAN_KY,
            List.of(),
            d.items(),
            3,
            REASON,
            new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ 99"));
    assertThat(service.create(T, inspector(), decided).request().scopeIds()).isEmpty();
    // Người thẩm định (phạm vi Cd) không mở toàn kỳ.
    periods.status = PeriodStatus.DA_KHOA;
    repo.rows.clear();
    assertThatThrownBy(() -> service.create(T, appraiser(), decided))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
  }

  @Test
  void thoiHanMuoiMotNgayVaLyDoNganBi422() {
    assertThatThrownBy(() -> service.create(T, appraiser(), profileDraft(11)))
        .satisfies(e -> assertThat(fields(e)).containsExactly("openWorkingDays"));
    assertThatThrownBy(() -> service.create(T, appraiser(), profileDraft(0)))
        .satisfies(e -> assertThat(fields(e)).containsExactly("openWorkingDays"));
    Draft d = profileDraft(3);
    Draft shortReason =
        new Draft(
            d.periodId(), d.auditKind(), d.scope(), d.scopeIds(), d.items(), 3, "Ngắn", d.basis());
    assertThatThrownBy(() -> service.create(T, appraiser(), shortReason))
        .satisfies(e -> assertThat(fields(e)).containsExactly("reason"));
  }

  @Test
  void kiemTraDauVaoGomNhieuLoi() {
    Draft bad =
        new Draft(
            null,
            "SAI",
            null,
            List.of(),
            List.of(new ReopenScopeItem("Bảng sai", null, null, null, List.of())),
            3,
            null,
            null);
    assertThatThrownBy(() -> service.create(T, inspector(), bad))
        .satisfies(
            e ->
                assertThat(fields(e))
                    .contains("periodId", "auditKind", "scope", "reason", "basis", "scopeDetail"));
    Draft noIds =
        new Draft(
            periodId,
            "KE_HOACH",
            ReopenScope.DON_VI,
            List.of(),
            List.of(new ReopenScopeItem("a.b", null, null, null, List.of("c"))),
            3,
            REASON,
            new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ 1"));
    assertThatThrownBy(() -> service.create(T, inspector(), noIds))
        .satisfies(e -> assertThat(fields(e)).containsExactly("scopeIds"));
    Draft foreignItem =
        new Draft(
            periodId,
            "KE_HOACH",
            ReopenScope.HO_SO,
            List.of(profile),
            List.of(new ReopenScopeItem("a.b", UUID.randomUUID(), unit, null, List.of())),
            3,
            REASON,
            new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ 1"));
    assertThatThrownBy(() -> service.create(T, inspector(), foreignItem))
        .satisfies(e -> assertThat(fields(e)).containsExactly("scopeDetail"));
  }

  @Test
  void canCuKhongTonTaiTrongKy422() {
    Draft d = profileDraft(3);
    Draft missing =
        new Draft(
            d.periodId(),
            d.auditKind(),
            d.scope(),
            d.scopeIds(),
            d.items(),
            3,
            REASON,
            new ReopenBasis(ReopenBasis.Kind.CANH_BAO, UUID.randomUUID(), null));
    assertThatThrownBy(() -> service.create(T, appraiser(), missing))
        .satisfies(e -> assertThat(fields(e)).containsExactly("basis"));
  }

  @Test
  void phamViNgoaiDonViHoacThieuVaiTroBi403() {
    Draft d = profileDraft(3);
    Draft outside =
        new Draft(
            d.periodId(),
            d.auditKind(),
            d.scope(),
            d.scopeIds(),
            List.of(new ReopenScopeItem("product.acceptance", profile, other, null, List.of())),
            3,
            REASON,
            d.basis());
    assertThatThrownBy(() -> service.create(T, appraiser(), outside))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    Draft noUnit =
        new Draft(
            d.periodId(),
            d.auditKind(),
            d.scope(),
            d.scopeIds(),
            List.of(new ReopenScopeItem("product.acceptance", profile, null, null, List.of())),
            3,
            REASON,
            d.basis());
    assertThatThrownBy(() -> service.create(T, appraiser(), noUnit))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(
            () ->
                service.create(
                    T, new Actor(me, List.of("appraiser"), null, "aal2"), profileDraft(3)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service.create(T, new Actor(me, List.of("manager"), unit), d))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
  }

  @Test
  void trungPhamViYeuCauDangXuLy409VaCongNoiBo() {
    service.create(T, appraiser(), profileDraft(3));
    assertThatThrownBy(() -> service.createInternal(T, UUID.randomUUID(), profileDraft(3)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    // Yêu cầu cũ đã đóng thì lập lại được qua cổng nội bộ (không kiểm phạm vi Cd).
    ReopenRequest old = repo.rows.values().iterator().next();
    repo.rows.put(old.id(), old.cancelled("Đã huỷ để lập lại", 1));
    UUID handler = UUID.randomUUID();
    View v = service.createInternal(T, handler, profileDraft(3));
    assertThat(v.request().requestedBy()).isEqualTo(handler);
    assertThat(v.request().code()).isEqualTo("MLK-2027-002");
    assertThat(audits).last().isEqualTo("TAO_YEU_CAU_MO_LAI_KY:null");
  }

  @Test
  void xemVaDanhSachTheoPhamVi() {
    UUID id = service.create(T, appraiser(), profileDraft(3)).request().id();
    assertThat(service.get(T, appraiser(), id).period().code()).isEqualTo("2026-Q4");
    assertThat(service.get(T, inspector(), id).request().id()).isEqualTo(id);
    // Người thẩm định khác cùng cây đơn vị thấy; người ngoài cây không thấy.
    Actor peer = new Actor(UUID.randomUUID(), List.of("appraiser"), unit);
    assertThat(service.get(T, peer, id).request().id()).isEqualTo(id);
    Actor stranger = new Actor(UUID.randomUUID(), List.of("appraiser"), other);
    assertThatThrownBy(() -> service.get(T, stranger, id))
        .isInstanceOf(ReopenRequestService.NotFoundException.class);
    // Người duyệt đã có phê duyệt thì thấy.
    UUID approver = UUID.randomUUID();
    repo.approvals.put(
        id,
        List.of(
            new ReopenRequestRepository.Approval(
                UUID.randomUUID(),
                approver,
                "decider",
                "DONG_Y",
                "aal2",
                CLOCK.instant(),
                UUID.randomUUID(),
                "Đồng ý mở lại")));
    assertThat(service.get(T, new Actor(approver, List.of("decider"), null), id).approvals())
        .hasSize(1);
    assertThatThrownBy(() -> service.get(T, inspector(), UUID.randomUUID()))
        .isInstanceOf(ReopenRequestService.NotFoundException.class);

    List<View> page =
        service.list(T, peer, periodId, List.of(ReopenStatus.CHO_DUYET_1), false, null, 21);
    assertThat(page).hasSize(1);
    assertThat(repo.lastFilter.units()).contains(unit, child);
    assertThat(repo.lastFilter.everything()).isFalse();
    service.list(T, inspector(), null, null, false, null, 21);
    assertThat(repo.lastFilter.everything()).isTrue();
    assertThat(repo.lastFilter.units()).isEmpty();
    assertThat(service.list(T, peer, null, null, true, null, 21)).isEmpty();
  }

  @Test
  void tinhThuLayDieuChinhTuKetLuanKhieuNaiVaKhongGhi() {
    UUID id = service.create(T, appraiser(), profileDraft(3)).request().id();
    int before = repo.writes;
    repo.adjustments = List.of(new Adjustment("kpi.task3.quality", "B", "A", "Kết luận khiếu nại"));
    ReopenImpactSimulator.Impact impact = service.simulate(T, appraiser(), id, List.of());
    assertThat(impact.policyVersion()).isEqualTo("pp-2026.4");
    assertThat(impact.rows())
        .singleElement()
        .extracting(ReopenImpactSimulator.Row::after)
        .isEqualTo("92,1");
    assertThat(simulated.profileIds()).containsExactly(profile);
    assertThat(simulated.adjustments()).extracting(Adjustment::newValue).containsExactly("A");
    assertThat(repo.writes).isEqualTo(before);

    List<Adjustment> mine = List.of(new Adjustment("kpi.task3.quality", null, "C", "Giả định"));
    service.simulate(T, appraiser(), id, mine);
    assertThat(simulated.adjustments()).isEqualTo(mine);

    ReopenRequestService noSim = service(false, false);
    assertThatThrownBy(() -> noSim.simulate(T, appraiser(), id, null))
        .isInstanceOf(ReopenRequestService.SimulationUnavailableException.class);
  }

  @Test
  void tinhThuPhamViDonViGomHoSoTuDoiTuong() {
    UUID id =
        service
            .create(
                T,
                inspector(),
                new Draft(
                    periodId,
                    "KE_HOACH",
                    ReopenScope.DON_VI,
                    List.of(child),
                    List.of(
                        new ReopenScopeItem("product.acceptance", profile, child, null, List.of()),
                        new ReopenScopeItem("product.acceptance", profile, child, null, List.of())),
                    3,
                    REASON,
                    new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ 5")))
            .request()
            .id();
    service.simulate(T, inspector(), id, null, "Bearer i");
    assertThat(simulated.authorization()).isEqualTo("Bearer i");
    assertThat(simulated.scope()).isEqualTo(ReopenScope.DON_VI);
    assertThat(simulated.profileIds()).containsExactly(profile);
    assertThat(simulated.adjustments()).isEmpty();
  }

  @Test
  void huyYeuCauChiNguoiLapTruocKhiDuHaiPheDuyet() {
    UUID id = service.create(T, appraiser(), profileDraft(3)).request().id();
    Actor peer = new Actor(UUID.randomUUID(), List.of("appraiser"), unit);
    assertThatThrownBy(() -> service.cancel(T, peer, id, "\"0\"", "Không còn cần thiết nữa"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    Actor stranger = new Actor(UUID.randomUUID(), List.of("appraiser"), other);
    assertThatThrownBy(() -> service.cancel(T, stranger, id, "\"0\"", "Không còn cần thiết nữa"))
        .isInstanceOf(ReopenRequestService.NotFoundException.class);
    assertThatThrownBy(() -> service.cancel(T, appraiser(), id, null, "Không còn cần thiết nữa"))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThatThrownBy(() -> service.cancel(T, appraiser(), id, "\"5\"", "Không còn cần thiết nữa"))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.cancel(T, appraiser(), id, "\"0\"", "Ngắn"))
        .satisfies(e -> assertThat(fields(e)).containsExactly("reason"));

    View v = service.cancel(T, appraiser(), id, "\"0\"", "  Không còn cần thiết nữa  ");
    assertThat(v.request().status()).isEqualTo(ReopenStatus.DA_DONG);
    assertThat(v.request().cancelReason()).isEqualTo("Không còn cần thiết nữa");
    assertThat(v.request().rowVersion()).isEqualTo(1);
    assertThat(audits).last().isEqualTo("HUY_YEU_CAU_MO_LAI_KY:appraiser");
    assertThatThrownBy(() -> service.cancel(T, appraiser(), id, "\"1\"", "Không còn cần thiết nữa"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  @Test
  void huyGapXungDotGhiDongThoi409() {
    UUID id = service.create(T, inspector(), profileDraft(3)).request().id();
    repo.failUpdate = true;
    assertThatThrownBy(() -> service.cancel(T, inspector(), id, "\"0\"", "Không còn cần thiết nữa"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-VERSION-CONFLICT"));
    assertThatThrownBy(
            () -> service.cancel(T, inspector(), UUID.randomUUID(), "\"0\"", "Không còn cần thiết"))
        .isInstanceOf(ReopenRequestService.NotFoundException.class);
  }

  @Test
  void khongCoLichDungThuHaiDenThuSauVaKyNamLayQuyKhong() {
    ReopenRequestService noCalendar = service(false, true);
    periods.yearly = true;
    ReopenRequest r = noCalendar.create(T, appraiser(), profileDraft(3)).request();
    // Thứ Sáu 22/01 cộng 3 ngày làm việc (bỏ thứ Bảy, Chủ nhật): thứ Tư 27/01.
    assertThat(r.openUntil()).isEqualTo(Instant.parse("2027-01-27T16:59:59.999Z"));
    assertThat(r.periodBucket()).isEqualTo(20260);
  }

  @Test
  void quyTacMienYeuCau() {
    assertThat(ReopenRules.code(2027, 7)).isEqualTo("MLK-2027-007");
    assertThat(ReopenRules.code(2027, 1234)).isEqualTo("MLK-2027-1234");
    assertThatThrownBy(() -> ReopenRules.code(2027, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ReopenRules.sequenceOf("MLK-2027-012", 2027)).isEqualTo(12);
    assertThat(ReopenRules.sequenceOf("MLK-2026-012", 2027)).isZero();
    assertThat(ReopenRules.sequenceOf("KN-2027-012", 2027)).isZero();
    assertThat(ReopenRules.sequenceOf(null, 2027)).isZero();
    assertThat(ReopenRules.wholePeriodAllowed(null)).isFalse();
    assertThat(ReopenRules.cancelReasonValid(null)).isFalse();
    assertThat(ReopenRules.reasonValid("x".repeat(4001))).isFalse();
    assertThat(ReopenRules.reasonValid("x".repeat(4000))).isTrue();

    assertThatThrownBy(() -> new ReopenBasis(null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    ReopenBasis decision = new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "  ");
    assertThat(decision.complete()).isFalse();
    assertThat(new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "x".repeat(300)).ref())
        .hasSize(200);
    assertThat(new ReopenBasis(ReopenBasis.Kind.HAU_KIEM, null, "a").complete()).isFalse();
    assertThat(new ReopenScopeItem(null, null, null, null, null).wellFormed()).isFalse();
    assertThat(new ReopenScopeItem("a.b", null, null, null, List.of("Cột")).wellFormed()).isFalse();

    ReopenRequest base =
        repoRow(ReopenStatus.DA_THUC_HIEN, null, ReopenScope.DON_VI, List.of(unit));
    assertThat(base.active()).isTrue();
    assertThat(
            repoRow(ReopenStatus.DA_THUC_HIEN, CLOCK.instant(), ReopenScope.DON_VI, List.of())
                .active())
        .isFalse();
    assertThat(repoRow(ReopenStatus.TU_CHOI, null, ReopenScope.DON_VI, List.of()).active())
        .isFalse();
    assertThat(repoRow(ReopenStatus.DA_DUYET, null, ReopenScope.DON_VI, List.of()).active())
        .isTrue();
    assertThat(base.overlaps(ReopenScope.TOAN_KY, List.of())).isTrue();
    assertThat(base.overlaps(ReopenScope.HO_SO, List.of(unit))).isFalse();
    assertThat(base.overlaps(ReopenScope.DON_VI, List.of(other))).isFalse();
    assertThat(base.overlaps(ReopenScope.DON_VI, List.of(unit))).isTrue();
    assertThat(ReopenStatus.DA_DUYET.cancellable()).isFalse();
  }

  private ReopenRequest repoRow(
      ReopenStatus status, Instant relocked, ReopenScope scope, List<UUID> ids) {
    return new ReopenRequest(
        UUID.randomUUID(),
        periodId,
        20264,
        "MLK-2027-050",
        scope,
        ids,
        null,
        null,
        new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ"),
        "KE_HOACH",
        REASON,
        me,
        2,
        status,
        3,
        CLOCK.instant(),
        relocked == null ? null : CLOCK.instant(),
        relocked,
        null,
        null,
        CLOCK.instant(),
        0);
  }

  private static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  private static List<String> fields(Throwable e) {
    return ((BusinessException) e).errors().stream().map(f -> f.field()).toList();
  }

  private final class Directory implements AccessDirectory {
    @Override
    public Map<UUID, String> personNames(TenantId tenant, Collection<UUID> personIds) {
      return Map.of();
    }

    @Override
    public Map<UUID, String> unitNames(TenantId tenant, Collection<UUID> unitIds) {
      return Map.of();
    }

    @Override
    public List<UUID> subunitsOf(TenantId tenant, UUID orgUnitId) {
      return orgUnitId.equals(unit) ? List.of(child) : List.of();
    }

    @Override
    public List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId) {
      return List.of();
    }
  }

  /** Lịch có ngày nghỉ thứ Hai 25/01/2027 ngoài thứ Bảy, Chủ nhật. */
  private static final class Calendar implements WorkingCalendarApi {
    @Override
    public boolean isWorkingDay(TenantId tenantId, LocalDate date) {
      return date.getDayOfWeek() != DayOfWeek.SATURDAY
          && date.getDayOfWeek() != DayOfWeek.SUNDAY
          && !date.equals(LocalDate.of(2027, 1, 25));
    }

    @Override
    public LocalDate addWorkingDays(TenantId tenantId, LocalDate from, int n) {
      LocalDate d = from;
      int left = n;
      while (left > 0) {
        d = d.plusDays(1);
        if (isWorkingDay(tenantId, d)) {
          left--;
        }
      }
      return d;
    }

    @Override
    public long countWorkingDays(
        TenantId tenantId, LocalDate fromExclusive, LocalDate toInclusive) {
      return 0;
    }

    @Override
    public WorkingCalendar celCalendar(TenantId tenantId) {
      return null;
    }
  }

  private final class FakePeriods implements PeriodApi {
    PeriodStatus status;
    boolean missing;
    boolean yearly;

    @Override
    public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
      return Optional.empty();
    }

    @Override
    public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId id) {
      if (missing) {
        return Optional.empty();
      }
      return Optional.of(
          new PeriodSnapshot(
              id,
              tenantId,
              yearly ? "2026" : "2026-Q4",
              yearly ? "Năm 2026" : "Quý IV/2026",
              yearly ? LocalDate.of(2026, 1, 1) : LocalDate.of(2026, 10, 1),
              LocalDate.of(2026, 12, 31),
              status));
    }

    @Override
    public void assertOpen(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {}
  }
}
