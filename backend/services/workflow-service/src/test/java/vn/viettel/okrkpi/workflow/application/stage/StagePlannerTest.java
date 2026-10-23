package vn.viettel.okrkpi.workflow.application.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.port.StageProcesses;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;
import vn.viettel.okrkpi.workflow.domain.stage.OverdueAction;
import vn.viettel.okrkpi.workflow.domain.stage.StagePlan;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/** Lập kế hoạch bước từ tham số {@code wf_binding} của tenant (SCR-1504). */
class StagePlannerTest {

  final StagePlanner planner = new StagePlanner(new ObjectMapper());

  @Test
  void macDinhCuaMauKhiTenantKhongKhai() {
    StagePlanner.Planned p = planner.plan(StageTemplate.DUYET_HO_SO_DANH_GIA, Optional.empty());
    assertThat(p.maxReturns()).isEqualTo(2);
    StagePlan.Planned appraise = p.plan().step("thamDinh").orElseThrow();
    assertThat(appraise.role()).isEqualTo("appraiser");
    assertThat(appraise.scope()).isEqualTo(ApprovalScope.CAP_TREN_TRUC_TIEP);
    assertThat(appraise.workingDays()).isEqualTo(3);
    assertThat(appraise.remindBefore()).isEqualTo(1);
    assertThat(p.plan().step("deXuat").orElseThrow().workingDays()).isEqualTo(2);
  }

  @Test
  void thamSoTenantGhiDeVaiTroHanNhacQuaHanVaSoLanTraLai() {
    String raw =
        "{\"cap\":[{\"buoc\":\"THAM_DINH\",\"vai_tro\":\"leader\",\"pham_vi\":\"CAP_UY_CO_THAM_QUYEN\","
            + "\"han_ngay_lam_viec\":5}],\"thoi_han_moi_buoc\":\"P4D_LV\",\"nhac_truoc\":\"P2D_LV\","
            + "\"khi_qua_han\":\"CHUYEN_CAP_TREN\",\"so_lan_tra_lai\":9}";
    StagePlanner.Planned p = planner.plan(StageTemplate.DUYET_HO_SO_DANH_GIA, Optional.of(raw));
    StagePlan.Planned appraise = p.plan().step("thamDinh").orElseThrow();
    assertThat(appraise.role()).isEqualTo("leader");
    assertThat(appraise.scope()).isEqualTo(ApprovalScope.CAP_UY_CO_THAM_QUYEN);
    assertThat(appraise.workingDays()).isEqualTo(5);
    assertThat(appraise.remindBefore()).isEqualTo(2);
    assertThat(appraise.overdue()).isEqualTo(OverdueAction.CHUYEN_CAP_TREN);
    assertThat(p.plan().step("deXuat").orElseThrow().workingDays()).isEqualTo(4);
    assertThat(p.maxReturns()).isEqualTo(5);
  }

  @Test
  void hanTongTheKhongNhanThoiHanMoiBuoc() {
    StagePlanner.Planned p =
        planner.plan(
            StageTemplate.XU_LY_KHIEU_NAI, Optional.of("{\"thoi_han_moi_buoc\":\"P1D_LV\"}"));
    assertThat(p.plan().step("ketLuan").orElseThrow().workingDays()).isEqualTo(15);
    assertThat(p.plan().step("tiepNhan").orElseThrow().workingDays()).isEqualTo(1);
    // Nhắc trước không vượt hạn của bước.
    assertThat(p.plan().step("tiepNhan").orElseThrow().remindBefore()).isZero();
  }

  @Test
  void thamSoSaiBiTuChoi() {
    assertThatThrownBy(
            () ->
                planner.plan(
                    StageTemplate.XU_LY_KHIEU_NAI,
                    Optional.of("{\"cap\":[{\"buoc\":" + "\"TIEP_NHAN\",\"vai_tro\":\"admin\"}]}")))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                planner.plan(
                    StageTemplate.XU_LY_KHIEU_NAI,
                    Optional.of(
                        "{\"cap\":[{\"buoc\":" + "\"TIEP_NHAN\",\"han_ngay_lam_viec\":20}]}")))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(() -> planner.plan(StageTemplate.XU_LY_KHIEU_NAI, Optional.of("{hong")))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                planner.plan(
                    StageTemplate.DUYET_HO_SO_DANH_GIA,
                    Optional.of("{\"nguoi_thay\":{\"THAM_DINH\":\"khong-phai-uuid\"}}")))
        .isInstanceOf(ApprovalSetupException.class);
  }

  @Test
  void dieuKienBoQuaChiApChoBuocBoQuaDuoc() {
    String raw =
        "{\"cap\":[{\"buoc\":\"DE_XUAT\",\"bo_qua_khi\":\"khoi == 'DANG'\"},"
            + "{\"buoc\":\"THAM_DINH\",\"bo_qua_khi\":\"true\"}]}";
    assertThat(planner.skipCondition(StageTemplate.DUYET_HO_SO_DANH_GIA, "DE_XUAT", raw))
        .contains("khoi == 'DANG'");
    assertThat(planner.skipCondition(StageTemplate.DUYET_HO_SO_DANH_GIA, "THAM_DINH", raw))
        .isEmpty();
    assertThat(planner.skipCondition(StageTemplate.DUYET_HO_SO_DANH_GIA, "DE_XUAT", null))
        .isEmpty();
  }

  @Test
  void soLanQuaHanTheoBuoc() {
    String once = OverdueCounts.increment("", "thamDinh");
    String twice = OverdueCounts.increment(OverdueCounts.increment(once, "deXuat"), "thamDinh");
    assertThat(twice).isEqualTo("deXuat=1,thamDinh=2");
    assertThat(OverdueCounts.of(twice, "thamDinh")).isEqualTo(2);
    assertThat(OverdueCounts.of("hong,x=y,=1", "x")).isZero();
    assertThat(OverdueCounts.of(null, "x")).isZero();
  }

  @Test
  void anhChupTienTrinhChapNhanBienNull() {
    java.util.Map<String, Object> vars = new java.util.HashMap<>();
    vars.put("nguoiBuoc", null);
    StageProcesses.Snapshot withNull =
        new StageProcesses.Snapshot("p", java.util.List.of("a"), null, null, vars);
    assertThat(withNull.variables()).containsEntry("nguoiBuoc", null);
    assertThat(new StageProcesses.Snapshot("p", java.util.List.of(), null, null, null).variables())
        .isEmpty();
  }
}
