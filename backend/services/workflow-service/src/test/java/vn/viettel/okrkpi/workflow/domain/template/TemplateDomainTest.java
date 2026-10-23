package vn.viettel.okrkpi.workflow.domain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Vòng đời phiên bản mẫu luồng, bốn mắt, khung cấm triển khai và ngân sách bộ test (BS-01). */
class TemplateDomainTest {

  static final UUID TENANT = UUID.randomUUID();
  static final UUID AUTHOR = UUID.randomUUID();
  static final UUID APPROVER = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-10-20T15:00:00Z");
  static final byte[] SHA = new byte[32];
  static final byte[] OTHER_SHA = filled(32, (byte) 7);

  static byte[] filled(int n, byte b) {
    byte[] out = new byte[n];
    java.util.Arrays.fill(out, b);
    return out;
  }

  static TemplateVersion draft() {
    return TemplateVersion.draft(
        UUID.randomUUID(),
        TENANT,
        "mau-thu",
        2,
        "<definitions/>",
        SHA,
        Map.of("type", "object"),
        AUTHOR,
        NOW);
  }

  static TemplateVersion pending() {
    return draft().markTested(SHA, AUTHOR, NOW).submit("lan-chay-1", AUTHOR, NOW);
  }

  @Test
  void vongDoiNhapDenHieuLuc() {
    TemplateVersion d = draft();
    assertThat(d.status()).isEqualTo(TemplateStatus.NHAP);
    assertThat(d.rowVersion()).isZero();
    assertThat(d.sha256Hex()).hasSize(64);
    TemplateVersion tested = d.markTested(SHA, AUTHOR, NOW);
    assertThat(tested.status()).isEqualTo(TemplateStatus.DAT_KIEM_THU);
    TemplateVersion submitted = tested.submit("lan-chay-1", AUTHOR, NOW);
    assertThat(submitted.status()).isEqualTo(TemplateStatus.CHO_PHE_DUYET);
    assertThat(submitted.testSuiteRef()).isEqualTo("lan-chay-1");
    TemplateVersion published = submitted.publish(new byte[] {1, 2}, APPROVER, null, NOW);
    assertThat(published.status()).isEqualTo(TemplateStatus.HIEU_LUC);
    assertThat(published.approvedBy()).isEqualTo(APPROVER);
    assertThat(published.signature()).containsExactly(1, 2);
    assertThat(published.testSuiteRef()).isEqualTo("lan-chay-1");
    assertThat(published.updatedBy()).isEqualTo(APPROVER);
    assertThat(published.status().released()).isTrue();
    assertThat(submitted.publish(new byte[] {3}, APPROVER, "abc", NOW).testSuiteRef())
        .isEqualTo("abc");
  }

  @Test
  void suaSauKhiDatKiemThuDuaVeNhap() {
    TemplateVersion tested = draft().markTested(SHA, AUTHOR, NOW);
    TemplateVersion edited = tested.edit("<x/>", OTHER_SHA, Map.of(), AUTHOR, NOW);
    assertThat(edited.status()).isEqualTo(TemplateStatus.NHAP);
    assertThat(edited.sameContent(OTHER_SHA)).isTrue();
    assertThat(edited.sameContent(SHA)).isFalse();
    assertThat(edited.sameContent(null)).isFalse();
  }

  @Test
  void phienBanHieuLucKhongSuaKhongChayTestDuoc() {
    TemplateVersion published = pending().publish(new byte[] {1}, APPROVER, null, NOW);
    assertThatThrownBy(() -> published.edit("<x/>", OTHER_SHA, Map.of(), AUTHOR, NOW))
        .isInstanceOf(TemplateRuleViolation.class)
        .extracting(e -> ((TemplateRuleViolation) e).reason())
        .isEqualTo(TemplateRuleViolation.Reason.BAT_BIEN);
    assertThatThrownBy(published::requireTestable).isInstanceOf(TemplateRuleViolation.class);
    assertThatThrownBy(() -> pending().edit("<x/>", OTHER_SHA, Map.of(), AUTHOR, NOW))
        .isInstanceOf(TemplateRuleViolation.class);
  }

  @Test
  void khongDeNghiKhiChuaDatKiemThu() {
    assertThatThrownBy(() -> draft().submit(null, AUTHOR, NOW))
        .extracting(e -> ((TemplateRuleViolation) e).reason())
        .isEqualTo(TemplateRuleViolation.Reason.CHUA_KIEM_THU);
    assertThatThrownBy(() -> draft().markTested(OTHER_SHA, AUTHOR, NOW))
        .extracting(e -> ((TemplateRuleViolation) e).reason())
        .isEqualTo(TemplateRuleViolation.Reason.CHUA_KIEM_THU);
  }

