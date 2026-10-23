package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.application.plan.PlanAdjustments.Change;
import vn.viettel.okrkpi.task.application.plan.PlanAdjustments.Diff;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Check;
import vn.viettel.okrkpi.task.application.plan.port.PlanLineFacts.LineFact;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.RemovedItem;

/** CORE-02-10: so sánh phiên bản, lý do từng thay đổi, BR-04-11, BR-04-12, giới hạn và hạn. */
class PlanAdjustmentsTest {

  private static final UUID UNIT = UUID.randomUUID();
  private static final LocalDate DUE = LocalDate.of(2026, 11, 30);

  @Test
  void soSanh300DongPhanLoaiDungThemSuaBo() {
    List<ItemRow> base = new ArrayList<>();
    for (int i = 1; i <= 300; i++) {
      base.add(item(UUID.randomUUID(), i, "Việc " + i, null, null));
    }
    List<ItemRow> next = new ArrayList<>();
    int no = 1;
    for (int i = 0; i < 300; i++) {
      if (i < 3) {
        continue; // ba dòng bỏ
      }
      ItemRow b = base.get(i);
      String title = i < 8 ? b.title() + " (sửa)" : b.title();
      next.add(item(UUID.randomUUID(), no++, title, b.id(), i < 8 ? "Theo chỉ đạo" : null));
    }
    for (int i = 0; i < 10; i++) {
      next.add(item(UUID.randomUUID(), no++, "Việc mới " + i, null, "Phát sinh"));
    }
    List<RemovedItem> removed =
        base.subList(0, 3).stream().map(b -> new RemovedItem(b.id(), "Không còn phù hợp")).toList();

    long start = System.nanoTime();
    Diff diff = PlanAdjustments.diff(1, 2, base, next, removed);
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertThat(diff.count(Change.THEM)).isEqualTo(10);
    assertThat(diff.count(Change.SUA)).isEqualTo(5);
    assertThat(diff.count(Change.BO)).isEqualTo(3);
    assertThat(diff.count(Change.KHONG_DOI)).isEqualTo(292);
    assertThat(diff.lines().stream().filter(l -> l.change() == Change.SUA).findFirst())
        .get()
        .satisfies(l -> assertThat(l.changedFields()).containsExactly("title"));
    assertThat(PlanAdjustments.checks(diff, Map.of())).isEmpty();
    // Chỉ để phát hiện thuật toán bậc hai, không phải bài đo hiệu năng (YC-PT-19).
    assertThat(millis).isLessThan(2_000);
  }

  @Test
  void thieuLyDoThemSuaBoBiChan() {
    ItemRow a = item(UUID.randomUUID(), 1, "Việc A", null, null);
    ItemRow b = item(UUID.randomUUID(), 2, "Việc B", null, null);
    List<ItemRow> next =
        List.of(
            item(UUID.randomUUID(), 1, "Việc A đã sửa", a.id(), " "),
            item(UUID.randomUUID(), 2, "Việc mới", null, null));
    Diff diff = PlanAdjustments.diff(1, 2, List.of(a, b), next, List.of());

    List<Check> checks = PlanAdjustments.checks(diff, Map.of());

    assertThat(checks).extracting(Check::rule).containsOnly(PlanAdjustments.BR_04_10);
    assertThat(checks).extracting(Check::lineNo).containsExactly(1, 2, null);
    assertThat(checks.get(2).message()).contains("Việc B");
    assertThat(checks).allMatch(c -> c.level() == PlanChecker.Level.CHAN);
  }

  @Test
  void khongBoViecCoMinhChungXacNhanVaKhongDoiHanViecDangLam() {
    ItemRow done = item(UUID.randomUUID(), 1, "Báo cáo chuyên đề", null, null);
    ItemRow doing = item(UUID.randomUUID(), 2, "Thẩm định hồ sơ", null, null);
    ItemRow draft = item(UUID.randomUUID(), 3, "Rà soát", null, null);
    ItemRow moved = withDue(item(UUID.randomUUID(), 1, doing.title(), doing.id(), "Lùi hạn"));
    ItemRow movedDraft = withDue(item(UUID.randomUUID(), 2, draft.title(), draft.id(), "Lùi"));
    Diff diff =
        PlanAdjustments.diff(
            1,
            2,
            List.of(done, doing, draft),
            List.of(moved, movedDraft),
            List.of(new RemovedItem(done.id(), "Bỏ")));
    Map<UUID, LineFact> facts =
        Map.of(
            done.id(), new LineFact(done.id(), "HOAN_THANH", true),
            doing.id(), new LineFact(doing.id(), "DANG_THUC_HIEN", false),
            draft.id(), new LineFact(draft.id(), "DA_GIAO", false));

    List<Check> checks = PlanAdjustments.checks(diff, facts);

    assertThat(checks).extracting(Check::msg).containsExactlyInAnyOrder("MSG-04-12", "MSG-04-11");
    assertThat(checks).allMatch(PlanAdjustments::protectsResults);
    assertThat(checks.stream().filter(c -> "MSG-04-11".equals(c.msg())).findFirst().get().message())
        .isEqualTo("Không bỏ được việc \"Báo cáo chuyên đề\" vì đã có minh chứng được xác nhận.");
  }

