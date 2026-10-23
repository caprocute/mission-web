package vn.viettel.okrkpi.workflow.domain.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.ProcessRef;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;

/** Quy tắc miền của giám sát tiến trình SCR-1506 (DV-06-10). */
class MonitorDomainTest {

  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  static final UUID TENANT = UUID.randomUUID();
  static final BusinessKey KEY = new BusinessKey("APPRAISAL_PROFILE", UUID.randomUUID());

  /** Thứ Hai 07/12/2026, 09:00 giờ Việt Nam. */
  static final Instant MONDAY = Instant.parse("2026-12-07T02:00:00Z");

  static TaskProjection row(UUID assignee, String role, Instant due, ProcessRef process) {
    return new TaskProjection(
        "t-1",
        TENANT,
        assignee,
        role,
        role == null ? null : UUID.randomUUID(),
        KEY,
        "duyet-ho-so-danh-gia",
        "Hồ sơ đánh giá năm 2026",
        null,
        due,
        Instant.parse("2026-12-01T01:00:00Z"),
        3,
        process);
  }

  // ------------------------------------------------------------------ bộ lọc

  @Test
  void boLocPhanBietPhamViTenantVaCell() {
    UUID period = UUID.randomUUID();
    InstanceFilter tenant = InstanceFilter.tenant(TENANT, " ", true, period, MONDAY);
    assertThat(tenant.cellScope()).isFalse();
    assertThat(tenant.templateCode()).isNull();
    assertThat(tenant.cursorScope()).isEqualTo("workflow.instances|" + TENANT + "||true|" + period);
    InstanceFilter cell = InstanceFilter.cell("duyet-okr", null, null, MONDAY);
    assertThat(cell.cellScope()).isTrue();
    assertThat(cell.cursorScope()).isEqualTo("workflow.instances|cell|duyet-okr||");
    assertThatThrownBy(() -> InstanceFilter.tenant(null, null, null, null, MONDAY))
        .isInstanceOf(NullPointerException.class);
  }

  // ------------------------------------------------------------------ dòng giám sát

  @Test
  void dongGiamSatKhongKetKhiConHanVaCoNguoiXuLy() {
    UUID assignee = UUID.randomUUID();
    Instant started = Instant.parse("2026-11-30T01:00:00Z");
    InstanceSummary s =
        InstanceSummary.of(
            row(
                assignee,
                "appraiser",
                MONDAY.plusSeconds(86_400),
                new ProcessRef("p-1", "review", "Duyệt theo cấp", 3, started)),
            "CELL-HN",
            MONDAY,
            false);
    assertThat(s.stuck()).isFalse();
    assertThat(s.stuckReason()).isNull();
    assertThat(s.title()).isEqualTo("Hồ sơ đánh giá năm 2026");
    assertThat(s.assigneeId()).isEqualTo(assignee);
    assertThat(s.templateVersion()).isEqualTo(3);
    assertThat(s.currentActivityName()).isEqualTo("Duyệt theo cấp");
    assertThat(s.startedAt()).isEqualTo(started);
    assertThat(s.cellCode()).isEqualTo("CELL-HN");
    assertThat(s.currentTaskId()).isEqualTo("t-1");
  }

  @Test
  void quaHanVaChuaNhanViecLaKetCoLyDo() {
    InstanceSummary s =
        InstanceSummary.of(
            row(null, "appraiser", MONDAY.minusSeconds(3 * 86_400), ProcessRef.NONE),
            "CELL-HN",
            MONDAY,
            false);
    assertThat(s.stuck()).isTrue();
    assertThat(s.stuckReason()).isEqualTo("Quá hạn 3 ngày, chưa có người nhận xử lý.");
    // Dòng chiếu cũ không có thời điểm khởi tạo tiến trình: lấy thời điểm tạo bước.
    assertThat(s.startedAt()).isEqualTo(Instant.parse("2026-12-01T01:00:00Z"));
    assertThat(s.templateVersion()).isNull();
  }