  @Test
  void nguoiSoanKhongTuPheDuyet() {
    TemplateVersion p = pending();
    assertThatThrownBy(() -> p.requireApprover(AUTHOR))
        .extracting(e -> ((TemplateRuleViolation) e).reason())
        .isEqualTo(TemplateRuleViolation.Reason.CUNG_NGUOI_SOAN);
    UUID submitter = UUID.randomUUID();
    TemplateVersion bySubmitter = draft().markTested(SHA, AUTHOR, NOW).submit("r", submitter, NOW);
    assertThatThrownBy(() -> bySubmitter.requireApprover(submitter))
        .isInstanceOf(TemplateRuleViolation.class);
    p.requireApprover(APPROVER);
    assertThatThrownBy(() -> draft().requireApprover(APPROVER))
        .extracting(e -> ((TemplateRuleViolation) e).reason())
        .isEqualTo(TemplateRuleViolation.Reason.KHONG_CHO_PHE_DUYET);
    assertThatThrownBy(() -> p.publish(null, APPROVER, null, NOW))
        .extracting(e -> ((TemplateRuleViolation) e).reason())
        .isEqualTo(TemplateRuleViolation.Reason.THIEU_CHU_KY);
    assertThatThrownBy(() -> p.publish(new byte[0], APPROVER, null, NOW))
        .isInstanceOf(TemplateRuleViolation.class);
  }

