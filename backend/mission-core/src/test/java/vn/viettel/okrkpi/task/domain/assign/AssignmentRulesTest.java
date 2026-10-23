package vn.viettel.okrkpi.task.domain.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.domain.assign.AssignmentRules.CheckItem;
import vn.viettel.okrkpi.task.domain.assign.AssignmentRules.Context;

class AssignmentRulesTest {

  static final UUID LEAD = UUID.randomUUID();
  static final UUID ACCEPTOR = UUID.randomUUID();
  static final LocalDate START = LocalDate.of(2026, 10, 1);
  static final LocalDate END = LocalDate.of(2026, 12, 31);

  static TaskDraftSpec spec(
      String source, UUID lead, List<TaskDraftSpec.Assignee> co, UUID acceptor, String basis) {
    return new TaskDraftSpec(
        UUID.randomUUID(),
        source,
        "KE_HOACH".equals(source) ? UUID.randomUUID() : null,
        null,
        "Hoàn thành báo cáo quý",
        "THUONG_XUYEN",
        null,
        "TRUNG_BINH",
        lead,
        co,
        LocalDate.of(2026, 11, 30),
        acceptor,
        List.of(),
        "Báo cáo",
        BigDecimal.ONE,
        "bản",
        "Đúng mẫu",
        "Tệp báo cáo",
        null,
        null,
        basis);
  }

  static Context ctx(boolean approved) {
    return new Context(START, END, approved, ACCEPTOR, List.of("hỗ trợ", " "));
  }

  static Map<String, Boolean> passed(List<CheckItem> items) {
    return items.stream().collect(Collectors.toMap(CheckItem::rule, CheckItem::passed));
  }

  @Test
  void banHopLeQuaMoiQuyTac() {
    List<CheckItem> items =
        AssignmentRules.check(spec("KE_HOACH", LEAD, List.of(), null, null), ctx(true));
    assertThat(items).allMatch(CheckItem::passed);
    assertThat(AssignmentRules.blocked(items)).isFalse();
  }

  @Test
  void chuTriTrungNguoiPhoiHopVaNghiemThuTrungNguoiThucHien() {
    UUID co = UUID.randomUUID();
    var s =
        spec(
            "DOT_XUAT",
            LEAD,
            List.of(
                new TaskDraftSpec.Assignee("PERSON", LEAD),
                new TaskDraftSpec.Assignee("PERSON", co)),
            co,
            "Chỉ đạo tại cuộc họp");
    var r = passed(AssignmentRules.check(s, ctx(false)));
    assertThat(r).containsEntry("BR-05-01", false).containsEntry("BR-05-02", false);
    assertThat(r).containsEntry("BR-05-09", true).containsEntry("BR-05-04", true);
  }

  @Test
  void thieuChuTriVaThieuNguoiNghiemThu() {
    var r =
        passed(
            AssignmentRules.check(
                spec("DOT_XUAT", null, List.of(), null, null),
                new Context(START, END, false, null, null)));
    assertThat(r)
        .containsEntry("BR-05-01", false)
        .containsEntry("BR-05-02", false)
        .containsEntry("BR-05-09", false)
        .containsEntry("BR-04-03", true);
  }

  @Test
  void donViPhoiHopKhongTinhLaNguoi() {
    var s = spec("VAN_BAN", LEAD, List.of(new TaskDraftSpec.Assignee("UNIT", ACCEPTOR)), null, "");
    var s2 =
        new TaskDraftSpec(
            s.periodId(),
            s.source(),
            null,
            null,
            s.title(),
            s.taskGroup(),
            null,
            s.priority(),
            LEAD,
            s.coAssignees(),
            s.dueDate(),
            null,
            List.of(),
            s.productName(),
            s.quantity(),
            s.unit(),
            s.evidenceRequired(),
            s.qualityCriteria(),
            "123/QĐ",
            null,
            null);
    var r = passed(AssignmentRules.check(s2, ctx(false)));
    assertThat(r).containsEntry("BR-05-01", true).containsEntry("BR-05-02", true);
    assertThat(r).containsEntry("BR-05-09", true);
  }

