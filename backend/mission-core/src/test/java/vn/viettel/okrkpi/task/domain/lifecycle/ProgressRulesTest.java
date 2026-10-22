package vn.viettel.okrkpi.task.domain.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.domain.lifecycle.ProgressRules.Role;
import vn.viettel.okrkpi.task.domain.lifecycle.ProgressRules.Violation;

class ProgressRulesTest {

  @Test
  void quyTacBr0602Br0603() {
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.CHU_TRI, 40, 60, "")).isEmpty();
    assertThat(ProgressRules.check(TaskStatus.DA_GIAO, Role.CHU_TRI, 0, 10, null)).isEmpty();
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.KHAC, 0, 10, "x"))
        .contains(Violation.NOT_ASSIGNEE);
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.CHU_TRI, 0, 101, "x"))
        .contains(Violation.INVALID_PERCENT);
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.CHU_TRI, 0, -1, "x"))
        .contains(Violation.INVALID_PERCENT);
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.CHU_TRI, 0, 1, "x".repeat(2001)))
        .contains(Violation.INVALID_PERCENT);
    assertThat(ProgressRules.check(TaskStatus.DA_GIAO, Role.PHOI_HOP, 0, 0, "x"))
        .contains(Violation.INVALID_STATE);
    assertThat(ProgressRules.check(TaskStatus.CHO_NGHIEM_THU, Role.CHU_TRI, 0, 0, "x"))
        .contains(Violation.INVALID_STATE);
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.PHOI_HOP, 30, 50, "x"))
        .contains(Violation.COORDINATOR_PERCENT);
    assertThat(
            ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.PHOI_HOP, 30, 30, "Đã gửi số liệu"))
        .isEmpty();
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.CHU_TRI, 50, 40, " "))
        .contains(Violation.DECREASE_WITHOUT_NOTE);
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.CHU_TRI, 50, 40, null))
        .contains(Violation.DECREASE_WITHOUT_NOTE);
    assertThat(ProgressRules.check(TaskStatus.DANG_THUC_HIEN, Role.CHU_TRI, 50, 40, "Làm lại"))
        .isEmpty();
    assertThat(ProgressRules.startsWork(TaskStatus.DA_GIAO, Role.CHU_TRI)).isTrue();
    assertThat(ProgressRules.startsWork(TaskStatus.DA_GIAO, Role.PHOI_HOP)).isFalse();
    assertThat(ProgressRules.startsWork(TaskStatus.DANG_THUC_HIEN, Role.CHU_TRI)).isFalse();
  }
}
