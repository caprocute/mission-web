package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;

/** CORE-02-08: băm SHA-256 trên biểu diễn chuẩn hoá của phiên bản kế hoạch. */
class PlanContentHashTest {

  private static final UUID A = UUID.fromString("01927a02-0000-7000-8000-0000000000c1");
  private static final UUID B = UUID.fromString("01927a02-0000-7000-8000-0000000000c2");
  private static final PlanRow PLAN =
      new PlanRow(
          UUID.fromString("01927a02-0000-7000-8000-0000000000d1"),
          UUID.fromString("01927a02-0000-7000-8000-0000000000d2"),
          20271,
          "TAP_THE",
          A,
          null,
          null,
          0);

  private static ItemRow line(int n, String title, String quantity, List<Assignee> co) {
    return new ItemRow(
        UUID.randomUUID(),
        n,
        null,
        null,
        null,
        title,
        "THEN_CHOT",
        new Assignee("UNIT", A, "Tên không vào băm " + n),
        co,
        "Báo cáo",
        new BigDecimal(quantity),
        "báo cáo",
        null,
        null,
        LocalDate.parse("2027-03-20"),
        null,
        null,
        null);
  }

  private static List<ItemRow> items() {
    return List.of(
        line(1, "Giao ban | định kỳ", "1.50", List.of()),
        line(
            2,
            "Đề án\nchuyển đổi số",
            "10",
            List.of(new Assignee("UNIT", B, ""), new Assignee("PERSON", A, ""))),
        line(3, "Tổng kết", "2.0", List.of()));
  }

  @Test
  void cungNoiDungKhacThuTuDocChoCungBam() {
    List<ItemRow> shuffled = new ArrayList<>(items());
    Collections.reverse(shuffled);
    List<ItemRow> coReordered = new ArrayList<>(items());
    ItemRow two = coReordered.get(1);
    coReordered.set(
        1,
        line(
            2, two.title(), "10.000", List.of(two.coAssignees().get(1), two.coAssignees().get(0))));
    byte[] h = PlanContentHash.of(PLAN, 1, items());
    assertThat(PlanContentHash.of(PLAN, 1, shuffled)).isEqualTo(h);
    assertThat(PlanContentHash.of(PLAN, 1, coReordered)).isEqualTo(h);
    assertThat(h).hasSize(32);
  }

  @Test
  void doiMotKyTuLamBamKhac() {
    List<ItemRow> changed = new ArrayList<>(items());
    changed.set(2, line(3, "Tổng kêt", "2.0", List.of()));
    assertThat(PlanContentHash.of(PLAN, 1, changed))
        .isNotEqualTo(PlanContentHash.of(PLAN, 1, items()));
    assertThat(PlanContentHash.of(PLAN, 2, items()))
        .isNotEqualTo(PlanContentHash.of(PLAN, 1, items()));
  }

  @Test
  void bieuDienChuanHoa() {
    String[] rows = PlanContentHash.canonical(PLAN, 1, items()).split("\n");
    assertThat(rows[0])
        .isEqualTo(
            "KE_HOACH|v1|01927a02-0000-7000-8000-0000000000d1|01927a02-0000-7000-8000-0000000000d2"
                + "|01927a02-0000-7000-8000-0000000000c1||1");
    assertThat(rows[1])
        .isEqualTo(
            "1|||Giao ban \\| định kỳ|THEN_CHOT|UNIT:01927a02-0000-7000-8000-0000000000c1||Báo cáo"
                + "|1.5|báo cáo|||2027-03-20|||");
    assertThat(rows[2])
        .contains("Đề án\\nchuyển đổi số")
        .contains(
            "|PERSON:01927a02-0000-7000-8000-0000000000c1,UNIT:01927a02-0000-7000-8000-0000000000c2|")
        .contains("|10|");
    assertThat(rows[3]).contains("|2|");
  }
}