  @Test
  void kiemTraDuLieuVaBangNhau() {
    TemplateVersion d = draft();
    assertThat(d.withRowVersion(3, NOW).rowVersion()).isEqualTo(3);
    assertThat(d).isEqualTo(d.withRowVersion(0, NOW)).hasSameHashCodeAs(d.withRowVersion(0, NOW));
    assertThat(d).isNotEqualTo(d.withRowVersion(1, NOW)).isNotEqualTo("khac");
    assertThat(d.toString()).contains("mau-thu@2").contains("NHAP");
    byte[] copy = d.bpmnSha256();
    copy[0] = 9;
    assertThat(d.bpmnSha256()[0]).isZero();
    assertThat(d.signature()).isNull();
    assertThatThrownBy(
            () ->
                TemplateVersion.draft(
                    UUID.randomUUID(), TENANT, "m", 0, "<x/>", SHA, null, AUTHOR, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateVersion.draft(
                    UUID.randomUUID(), TENANT, "m", 1, "<x/>", new byte[3], null, AUTHOR, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            TemplateVersion.draft(UUID.randomUUID(), TENANT, "m", 1, "<x/>", SHA, null, AUTHOR, NOW)
                .paramSchema())
        .isEmpty();
    assertThat(TemplateStatus.NHAP.editable()).isTrue();
    assertThat(TemplateStatus.CHO_PHE_DUYET.editable()).isFalse();
    assertThat(TemplateStatus.CHO_PHE_DUYET.released()).isFalse();
    assertThat(TemplateStatus.NGUNG.released()).isTrue();
  }

  @Test
  void khungCamTrienKhai() {
    ZoneId hcm = ZoneId.of("Asia/Ho_Chi_Minh");
    RolloutWindow w =
        new RolloutWindow(
            hcm,
            List.of(RolloutWindow.PeakHours.parse("08:00-11:30")),
            false,
            List.of(LocalDate.of(2026, 12, 31)),
            RolloutWindow.DEFAULT_FREEZE_DAYS);
    // Thứ Ba 20/10/2026 09:00 giờ Việt Nam: giờ cao điểm.
    assertThat(w.blockedAt(Instant.parse("2026-10-20T02:00:00Z"))).isPresent();
    // 22:00 cùng ngày: được triển khai.
    assertThat(w.blockedAt(Instant.parse("2026-10-20T15:00:00Z"))).isEmpty();
    // Thứ Bảy 24/10/2026 09:00: cuối tuần không tính giờ cao điểm.
    assertThat(w.blockedAt(Instant.parse("2026-10-24T02:00:00Z"))).isEmpty();
    // 29/12 đến 31/12: ba ngày cuối kỳ; 28/12 thì không.
    assertThat(w.blockedAt(Instant.parse("2026-12-29T15:00:00Z"))).isPresent();
    assertThat(w.blockedAt(Instant.parse("2026-12-31T15:00:00Z"))).isPresent();
    assertThat(w.blockedAt(Instant.parse("2026-12-28T15:00:00Z"))).isEmpty();
    RolloutWindow weekends =
        new RolloutWindow(
            hcm,
            List.of(new RolloutWindow.PeakHours(LocalTime.of(8, 0), LocalTime.of(9, 0))),
            true,
            null,
            0);
    assertThat(weekends.blockedAt(Instant.parse("2026-10-24T01:30:00Z"))).isPresent();
    assertThat(weekends.periodEnds()).isEmpty();
    assertThatThrownBy(() -> RolloutWindow.PeakHours.parse("08:00"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RolloutWindow.PeakHours.parse("10:00-09:00"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RolloutWindow(hcm, null, false, null, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nganSachBoTest() {
    TestCaseResult ok = new TestCaseResult("A", "Tham số mặc định", "DA_DUYET", true, 2, 13);
    TestCaseResult heavy = new TestCaseResult("B", "Tham số mặc định", "DA_DUYET", true, 1, 113);
    TestCaseResult failed = new TestCaseResult("C", "Tham số mặc định", "TRA_LAI", false, 1, 5);
    TestReport good = new TestReport(List.of(ok), 1, TestReport.DEFAULT_SQL_BUDGET);
    assertThat(good.passed()).isTrue();
    assertThat(good.summary()).isEqualTo("Đạt 1/1 ca.");
    TestReport over = new TestReport(List.of(ok, heavy), 1, 20);
    assertThat(over.withinBudget()).isFalse();
    assertThat(over.passed()).isFalse();
    assertThat(over.maxSqlPerCompletion()).isEqualTo(113);
    assertThat(over.summary()).contains("113").contains("vượt ngân sách 20");
    TestReport notAll = new TestReport(List.of(ok, failed), 0, 20);
    assertThat(notAll.passedCount()).isEqualTo(1);
    assertThat(notAll.passed()).isFalse();
    TestReport empty = new TestReport(List.of(), 0, 20);
    assertThat(empty.passed()).isFalse();
    assertThat(empty.summary()).contains("Không chạy được");
    assertThatThrownBy(() -> new TestReport(List.of(), 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TestCaseResult("A", "x", "y", true, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lanChayVaTienDo() {
    UUID id = UUID.randomUUID();
    TestRun waiting =
        new TestRun(id, TENANT, UUID.randomUUID(), SHA, TestRunStatus.CHO, null, AUTHOR, NOW);
    assertThat(waiting.progress()).isZero();
    assertThat(waiting.message()).contains("chờ");
    assertThat(waiting.status().terminal()).isFalse();
    TestRun running =
        new TestRun(id, TENANT, UUID.randomUUID(), SHA, TestRunStatus.DANG_CHAY, null, AUTHOR, NOW);
    assertThat(running.progress()).isEqualTo(50);
    assertThat(running.message()).contains("Đang chạy");
    TestReport report =
        new TestReport(List.of(new TestCaseResult("A", "p", "e", true, 1, 3)), 0, 20);
    TestRun done =
        new TestRun(
            id, TENANT, UUID.randomUUID(), SHA, TestRunStatus.HOAN_TAT, report, AUTHOR, NOW);
    assertThat(done.progress()).isEqualTo(100);
    assertThat(done.message()).isEqualTo("Đạt 1/1 ca.");
    TestRun broken =
        new TestRun(id, TENANT, UUID.randomUUID(), null, TestRunStatus.THAT_BAI, null, AUTHOR, NOW);
    assertThat(broken.message()).contains("Không chạy được");
    assertThat(broken.bpmnSha256()).isNull();
    TestRun same =
        new TestRun(id, TENANT, UUID.randomUUID(), null, TestRunStatus.HOAN_TAT, null, AUTHOR, NOW);
    assertThat(done).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(broken);
    assertThat(done).isNotEqualTo("x");
    assertThat(done.toString()).contains(id.toString());
    assertThat(done.bpmnSha256()).hasSize(32);
  }

  @Test
  void deNghiPhatHanhVaTienDoCell() {
    UUID t = UUID.randomUUID();
    Release open = new Release(t, "mr-1", null, ReleaseStatus.MO, AUTHOR, null, null, null, null);
    assertThat(open.signed()).isFalse();
    Release signed =
        new Release(
            t, "mr-1", "u", ReleaseStatus.DA_KY, AUTHOR, APPROVER, null, new byte[] {5}, "p");
    assertThat(signed.signed()).isTrue();
    assertThat(signed.signature()).containsExactly(5);
    Release signedAgain =
        new Release(t, "mr-2", null, ReleaseStatus.DA_KY, AUTHOR, null, null, new byte[] {6}, null);
    assertThat(signed).isEqualTo(signedAgain).hasSameHashCodeAs(signedAgain);
    assertThat(signed).isNotEqualTo(open).isNotEqualTo("x");
    assertThat(signed.toString()).contains("mr-1");
    assertThat(open.signature()).isNull();
    CellRollout r = new CellRollout(t, "CELL-A", 1, RolloutStatus.CHO, null, null, null);
    assertThat(r.cellCode()).isEqualTo("CELL-A");
    assertThatThrownBy(() -> new CellRollout(t, "CELL-A", 0, RolloutStatus.CHO, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    BindingUsage usage = new BindingUsage(TENANT, t, "OKR", "DANG", null);
    assertThat(usage.params()).isEmpty();
    assertThat(usage.label()).startsWith("Tham số ").hasSize("Tham số ".length() + 8);
    TemplateLintFinding f = new TemplateLintFinding("R1", null, "m");
    assertThat(f.element()).isNull();
    TemplateRuleViolation v = new TemplateRuleViolation(TemplateRuleViolation.Reason.BAT_BIEN, "x");
    assertThat(v.getMessage()).isEqualTo("x");
  }
}