  @Test
  void khongCoNguoiXuLyLanNhomUngVienLaKet() {
    InstanceSummary orphan =
        InstanceSummary.of(row(null, null, null, ProcessRef.NONE), "C", MONDAY, false);
    assertThat(orphan.stuck()).isTrue();
    assertThat(orphan.stuckReason()).isEqualTo("Không có người xử lý.");
    InstanceSummary overdueOrphan =
        InstanceSummary.of(
            row(null, null, MONDAY.minusSeconds(3600), ProcessRef.NONE), "C", MONDAY, false);
    assertThat(overdueOrphan.stuckReason()).isEqualTo("Quá hạn trong ngày, không có người xử lý.");
    InstanceSummary assigned =
        InstanceSummary.of(
            row(UUID.randomUUID(), null, MONDAY.minusSeconds(2 * 86_400), ProcessRef.NONE),
            "C",
            MONDAY,
            false);
    assertThat(assigned.stuckReason()).isEqualTo("Quá hạn 2 ngày.");
  }

  @Test
  void phamViCellCheNoiDungHoSo() {
    UUID assignee = UUID.randomUUID();
    InstanceSummary s =
        InstanceSummary.of(row(assignee, "appraiser", null, ProcessRef.NONE), "C", MONDAY, true);
    assertThat(s.title()).isNull();
    assertThat(s.assigneeId()).isNull();
    assertThat(s.candidateRole()).isEqualTo("appraiser");
    assertThat(s.businessKey()).isEqualTo(KEY);
  }

  @Test
  void maMauRongKhiDongChieuKhongCoMa() {
    TaskProjection p =
        new TaskProjection(
            "t", TENANT, null, "leader", UUID.randomUUID(), KEY, null, "T", null, null, MONDAY, 1);
    assertThat(InstanceSummary.of(p, "C", MONDAY, false).templateCode()).isEmpty();
    assertThat(p.process()).isEqualTo(ProcessRef.NONE);
  }

  // ------------------------------------------------------------------ gia hạn

  @Test
  void giaHanTrongGioiHanNgayLamViec() {
    // Hạn cũ thứ Hai 14/12, đề nghị thứ Sáu 18/12 cùng giờ: thêm 4 ngày làm việc.
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    Instant requested = Instant.parse("2026-12-18T10:00:00Z");
    DueExtension.Result r = DueExtension.evaluate(due, requested, MONDAY, 5, Map.of(), VN);
    assertThat(r.accepted()).isTrue();
    assertThat(r.newDue()).isEqualTo(requested);
    assertThat(r.addedWorkingDays()).isEqualTo(4);
    assertThat(r.limit()).isEqualTo(5);
  }

  @Test
  void hanRoiVaoNgayNghiDoiToiNgayLamViecKeTiepGiuGio() {
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    // Thứ Bảy 19/12 17:00 giờ Việt Nam; thứ Hai 21/12 là ngày nghỉ bù khai trong lịch.
    Instant saturday = Instant.parse("2026-12-19T10:00:00Z");
    Map<LocalDate, Boolean> overrides = Map.of(LocalDate.of(2026, 12, 21), false);
    DueExtension.Result r = DueExtension.evaluate(due, saturday, MONDAY, 10, overrides, VN);
    assertThat(r.accepted()).isTrue();
    assertThat(r.newDue()).isEqualTo(Instant.parse("2026-12-22T10:00:00Z"));
    // 15, 16, 17, 18, 22 tháng 12.
    assertThat(r.addedWorkingDays()).isEqualTo(5);
  }

  @Test
  void ngayLamBuDuocTinhLaNgayLamViec() {
    Instant due = Instant.parse("2026-12-18T10:00:00Z");
    Instant saturday = Instant.parse("2026-12-19T10:00:00Z");
    Map<LocalDate, Boolean> overrides = Map.of(LocalDate.of(2026, 12, 19), true);
    DueExtension.Result r = DueExtension.evaluate(due, saturday, MONDAY, 1, overrides, VN);
    assertThat(r.accepted()).isTrue();
    assertThat(r.newDue()).isEqualTo(saturday);
    assertThat(r.addedWorkingDays()).isEqualTo(1);
  }

  @Test
  void vuotGioiHanBiTuChoi() {
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    Instant requested = Instant.parse("2026-12-28T10:00:00Z");
    DueExtension.Result r = DueExtension.evaluate(due, requested, MONDAY, 3, Map.of(), VN);
    assertThat(r.accepted()).isFalse();
    assertThat(r.violation()).contains(DueExtension.Violation.OVER_LIMIT);
    assertThat(r.addedWorkingDays()).isEqualTo(10);
    assertThat(r.limit()).isEqualTo(3);
  }

