package vn.viettel.okrkpi.workflow.application.dual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.port.DualApprovalProcesses;
import vn.viettel.okrkpi.workflow.application.stage.StageProcessService.Outcome;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval.Branch;
import vn.viettel.okrkpi.workflow.domain.stage.OverdueAction;
import vn.viettel.okrkpi.workflow.domain.stage.StagePlan;

/** Tham số, tách vai trò và nhận chữ ký của mẫu duyệt kép (DV-06-12). */
class DualApprovalTest {

  final DualApprovalPlanner planner = new DualApprovalPlanner(new ObjectMapper());

  @Test
  void thamSoTrongThiDungMacDinhCuaMau() {
    DualApprovalPlanner.Planned p = planner.plan(Optional.empty());
    StagePlan.Planned command = p.plan().step("chiHuy").orElseThrow();
    StagePlan.Planned committee = p.plan().step("capUy").orElseThrow();
    assertThat(command.role()).isEqualTo("leader");
    assertThat(command.scope()).isEqualTo(ApprovalScope.DON_VI_TRUC_TIEP);
    assertThat(committee.scope()).isEqualTo(ApprovalScope.CAP_UY_CO_THAM_QUYEN);
    assertThat(command.workingDays()).isEqualTo(DualApproval.DEFAULT_DAYS);
    assertThat(command.remindBefore()).isEqualTo(DualApproval.DEFAULT_REMIND);
    assertThat(command.overdue()).isEqualTo(OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN);
    assertThat(p.signs()).containsEntry(Branch.CHI_HUY, false).containsEntry(Branch.CAP_UY, false);
    // Kế hoạch đi qua biến tiến trình dạng chuỗi gọn.
    assertThat(StagePlan.decode(p.plan().encode()).step("capUy")).contains(committee);
  }

  @Test
  void thamSoCuaTenantGhiDeTungNhanh() {
    UUID deputy = UUID.randomUUID();
    DualApprovalPlanner.Planned p =
        planner.plan(
            Optional.of(
                "{\"cap\":[{\"buoc\":\"CAP_UY\",\"vai_tro\":\"decider\","
                    + "\"pham_vi\":\"CAP_TREN_TRUC_TIEP\",\"han_ngay_lam_viec\":15,"
                    + "\"ky_so\":true}],\"nhac_truoc\":\"P2D_LV\","
                    + "\"khi_qua_han\":\"NHAC_LAI\",\"nguoi_thay\":{\"CAP_UY\":\""
                    + deputy
                    + "\"}}"));
    StagePlan.Planned committee = p.plan().step("capUy").orElseThrow();
    assertThat(committee.role()).isEqualTo("decider");
    assertThat(committee.scope()).isEqualTo(ApprovalScope.CAP_TREN_TRUC_TIEP);
    assertThat(committee.workingDays()).isEqualTo(15);
    assertThat(committee.remindBefore()).isEqualTo(2);
    assertThat(committee.overdue()).isEqualTo(OverdueAction.NHAC_LAI);
    assertThat(committee.substituteId()).isEqualTo(deputy);
    assertThat(p.signs()).containsEntry(Branch.CAP_UY, true);
    assertThat(p.plan().step("chiHuy").orElseThrow().substituteId()).isNull();
  }

  @Test
  void khongChoUyQuyenThiQuaHanLenCapTrenVaBoNguoiThay() {
    DualApprovalPlanner.Planned p =
        planner.plan(
            Optional.of(
                "{\"allowDelegation\":false,\"nguoi_thay\":{\"CHI_HUY\":\""
                    + UUID.randomUUID()
                    + "\"}}"));
    StagePlan.Planned command = p.plan().step("chiHuy").orElseThrow();
    assertThat(command.overdue()).isEqualTo(OverdueAction.CHUYEN_CAP_TREN);
    assertThat(command.substituteId()).isNull();
  }

