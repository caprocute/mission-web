package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.stage.AdvanceStageCommand;
import vn.viettel.okrkpi.workflow.application.stage.StageProcessService.Outcome;
import vn.viettel.okrkpi.workflow.application.stage.StartStageCommand;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Kiểm thử Flowable của mẫu {@code mo-lai-ky-hai-nguoi} (QT-13): hai người duyệt khác nhau, khác
 * người tạo yêu cầu và chưa tham gia hồ sơ (BR-13-02, BR-13-03); đơn vị thiếu người thứ hai thì
 * chọn ở cấp trên, không hạ xuống một người; mỗi lượt xong khi nhận EVT-18; quá hạn chuyển cấp.
 */
class MoLaiKyHaiNguoiFlowTest {

  static final StageTemplate T = StageTemplate.MO_LAI_KY_HAI_NGUOI;

  StageEngineHarness h;
  UUID request;
  UUID requester;
  UUID participant;

  @BeforeEach
  void setUp() {
    h = new StageEngineHarness();
    request = UUID.randomUUID();
    requester = UUID.randomUUID();
    participant = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    h.close();
  }

  String start() {
    StartStageCommand c =
        new StartStageCommand(
            h.tenant,
            T,
            request,
            1,
            ApprovalFlow.DANG,
            h.tree.banA(),
            "Duyệt yêu cầu mở lại kỳ",
            requester,
            null,
            null,
            false,
            null,
            Map.of("decider", List.of(participant)),
            null);
    assertThat(h.service.start(c)).isEqualTo(Outcome.STARTED);
    String pid = h.pid(T, request);
    h.choose(pid);
    return pid;
  }

  Outcome signed() {
    return h.service.onSigned(h.tenant, "oversight", "PHE_DUYET_MO_LAI", request, null);
  }

  @Test
  void nguoiTaoYeuCauVaNguoiDaThamGiaKhongDuyetDuoc() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    h.holders.put("decider", h.tree.banA(), requester, participant, a, b);
    String pid = start();
    Object first = h.variable(pid, "nguoiDuyet1");
    Object second = h.variable(pid, "nguoiDuyet2");
    assertThat(Set.of(first, second)).containsExactlyInAnyOrder(a.toString(), b.toString());
    assertThat(h.task(pid).getAssignee()).isEqualTo(first);

    assertThat(signed()).isEqualTo(Outcome.ADVANCED);
    Task two = h.task(pid);
    assertThat(two.getTaskDefinitionKey()).isEqualTo("duyetLan2");
    assertThat(two.getAssignee()).isEqualTo(second).isNotEqualTo(first);
    assertThat(signed()).isEqualTo(Outcome.ADVANCED);
    assertThat(h.endActivity(pid)).isEqualTo("daDuyet");
  }

  @Test
  void donViThieuNguoiThuHaiThiChonOCapTrenKhongHaXuongMotNguoi() {
    UUID only = UUID.randomUUID();
    UUID up1 = UUID.randomUUID();
    UUID up2 = UUID.randomUUID();
    h.holders.put("decider", h.tree.banA(), requester, only);
    h.holders.put("decider", h.tree.chiBo(), up1, up2);
    String pid = start();
    assertThat(h.variable(pid, "donViChon")).isEqualTo(h.tree.chiBo().toString());
    assertThat(Set.of(h.variable(pid, "nguoiDuyet1"), h.variable(pid, "nguoiDuyet2")))
        .containsExactlyInAnyOrder(up1.toString(), up2.toString());
  }

  @Test
  void tuChoiOLuotMotKetThucLuong() {
    h.holders.put("decider", h.tree.banA(), UUID.randomUUID(), UUID.randomUUID());
    String pid = start();
    h.service.advance(
        new AdvanceStageCommand(h.tenant, T, request, "DUYET_LAN_1", "TU_CHOI", null, Map.of()));
    assertThat(h.endActivity(pid)).isEqualTo("tuChoi");
  }

  @Test
  void quaHanLuotHaiChonNguoiKhacOCapTren() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID up = UUID.randomUUID();
    h.holders.put("decider", h.tree.banA(), a, b);
    h.holders.put("decider", h.tree.chiBo(), up);
    String pid = start();
    Object first = h.variable(pid, "nguoiDuyet1");
    signed();
    // Lượt hai: hạn 2 ngày làm việc, quá hạn thì chọn lại ở cấp trên, khác người duyệt lượt một.
    h.fireTimer(pid, "quaHan_duyetLan2");
    assertThat(h.active(pid)).containsExactly("chon_duyetLan2");
    h.choose(pid);
    Task two = h.task(pid);
    assertThat(two.getTaskDefinitionKey()).isEqualTo("duyetLan2");
    assertThat(two.getAssignee()).isEqualTo(up.toString()).isNotEqualTo(first);
  }
}
