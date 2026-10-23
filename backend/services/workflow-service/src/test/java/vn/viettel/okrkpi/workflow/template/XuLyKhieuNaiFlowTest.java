package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.stage.AdvanceStageCommand;
import vn.viettel.okrkpi.workflow.application.stage.StageProcessService.Outcome;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Kiểm thử Flowable của mẫu {@code xu-ly-khieu-nai} (QT-12): khởi tạo từ EVT-25 với người đã tham
 * gia đọc từ lịch sử hồ sơ, chọn người xử lý theo OPA (người vừa thẩm định không nhận việc,
 * BR-12-04) và ít việc nhất, chuyển cấp trên khi đơn vị không còn ứng viên hoặc quá hạn, hạn tổng
 * 15 ngày làm việc (BR-12-05).
 */
class XuLyKhieuNaiFlowTest {

  static final StageTemplate T = StageTemplate.XU_LY_KHIEU_NAI;

  StageEngineHarness h;
  UUID profile;
  UUID member;
  UUID appraiser;
  UUID proposer;
  UUID decider;
  UUID complaint;

  @BeforeEach
  void setUp() {
    h = new StageEngineHarness();
    profile = UUID.randomUUID();
    member = UUID.randomUUID();
    appraiser = UUID.randomUUID();
    proposer = UUID.randomUUID();
    decider = UUID.randomUUID();
    complaint = UUID.randomUUID();
    // Hồ sơ đã qua đủ bốn bước, thẩm định ở Chi bộ.
    h.service.start(h.command(StageTemplate.DUYET_HO_SO_DANH_GIA, profile, member));
    h.service.onAppraisalStep(h.tenant, profile, "TU_DE_XUAT", "CHO_THAM_DINH", member);
    h.service.onSigned(h.tenant, "appraisal", "TU_DANH_GIA", profile, null);
    h.service.onAppraisalStep(h.tenant, profile, "THAM_DINH", "DA_THAM_DINH", appraiser);
    h.service.onSigned(h.tenant, "appraisal", "THAM_DINH", profile, null);
    h.service.onAppraisalStep(h.tenant, profile, "DE_XUAT", "CHO_QUYET_DINH", proposer);
    h.service.onAppraisalStep(h.tenant, profile, "QUYET_DINH", "DA_QUYET_DINH", decider);
    h.service.onAppraisalStep(h.tenant, profile, "QUYET_DINH", "DA_KY_SO", null);
  }

  @AfterEach
  void tearDown() {
    h.close();
  }

  String file() {
    assertThat(
            h.service.onComplaintFiled(
                h.tenant, complaint, profile, member, LocalDate.of(2026, 11, 11), 1))
        .isEqualTo(Outcome.STARTED);
    String pid = h.pid(T, complaint);
    assertThat(h.active(pid)).containsExactly("chon_tiepNhan");
    h.choose(pid);
    return pid;
  }

  Outcome advance(String step, String outcome) {
    return h.service.advance(
        new AdvanceStageCommand(h.tenant, T, complaint, step, outcome, null, Map.of()));
  }

  @Test
  void nguoiVuaThamDinhKhongNhanViecVaChonNguoiItViecNhat() {
    UUID busy = UUID.randomUUID();
    UUID free = UUID.randomUUID();
    h.holders.put("appraiser", h.tree.chiBo(), appraiser, busy, free);
    h.busy(busy);
    String pid = file();
    Task receive = h.task(pid);
    assertThat(receive.getTaskDefinitionKey()).isEqualTo("tiepNhan");
    assertThat(receive.getAssignee()).isEqualTo(free.toString());
    assertThat(h.variable(pid, "donViChon")).isEqualTo(h.tree.chiBo().toString());
    assertThat(h.opa.participantsSeen.get(0))
        .containsEntry("appraiser", java.util.List.of(appraiser))
        .containsEntry("proposer", java.util.List.of(proposer))
        .containsEntry("decider", java.util.List.of(decider));
    // Tiếp nhận 2 ngày làm việc: thứ Tư 21/10 → thứ Sáu 23/10.
    assertThat(receive.getDueDate().toInstant())
        .isEqualTo(StageEngineHarness.at17(LocalDate.of(2026, 10, 23)));
  }

  @Test
  void donViKhongConUngVienThiChuyenCapTren() {
    UUID upper = UUID.randomUUID();
    h.holders.put("appraiser", h.tree.chiBo(), appraiser);
    h.holders.put("appraiser", h.tree.dangUy(), upper);
    String pid = file();
    assertThat(h.task(pid).getAssignee()).isEqualTo(upper.toString());
    assertThat(h.variable(pid, "donViChon")).isEqualTo(h.tree.dangUy().toString());
  }