  @Test
  void gioiHanBiChanTranCung() {
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    Instant far = Instant.parse("2027-06-14T10:00:00Z");
    DueExtension.Result r = DueExtension.evaluate(due, far, MONDAY, 10_000, Map.of(), VN);
    assertThat(r.limit()).isEqualTo(DueExtension.HARD_MAX_WORKING_DAYS);
    assertThat(r.violation()).contains(DueExtension.Violation.OVER_LIMIT);
    assertThat(DueExtension.evaluate(due, far, MONDAY, -1, Map.of(), VN).limit()).isZero();
  }

  @Test
  void hanMoiPhaiSauHienTaiVaSauHanCu() {
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    assertThat(DueExtension.evaluate(due, MONDAY, MONDAY, 5, Map.of(), VN).violation())
        .contains(DueExtension.Violation.NOT_IN_FUTURE);
    assertThat(
            DueExtension.evaluate(due, due.minusSeconds(60), MONDAY, 5, Map.of(), VN).violation())
        .contains(DueExtension.Violation.NOT_LATER);
    assertThat(DueExtension.evaluate(due, due, MONDAY, 5, Map.of(), VN).violation())
        .contains(DueExtension.Violation.NOT_LATER);
  }

  @Test
  void buocQuaHanHoacChuaCoHanTinhTuHienTai() {
    // Thứ Tư 09/12 09:00: hai ngày làm việc sau thứ Hai.
    Instant wednesday = Instant.parse("2026-12-09T02:00:00Z");
    DueExtension.Result overdue =
        DueExtension.evaluate(MONDAY.minusSeconds(5 * 86_400), wednesday, MONDAY, 2, Map.of(), VN);
    assertThat(overdue.accepted()).isTrue();
    assertThat(overdue.addedWorkingDays()).isEqualTo(2);
    DueExtension.Result none = DueExtension.evaluate(null, wednesday, MONDAY, 1, Map.of(), VN);
    assertThat(none.violation()).contains(DueExtension.Violation.OVER_LIMIT);
  }

  @Test
  void lichKhongCoNgayLamViecBaoLoi() {
    Map<LocalDate, Boolean> allOff = new java.util.HashMap<>();
    LocalDate d = LocalDate.of(2026, 12, 7);
    for (int i = 0; i < 400; i++) {
      allOff.put(d.plusDays(i), false);
    }
    assertThatThrownBy(
            () ->
                DueExtension.evaluate(
                    null, MONDAY.plusSeconds(86_400), MONDAY, 5, Map.copyOf(allOff), VN))
        .isInstanceOf(IllegalStateException.class);
  }

  // ------------------------------------------------------------------ lý do, task, sơ đồ

  @Test
  void lyDoBatBuocVaToiDa1000KyTu() {
    assertThat(MonitorRules.reason(null)).isEmpty();
    assertThat(MonitorRules.reason("   ")).isEmpty();
    assertThat(MonitorRules.reason("x".repeat(1001))).isEmpty();
    assertThat(MonitorRules.reason("  Nghỉ phép  ")).contains("Nghỉ phép");
    assertThat(MonitorRules.reason("x".repeat(1000))).isPresent();
  }

  @Test
  void nhomUngVienChinhCungTenant() {
    UUID unit = UUID.randomUUID();
    CandidateGroup other = new CandidateGroup(UUID.randomUUID(), "leader", unit);
    CandidateGroup own = new CandidateGroup(TENANT, "appraiser", unit);
    MonitoredTask task =
        new MonitoredTask(
            "t", TENANT, "p", "review", null, List.of(other, own), 1, null, KEY, null, "m");
    assertThat(task.primaryCandidate()).isEqualTo(own);
    MonitoredTask direct =
        new MonitoredTask("t", TENANT, "p", "review", null, null, 1, null, KEY, null, "m");
    assertThat(direct.primaryCandidate()).isNull();
    assertThat(direct.candidateGroups()).isEmpty();
  }

  @Test
  void soDoGiuThuTuVaKhongNhanNull() {
    WorkflowDiagram d =
        new WorkflowDiagram(TENANT, KEY, "m", 2, "<definitions/>", null, List.of("start"));
    assertThat(d.activeActivityIds()).isEmpty();
    assertThat(d.completedActivityIds()).containsExactly("start");
    assertThatThrownBy(() -> new WorkflowDiagram(TENANT, KEY, "m", 2, null, null, null))
        .isInstanceOf(NullPointerException.class);
  }
}