  @Test
  void thamSoNgoaiMienLaLoiCauHinh() {
    for (String bad :
        List.of(
            "{\"cap\":[{\"buoc\":\"CHI_HUY\",\"han_ngay_lam_viec\":16}]}",
            "{\"cap\":[{\"buoc\":\"CHI_HUY\",\"han_ngay_lam_viec\":0}]}",
            "{\"cap\":[{\"buoc\":\"CAP_UY\",\"vai_tro\":\"chinh_uy\"}]}",
            "{\"cap\":[{\"buoc\":\"CAP_UY\",\"pham_vi\":\"TOAN_QUAN\"}]}",
            "{\"khi_qua_han\":\"BO_QUA\"}",
            "{\"nguoi_thay\":{\"CHI_HUY\":\"khong-phai-uuid\"}}",
            "{khong-phai-json")) {
      assertThatThrownBy(() -> planner.plan(Optional.of(bad)))
          .as(bad)
          .isInstanceOf(ApprovalSetupException.class);
    }
  }

  @Test
  void tachVaiTroCuaLuotDuyetKep() {
    UUID submitter = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThat(DualApproval.check(submitter, submitter, List.of()))
        .contains(DualApproval.Violation.INITIATOR);
    assertThat(DualApproval.check(a, submitter, List.of(a)))
        .contains(DualApproval.Violation.OTHER_BRANCH);
    assertThat(DualApproval.check(b, submitter, List.of(a))).isEmpty();
    assertThat(DualApproval.check(b, null, List.of())).isEmpty();
    assertThat(DualApproval.check(null, submitter, List.of(a))).isEmpty();
  }

  @Test
  void nhanhTheoMaPhanTuVaMaBuoc() {
    assertThat(Branch.byElement("quaHan_capUy")).contains(Branch.CAP_UY);
    assertThat(Branch.byElement("choKy_chiHuy")).contains(Branch.CHI_HUY);
    assertThat(Branch.byElement("capUy")).contains(Branch.CAP_UY);
    assertThat(Branch.byElement("gop")).isEmpty();
    assertThat(Branch.byElement(null)).isEmpty();
    assertThat(Branch.byCode("CAP_UY")).contains(Branch.CAP_UY);
    assertThat(Branch.byCode("CHINH_UY")).isEmpty();
    assertThat(Branch.CHI_HUY.other()).isEqualTo(Branch.CAP_UY);
    assertThat(Branch.CAP_UY.other()).isEqualTo(Branch.CHI_HUY);
    assertThat(Branch.CAP_UY.remindVariable()).isEqualTo("nhac_capUy");
  }

  @Test
  void listenerTuChoiPhanTuKhongPhaiNhanh() {
    DualApprovalHooks hooks = new DualApprovalHooks(null, planner, null);
    assertThatThrownBy(() -> hooks.overdue("nhac_chiHuy", ""))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(() -> hooks.overdue("quaHan_gop", ""))
        .isInstanceOf(ApprovalSetupException.class);
    assertThat(hooks.overdue("quaHan_chiHuy", "chiHuy=1")).isEqualTo("chiHuy=2");
    assertThatThrownBy(() -> hooks.enterBranch("choKy_chiHuy", Map.of()))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(() -> hooks.start(Map.of("khoi", "DANG")))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () -> hooks.start(Map.of("tenantId", UUID.randomUUID().toString(), "khoi", "LLVT")))
        .isInstanceOf(ApprovalSetupException.class);
  }

  @Test
  void chuKyKhongNguoiKyChiApKhiDungMotNhanhCho() {
    Fake port = new Fake();
    DualApprovalService service = new DualApprovalService(port);
    UUID tenant = UUID.randomUUID();
    UUID object = UUID.randomUUID();
    port.waits.add(new DualApprovalProcesses.SignWait("e1", Branch.CHI_HUY, UUID.randomUUID()));
    port.waits.add(new DualApprovalProcesses.SignWait("e2", Branch.CAP_UY, null));
    assertThat(service.onSigned(tenant, object, null)).isEqualTo(Outcome.IGNORED);
    assertThat(port.delivered).isEmpty();
  }

  static final class Fake implements DualApprovalProcesses {
    final List<SignWait> waits = new ArrayList<>();
    final List<SignWait> delivered = new ArrayList<>();

    @Override
    public List<SignWait> waitingSignatures(UUID tenantId, UUID objectId) {
      return waits;
    }

    @Override
    public void deliverSigned(SignWait wait) {
      delivered.add(wait);
    }
  }
}
