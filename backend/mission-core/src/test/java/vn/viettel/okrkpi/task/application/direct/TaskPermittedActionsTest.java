package vn.viettel.okrkpi.task.application.direct;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

/** Thao tác của người tham gia theo vai trò và trạng thái (YC-PT-21). */
class TaskPermittedActionsTest {

  @Test
  void viecCuaToi() {
    assertThat(TaskPermittedActions.mine(TaskStatus.DA_GIAO, "CHU_TRI"))
        .containsExactly(TaskPermittedActions.NHAN_VIEC, TaskPermittedActions.TIEN_DO);
    assertThat(TaskPermittedActions.mine(TaskStatus.DA_GIAO, "PHOI_HOP")).isEmpty();
    assertThat(TaskPermittedActions.mine(TaskStatus.DANG_THUC_HIEN, "PHOI_HOP"))
        .containsExactly(TaskPermittedActions.TIEN_DO);
    assertThat(TaskPermittedActions.mine(TaskStatus.DANG_THUC_HIEN, null)).isEmpty();
    assertThat(TaskPermittedActions.mine(TaskStatus.HOAN_THANH, "CHU_TRI")).isEmpty();
  }

  @Test
  void chiTiet() {
    assertThat(TaskPermittedActions.detail(TaskStatus.DANG_THUC_HIEN, "CHU_TRI"))
        .containsExactly(TaskPermittedActions.TIEN_DO_CHI_TIET);
    assertThat(TaskPermittedActions.detail(TaskStatus.CHO_NGHIEM_THU, "CHU_TRI")).isEmpty();
    assertThat(TaskPermittedActions.detail(TaskStatus.DANG_THUC_HIEN, "KHAC")).isEmpty();
  }
}
