package vn.viettel.okrkpi.task.domain.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.domain.tree.TaskTree.AssignmentMode;
import vn.viettel.okrkpi.task.domain.tree.TaskTree.Child;

class TaskTreeTest {

  private static final BigDecimal FIFTY = new BigDecimal("50");

  @Test
  void khuonVaViTriTheoBac() {
    assertThat(TaskTree.template(null)).isEqualTo(TaskTree.KINDS);
    assertThat(TaskTree.template(List.of("NHIEM_VU", "LA"))).isEqualTo(TaskTree.KINDS);
    List<String> two = TaskTree.template(List.of("NHIEM_VU", "CONG_VIEC"));
    assertThat(two).containsExactly("NHIEM_VU", "CONG_VIEC");

    assertThat(TaskTree.placement(two, List.of(), "NHIEM_VU")).isNull();
    assertThat(TaskTree.placement(two, List.of(), "KPI")).isNotNull();
    assertThat(TaskTree.placement(two, List.of("NHIEM_VU"), "CONG_VIEC")).isNull();
    assertThat(TaskTree.placement(two, List.of("CONG_VIEC"), "NHIEM_VU")).isNotNull();
    assertThat(TaskTree.placement(TaskTree.KINDS, List.of("NHIEM_VU", "KPI"), "CONG_VIEC"))
        .isNull();
    assertThat(TaskTree.placement(TaskTree.KINDS, List.of("NHIEM_VU", "KPI"), "KPI")).isNotNull();
  }

  @Test
  void cheDoMacDinhKhongCoViPham() {
    assertThat(AssignmentMode.parse(null)).isEqualTo(AssignmentMode.CHU_TRI_PHOI_HOP);
    assertThat(AssignmentMode.parse("LA")).isEqualTo(AssignmentMode.CHU_TRI_PHOI_HOP);
    assertThat(
            TaskTree.violations(
                AssignmentMode.CHU_TRI_PHOI_HOP, true, 3, true, true, FIFTY, FIFTY, FIFTY))
        .isEmpty();
  }

  @Test
  void motNguoiMotViecChanHaiNguoiVaVuotDiem() {
    AssignmentMode one = AssignmentMode.parse("MOT_NGUOI_MOT_VIEC");
    assertThat(TaskTree.violations(one, true, 2, false, false, null, null, null))
        .containsExactly(TaskTree.ONE_PERSON);
    assertThat(TaskTree.violations(one, true, 1, true, false, null, null, null))
        .containsExactly(TaskTree.ONE_PERSON);
    assertThat(TaskTree.violations(one, false, 3, false, false, null, null, null)).isEmpty();
    assertThat(
            TaskTree.violations(
                one, true, 1, false, true, FIFTY, new BigDecimal("20"), new BigDecimal("30")))
        .isEmpty();
    assertThat(
            TaskTree.violations(
                one, true, 1, false, true, FIFTY, new BigDecimal("20"), new BigDecimal("30.001")))
        .containsExactly(TaskTree.SCORE_OVER);
    assertThat(TaskTree.violations(one, true, 1, false, true, FIFTY, null, null))
        .containsExactly(TaskTree.SCORE_MISSING);
  }

  @Test
  void viecChaChiXongKhiMoiViecConXong() {
    AssignmentMode one = AssignmentMode.MOT_NGUOI_MOT_VIEC;
    List<Child> half =
        List.of(
            new Child("HOAN_THANH", new BigDecimal("25")),
            new Child("DANG_THUC_HIEN", new BigDecimal("25")),
            new Child("HUY", new BigDecimal("10")));
    TaskTree.Rollup r = TaskTree.rollup(one, FIFTY, half);
    assertThat(r.children()).isEqualTo(2);
    assertThat(r.completed()).isEqualTo(1);
    assertThat(r.componentSum()).isEqualByComparingTo("50");
    assertThat(r.done()).isFalse();

    List<Child> all =
        List.of(
            new Child("HOAN_THANH", new BigDecimal("25")),
            new Child("HOAN_THANH", new BigDecimal("20")));
    assertThat(TaskTree.rollup(one, FIFTY, all).done()).isFalse();
    assertThat(TaskTree.rollup(AssignmentMode.CHU_TRI_PHOI_HOP, FIFTY, all).done()).isTrue();
    assertThat(TaskTree.rollup(one, FIFTY, List.of()).done()).isFalse();
  }
}