  @Test
  void khongAiHopLeThiGiaoNhomTaiDonViXuLy() {
    h.holders.put("appraiser", h.tree.chiBo(), appraiser);
    String pid = file();
    Task receive = h.task(pid);
    assertThat(receive.getAssignee()).isNull();
    assertThat(h.groups(receive)).containsExactly(h.group("appraiser", h.tree.chiBo()));
  }

  @Test
  void duongChinhGiaiTrinhKetLuanVaKyTraLoi() {
    UUID handler = UUID.randomUUID();
    h.holders.put("appraiser", h.tree.chiBo(), handler);
    String pid = file();
    assertThat(advance("tiepNhan", "THU_LY")).isEqualTo(Outcome.ADVANCED);
    Task conclude = h.task(pid);
    assertThat(conclude.getTaskDefinitionKey()).isEqualTo("ketLuan");
    assertThat(conclude.getAssignee()).isEqualTo(handler.toString());
    // Hạn tổng thể: 17:00 ngày 11/11 (EVT-25 responseDeadline).
    assertThat(conclude.getDueDate().toInstant())
        .isEqualTo(StageEngineHarness.at17(LocalDate.of(2026, 11, 11)));
    assertThat(advance("ketLuan", "GIAI_TRINH")).isEqualTo(Outcome.ADVANCED);
    assertThat(h.task(pid).getTaskDefinitionKey()).isEqualTo("giaiTrinh");
    // Hết hạn giải trình thì vẫn về bước kết luận.
    h.fireTimer(pid, "quaHan_giaiTrinh");
    assertThat(h.task(pid).getTaskDefinitionKey()).isEqualTo("ketLuan");
    assertThat(advance("ketLuan", "KET_LUAN")).isEqualTo(Outcome.ADVANCED);
    Task sign = h.task(pid);
    assertThat(sign.getTaskDefinitionKey()).isEqualTo("kyTraLoi");
    assertThat(h.groups(sign)).containsExactly(h.group("decider", h.tree.chiBo()));
    assertThat(h.service.onSigned(h.tenant, "oversight", "TRA_LOI_KHIEU_NAI", complaint, null))
        .isEqualTo(Outcome.ADVANCED);
    assertThat(h.ended(pid)).isTrue();
    assertThat(h.endActivity(pid)).isEqualTo("daTraLoi");
  }

  @Test
  void quaHanTiepNhanChonLaiOCapTren() {
    UUID handler = UUID.randomUUID();
    UUID upper = UUID.randomUUID();
    h.holders.put("appraiser", h.tree.chiBo(), handler);
    h.holders.put("appraiser", h.tree.dangUy(), upper);
    String pid = file();
    assertThat(h.task(pid).getAssignee()).isEqualTo(handler.toString());
    h.fireTimer(pid, "quaHan_tiepNhan");
    assertThat(h.active(pid)).containsExactly("chon_tiepNhan");
    h.choose(pid);
    assertThat(h.task(pid).getAssignee()).isEqualTo(upper.toString());
  }

  @Test
  void quaHanKyTraLoiChuyenCapTrenGiuHanTongThe() {
    UUID handler = UUID.randomUUID();
    h.holders.put("appraiser", h.tree.chiBo(), handler);
    String pid = file();
    advance("tiepNhan", "THU_LY");
    advance("ketLuan", "KET_LUAN");
    h.fireTimer(pid, "quaHan_kyTraLoi");
    Task sign = h.task(pid);
    assertThat(h.groups(sign)).containsExactly(h.group("decider", h.tree.dangUy()));
    Instant due = sign.getDueDate().toInstant();
    assertThat(due).isEqualTo(StageEngineHarness.at17(LocalDate.of(2026, 11, 11)));
  }

  @Test
  void khongThuLyVaRutKetThucLuong() {
    h.holders.put("appraiser", h.tree.chiBo(), UUID.randomUUID());
    String pid = file();
    advance("tiepNhan", "KHONG_THU_LY");
    assertThat(h.endActivity(pid)).isEqualTo("khongThuLy");
  }

  @Test
  void khongCoLichSuHoSoThiKhongKhoiTao() {
    assertThat(
            h.service.onComplaintFiled(
                h.tenant, complaint, UUID.randomUUID(), member, LocalDate.of(2026, 11, 11), 1))
        .isEqualTo(Outcome.NO_PROCESS);
  }

  @Test
  void eventTrungKhongTaoLuongThuHai() {
    h.holders.put("appraiser", h.tree.chiBo(), UUID.randomUUID());
    file();
    assertThat(
            h.service.onComplaintFiled(
                h.tenant, complaint, profile, member, LocalDate.of(2026, 11, 11), 1))
        .isEqualTo(Outcome.EXISTING);
  }
}