  @Test
  void keHoachChuaDuyetVaHanNgoaiKy() {
    var s = spec("KE_HOACH", LEAD, List.of(), null, null);
    var late =
        new TaskDraftSpec(
            s.periodId(),
            s.source(),
            s.planItemId(),
            null,
            "Hỗ trợ đơn vị bạn",
            s.taskGroup(),
            null,
            s.priority(),
            LEAD,
            List.of(),
            END.plusDays(1),
            null,
            List.of(),
            "",
            null,
            "",
            "",
            "",
            null,
            null,
            null);
    List<CheckItem> items = AssignmentRules.check(late, ctx(false));
    var r = passed(items);
    assertThat(r)
        .containsEntry("BR-05-03", false)
        .containsEntry("BR-05-04", false)
        .containsEntry("BR-04-03", false);
    assertThat(AssignmentRules.blocked(items)).isTrue();
    var early =
        new TaskDraftSpec(
            s.periodId(),
            s.source(),
            null,
            null,
            null,
            s.taskGroup(),
            null,
            s.priority(),
            LEAD,
            null,
            START.minusDays(1),
            null,
            null,
            "SP",
            BigDecimal.ZERO,
            "c",
            "q",
            "e",
            null,
            null,
            null);
    assertThat(passed(AssignmentRules.check(early, ctx(true))))
        .containsEntry("BR-05-03", false)
        .containsEntry("BR-05-04", false)
        .containsEntry("BR-04-03", true);
  }

  @Test
  void hanTruocDauKyKhongDat() {
    var s = spec("DOT_XUAT", LEAD, List.of(), null, "Có căn cứ");
    var early =
        new TaskDraftSpec(
            s.periodId(),
            s.source(),
            null,
            null,
            s.title(),
            s.taskGroup(),
            null,
            s.priority(),
            LEAD,
            List.of(),
            START.minusDays(1),
            null,
            List.of(),
            "SP",
            BigDecimal.TEN,
            "c",
            "q",
            "e",
            null,
            null,
            s.basis());
    assertThat(passed(AssignmentRules.check(early, ctx(true)))).containsEntry("BR-05-03", false);
    assertThat(passed(AssignmentRules.check(early.withLead(ACCEPTOR), ctx(true))))
        .containsEntry("BR-05-02", false);
  }

  @Test
  void maNhiemVuTheoKy() {
    assertThat(TaskCodes.code(20264, 7)).isEqualTo("NV-2026-Q4-0007");
    assertThat(TaskCodes.prefix(20260)).isEqualTo("NV-2026-N-");
    assertThat(TaskCodes.sequenceOf(20264, "NV-2026-Q4-0123")).isEqualTo(123);
    assertThat(TaskCodes.sequenceOf(20264, "NV-2026-Q3-0123")).isZero();
    assertThat(TaskCodes.sequenceOf(20264, null)).isZero();
    assertThat(TaskCodes.sequenceOf(20264, "NV-2026-Q4-x")).isZero();
    assertThatThrownBy(() -> TaskCodes.code(20264, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dangKyPhaiDuocDuyetTruocKhiGiao() {
    TaskDraftSpec s = spec("DANG_KY", LEAD, List.of(), null, null);
    assertThat(passed(AssignmentRules.check(s, ctx(true))).get("BR-05-08")).isFalse();
    Context ok = new Context(START, END, true, ACCEPTOR, List.of("hỗ trợ"), true);
    assertThat(passed(AssignmentRules.check(s, ok)).get("BR-05-08")).isTrue();
    assertThat(passed(AssignmentRules.check(spec("KE_HOACH", LEAD, List.of(), null, null), ok)))
        .doesNotContainKey("BR-05-08");
  }
}
