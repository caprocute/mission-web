package vn.viettel.okrkpi.task.domain.fromplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.domain.fromplan.PlanLineSync.Removal;
import vn.viettel.okrkpi.task.domain.fromplan.PlanLineSync.Update;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class PlanLineSyncTest {

  @Test
  void dongSuaChiCapNhatNhiemVuChuaBatDau() {
    assertThat(PlanLineSync.update(TaskStatus.NHAP)).isEqualTo(Update.REWRITE_DRAFT);
    assertThat(PlanLineSync.update(TaskStatus.DA_GIAO)).isEqualTo(Update.REFRESH_ASSIGNED);
    assertThat(PlanLineSync.update(TaskStatus.DANG_THUC_HIEN)).isEqualTo(Update.RELINK);
    assertThat(PlanLineSync.update(TaskStatus.HOAN_THANH)).isEqualTo(Update.RELINK);
  }

  @Test
  void dongBoHuyKhiChuaCoMinhChungXacNhan() {
    assertThat(PlanLineSync.removal(TaskStatus.HUY, true, false)).isEqualTo(Removal.NONE);
    assertThat(PlanLineSync.removal(TaskStatus.NHAP, false, false))
        .isEqualTo(Removal.DISCARD_DRAFT);
    assertThat(PlanLineSync.removal(TaskStatus.DA_GIAO, true, false)).isEqualTo(Removal.CANCEL);
    assertThat(PlanLineSync.removal(TaskStatus.DA_GIAO, true, true)).isEqualTo(Removal.BLOCKED);
    assertThat(PlanLineSync.removal(TaskStatus.CHO_NGHIEM_THU, false, false))
        .isEqualTo(Removal.BLOCKED);
    assertThat(PlanLineSync.mayCancel(TaskStatus.TAM_DUNG)).isTrue();
    assertThat(PlanLineSync.mayCancel(TaskStatus.HOAN_THANH)).isFalse();
  }

  @Test
  void dongLienDonViCanXacNhan() {
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    assertThat(LeadConfirmation.required(owner, null)).isFalse();
    assertThat(LeadConfirmation.required(owner, owner)).isFalse();
    assertThat(LeadConfirmation.issue(owner, other, null))
        .isEqualTo(LeadConfirmation.Issue.PENDING);
    assertThat(LeadConfirmation.issue(owner, other, LeadConfirmation.REJECTED))
        .isEqualTo(LeadConfirmation.Issue.REJECTED);
    assertThat(LeadConfirmation.issue(owner, other, LeadConfirmation.CONFIRMED))
        .isEqualTo(LeadConfirmation.Issue.NONE);
    assertThat(LeadConfirmation.issue(owner, owner, null)).isEqualTo(LeadConfirmation.Issue.NONE);
  }

  @Test
  void lyDoTuChoiBatBuoc() {
    assertThat(LeadConfirmation.reasonError(true, null)).isNull();
    assertThat(LeadConfirmation.reasonError(true, "x".repeat(1001))).isNotNull();
    assertThat(LeadConfirmation.reasonError(false, " ")).isNotNull();
    assertThat(LeadConfirmation.reasonError(false, null)).isNotNull();
    assertThat(LeadConfirmation.reasonError(false, "x".repeat(1001))).isNotNull();
    assertThat(LeadConfirmation.reasonError(false, "Không đúng chức năng")).isNull();
  }
}