  @Test
  void hangDongSoSanhVaDiemDanhMuc() {
    UUID catalog = UUID.randomUUID();
    ItemRow b = scored(item(UUID.randomUUID(), 1, "Việc", null, null), catalog, "10");
    ItemRow sameScore = scored(item(UUID.randomUUID(), 1, "Việc", b.id(), null), catalog, "10.0");
    ItemRow otherScore = scored(item(UUID.randomUUID(), 1, "Việc", b.id(), "Đổi"), catalog, "20");
    ItemRow offCatalog = scored(item(UUID.randomUUID(), 1, "Việc", b.id(), "Đổi"), null, null);
    assertThat(PlanAdjustments.changedFields(b, sameScore)).isEmpty();
    assertThat(PlanAdjustments.changedFields(b, otherScore)).containsExactly("standardScore");
    assertThat(PlanAdjustments.changedFields(b, offCatalog)).containsExactly("catalogItemId");

    ItemRow other =
        new ItemRow(
            UUID.randomUUID(),
            1,
            null,
            null,
            UUID.randomUUID(),
            "Việc",
            "THUONG_XUYEN",
            new Assignee("PERSON", UUID.randomUUID(), ""),
            List.of(new Assignee("UNIT", UNIT, "")),
            "Sản phẩm",
            new BigDecimal("2"),
            "bản",
            null,
            null,
            DUE.plusDays(1),
            b.id(),
            "Đổi",
            "HANG_THANG");
    assertThat(PlanAdjustments.changedFields(item(b.id(), 1, "Khác", null, null), other))
        .containsExactly(
            "title",
            "taskGroup",
            "leadAssignee",
            "coAssignees",
            "keyResultId",
            "quantity",
            "unit",
            "dueDate",
            "recurrence");

    // Hai dòng cùng trỏ một dòng gốc: dòng sau là dòng thêm.
    Diff dup =
        PlanAdjustments.diff(
            1,
            2,
            List.of(b),
            List.of(
                sameScore, scored(item(UUID.randomUUID(), 2, "Việc", b.id(), "X"), catalog, "10")),
            List.of());
    assertThat(dup.lines())
        .extracting(PlanAdjustments.Line::change)
        .containsExactly(Change.KHONG_DOI, Change.THEM);
  }

  @Test
  void gioiHanVaHanDieuChinh() {
    assertThat(PlanAdjustments.allowed(2, 0)).isEqualTo(2);
    assertThat(PlanAdjustments.allowed(2, 1)).isEqualTo(3);
    assertThat(PlanAdjustments.allowed(-1, -1)).isZero();
    assertThat(PlanAdjustments.cutoff(LocalDate.of(2026, 12, 31), 1))
        .isEqualTo(LocalDate.of(2026, 12, 1));
    assertThat(PlanAdjustments.cutoff(LocalDate.of(2026, 12, 31), 2))
        .isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(PlanAdjustments.cutoff(LocalDate.of(2026, 12, 31), 0))
        .isEqualTo(LocalDate.of(2026, 12, 1));
  }

  @Test
  void tyTrongNhomDoiKhiTongDiemNhomDoi() {
    UUID catalog = UUID.randomUUID();
    ItemRow a = scored(item(UUID.randomUUID(), 1, "A", null, null), catalog, "30");
    ItemRow b = scored(item(UUID.randomUUID(), 1, "A", a.id(), null), catalog, "30.00");
    ItemRow c = scored(item(UUID.randomUUID(), 1, "A", a.id(), null), catalog, "40");
    assertThat(PlanAdjustments.groupWeightChanged(List.of(a), List.of(b))).isFalse();
    assertThat(PlanAdjustments.groupWeightChanged(List.of(a), List.of(c))).isTrue();
    assertThat(
            PlanAdjustments.groupWeightChanged(List.of(), List.of(item(null, 1, "x", null, null))))
        .isTrue();
  }

  private static ItemRow item(UUID id, int no, String title, UUID origin, String reason) {
    return new ItemRow(
        id,
        no,
        null,
        null,
        null,
        title,
        "THEN_CHOT",
        new Assignee("UNIT", UNIT, ""),
        List.of(),
        title,
        BigDecimal.ONE,
        "báo cáo",
        null,
        null,
        DUE,
        origin,
        reason,
        null);
  }

  private static ItemRow withDue(ItemRow i) {
    return new ItemRow(
        i.id(),
        i.itemNo(),
        i.catalogItemId(),
        i.catalogCode(),
        i.keyResultId(),
        i.title(),
        i.taskGroup(),
        i.lead(),
        i.coAssignees(),
        i.productName(),
        i.quantity(),
        i.unit(),
        i.difficulty(),
        i.standardScore(),
        DUE.plusDays(10),
        i.originItemId(),
        i.changeReason(),
        i.recurrence());
  }

  private static ItemRow scored(ItemRow i, UUID catalog, String score) {
    return new ItemRow(
        i.id(),
        i.itemNo(),
        catalog,
        null,
        i.keyResultId(),
        i.title(),
        i.taskGroup(),
        i.lead(),
        i.coAssignees(),
        i.productName(),
        i.quantity(),
        i.unit(),
        null,
        score == null ? null : new BigDecimal(score),
        i.dueDate(),
        i.originItemId(),
        i.changeReason(),
        i.recurrence());
  }
}
