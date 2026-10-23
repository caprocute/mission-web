package vn.viettel.okrkpi.task.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.api.TaskApi.ResponsibleItem;
import vn.viettel.okrkpi.task.infrastructure.persistence.JdbcTaskScoringReads.Node;
import vn.viettel.okrkpi.task.infrastructure.persistence.JdbcTaskScoringReads.Root;

/** Gán việc lá cho mục phụ trách gần nhất (CORE-04-13, BS-03 DC-03 "không cộng dồn hai lần"). */
class JdbcTaskScoringReadsTest {

  @Test
  void phanCuaCapDuoiKhongBiTinhHaiLan() {
    UUID a = UUID.randomUUID(); // nhiệm vụ người đó phụ trách
    UUID b = UUID.randomUUID(); // KPI con của a, người đó cũng phụ trách
    UUID c = UUID.randomUUID(); // việc trung gian dưới b, không phụ trách
    UUID l1 = UUID.randomUUID();
    UUID l2 = UUID.randomUUID();
    UUID l3 = UUID.randomUUID();
    UUID draft = UUID.randomUUID();
    List<Root> roots =
        List.of(
            new Root(a, "NHIEM_VU", new BigDecimal("4"), new BigDecimal("1")),
            new Root(b, "KPI", BigDecimal.ZERO, BigDecimal.ONE));
    List<Node> nodes =
        List.of(
            new Node(b, a, null, "DANG_THUC_HIEN", false),
            new Node(l1, a, new BigDecimal("5"), "HOAN_THANH", true),
            new Node(c, b, null, "DANG_THUC_HIEN", false),
            new Node(l2, c, new BigDecimal("10"), "HOAN_THANH", true),
            new Node(l3, b, null, "DA_GIAO", true),
            new Node(draft, b, new BigDecimal("99"), "NHAP", true));

    List<ResponsibleItem> items = JdbcTaskScoringReads.aggregate(roots, nodes);

    assertThat(items).hasSize(2);
    ResponsibleItem itemA = items.get(0);
    assertThat(itemA.itemType()).isEqualTo("NHIEM_VU");
    assertThat(itemA.leafCount()).isEqualTo(1);
    assertThat(itemA.sumStandardScore()).isEqualByComparingTo("5");
    assertThat(itemA.sumCompletedScore()).isEqualByComparingTo("5");
    assertThat(itemA.targetAchievedRatio()).isEqualByComparingTo("0.25");
    ResponsibleItem itemB = items.get(1);
    assertThat(itemB.itemType()).isEqualTo("KPI");
    assertThat(itemB.leafCount()).isEqualTo(2);
    assertThat(itemB.sumStandardScore()).isEqualByComparingTo("10");
    assertThat(itemB.sumCompletedScore()).isEqualByComparingTo("10");
    assertThat(itemB.targetAchievedRatio()).isNull();
  }

  @Test
  void viecLaKhongThuocMucNaoBiBoQua() {
    UUID a = UUID.randomUUID();
    List<ResponsibleItem> items =
        JdbcTaskScoringReads.aggregate(
            List.of(new Root(a, "NHIEM_VU", null, null)),
            List.of(
                new Node(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "DA_GIAO", true)));
    assertThat(items.get(0).leafCount()).isZero();
    assertThat(items.get(0).sumStandardScore()).isZero();
  }
}
