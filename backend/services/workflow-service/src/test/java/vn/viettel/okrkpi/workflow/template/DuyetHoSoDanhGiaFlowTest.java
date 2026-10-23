package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.stage.AdvanceStageCommand;
import vn.viettel.okrkpi.workflow.application.stage.StageProcessService.Outcome;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Kiểm thử Flowable của mẫu {@code duyet-ho-so-danh-gia} (QT-09, QT-10): đường chính có ba mốc ký,
 * trả lại tối đa hai lần, quá hạn chuyển cấp (cấp trên hoặc người được uỷ quyền), bỏ qua bước đề
 * xuất theo điều kiện của tenant, lõi đi trước và sự kiện trùng.
 */
class DuyetHoSoDanhGiaFlowTest {

  static final StageTemplate T = StageTemplate.DUYET_HO_SO_DANH_GIA;

  StageEngineHarness h;
  UUID profile;
  UUID member;

  @BeforeEach
  void setUp() {
    h = new StageEngineHarness();
    profile = UUID.randomUUID();
    member = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    h.close();
  }

  String start() {
    assertThat(h.service.start(h.command(T, profile, member))).isEqualTo(Outcome.STARTED);
    return h.pid(T, profile);
  }

  Outcome evt11(String stage, String to, UUID actor) {
    return h.service.onAppraisalStep(h.tenant, profile, stage, to, actor);
  }

  Outcome signed(String purpose) {
    return h.service.onSigned(h.tenant, "appraisal", purpose, profile, null);
  }

  @Test
  void duongChinhQuaBaMocKySo() {
    String pid = start();
    Task self = h.task(pid);
    assertThat(self.getTaskDefinitionKey()).isEqualTo("tuDanhGia");
    assertThat(self.getAssignee()).isEqualTo(member.toString());
    // Thứ Tư 21/10 + 3 ngày làm việc = thứ Hai 26/10, nhắc trước 1 ngày: 08:00 thứ Sáu 23/10.
    assertThat(self.getDueDate().toInstant())
        .isEqualTo(StageEngineHarness.at17(LocalDate.of(2026, 10, 26)));
    assertThat(h.timerDue(pid, "nhac_tuDanhGia"))
        .isEqualTo(StageEngineHarness.at8(LocalDate.of(2026, 10, 23)));
    assertThat(h.service.start(h.command(T, profile, member))).isEqualTo(Outcome.EXISTING);

    assertThat(evt11("TU_DE_XUAT", "CHO_THAM_DINH", member)).isEqualTo(Outcome.ADVANCED);
    assertThat(h.active(pid)).containsExactly("choKy_tuDanhGia");
    assertThat(signed("TU_DANH_GIA")).isEqualTo(Outcome.SIGNALLED);

    Task appraise = h.task(pid);
    assertThat(appraise.getTaskDefinitionKey()).isEqualTo("thamDinh");
    assertThat(h.groups(appraise)).containsExactly(h.group("appraiser", h.tree.chiBo()));
    UUID appraiser = UUID.randomUUID();
    // Chữ ký về trước EVT-11: ghi cờ, bước xong thì đi thẳng qua chốt chờ ký.
    assertThat(signed("THAM_DINH")).isEqualTo(Outcome.SIGNALLED);
    assertThat(evt11("THAM_DINH", "DA_THAM_DINH", appraiser)).isEqualTo(Outcome.ADVANCED);

    Task propose = h.task(pid);
    assertThat(propose.getTaskDefinitionKey()).isEqualTo("deXuat");
    assertThat(h.groups(propose)).containsExactly(h.group("manager", h.tree.banA()));
    assertThat(evt11("DE_XUAT", "CHO_QUYET_DINH", UUID.randomUUID())).isEqualTo(Outcome.ADVANCED);

    Task decide = h.task(pid);
    assertThat(decide.getTaskDefinitionKey()).isEqualTo("quyetDinh");
    assertThat(h.groups(decide)).containsExactly(h.group("decider", h.tree.chiBo()));
    assertThat(evt11("QUYET_DINH", "DA_QUYET_DINH", UUID.randomUUID())).isEqualTo(Outcome.ADVANCED);
    assertThat(h.active(pid)).containsExactly("choKy_quyetDinh");
    assertThat(evt11("QUYET_DINH", "DA_KY_SO", null)).isEqualTo(Outcome.SIGNALLED);
    assertThat(h.ended(pid)).isTrue();
    assertThat(h.endActivity(pid)).isEqualTo("daQuyetDinh");
  }

  @Test
  void traLaiToiDaHaiLanThuBaBiTuChoi() {
    String pid = start();
    for (int i = 1; i <= 2; i++) {
      evt11("TU_DE_XUAT", "CHO_THAM_DINH", member);
      signed("TU_DANH_GIA");
      assertThat(evt11("THAM_DINH", "TU_DANH_GIA", UUID.randomUUID())).isEqualTo(Outcome.ADVANCED);
      assertThat(h.task(pid).getTaskDefinitionKey()).isEqualTo("tuDanhGia");
      assertThat(h.variable(pid, "soLanTraLai")).isEqualTo(i);
      // Vào lại bước tự đánh giá thì phải ký lại.
      assertThat(h.variable(pid, "daKy_tuDanhGia")).isEqualTo(false);
    }
    evt11("TU_DE_XUAT", "CHO_THAM_DINH", member);
    signed("TU_DANH_GIA");
    assertThatThrownBy(
            () ->
                h.service.advance(
                    new AdvanceStageCommand(
                        h.tenant, T, profile, "thamDinh", "TRA_LAI", null, Map.of())))
        .isInstanceOf(ApprovalSetupException.class)
        .hasMessageContaining("tối đa");
    assertThat(h.task(pid).getTaskDefinitionKey()).isEqualTo("thamDinh");
  }

  @Test
  void quaHanChuyenCapTrenKhiKhongKhaiNguoiDuocUyQuyen() {
    String pid = start();
    evt11("TU_DE_XUAT", "CHO_THAM_DINH", member);
    signed("TU_DANH_GIA");
    h.fireTimer(pid, "quaHan_thamDinh");
    Task escalated = h.task(pid);
    assertThat(escalated.getTaskDefinitionKey()).isEqualTo("thamDinh");
    assertThat(h.groups(escalated)).containsExactly(h.group("appraiser", h.tree.dangUy()));
    assertThat(h.variable(pid, "soLanQuaHan")).isEqualTo("thamDinh=1");
  }

  @Test
  void quaHanChuyenNguoiDuocUyQuyenKhiTenantKhai() {
    UUID deputy = UUID.randomUUID();
    h.bindings.raw.put("HO_SO_DANH_GIA", "{\"nguoi_thay\":{\"THAM_DINH\":\"" + deputy + "\"}}");
    String pid = start();
    evt11("TU_DE_XUAT", "CHO_THAM_DINH", member);
    signed("TU_DANH_GIA");
    h.fireTimer(pid, "quaHan_thamDinh");
    assertThat(h.task(pid).getAssignee()).isEqualTo(deputy.toString());
  }

  @Test
  void nhacTruocHanKhongDongBuoc() {
    String pid = start();
    h.fireTimer(pid, "nhac_tuDanhGia");
    assertThat(h.active(pid)).contains("tuDanhGia", "nhacViec");
    h.completeExternal(pid, "notify-remind");
    assertThat(h.active(pid)).containsExactly("tuDanhGia");
  }

  @Test
  void boQuaBuocDeXuatTheoDieuKienCuaTenant() {
    h.bindings.raw.put(
        "HO_SO_DANH_GIA",
        "{\"cap\":[{\"buoc\":\"DE_XUAT\",\"bo_qua_khi\":\"muc_tham_dinh == 'HTXSNV'\"}]}");
    String pid = start();
    evt11("TU_DE_XUAT", "CHO_THAM_DINH", member);
    signed("TU_DANH_GIA");
    h.service.advance(
        new AdvanceStageCommand(
            h.tenant,
            T,
            profile,
            "THAM_DINH",
            "DUYET",
            UUID.randomUUID(),
            Map.of("mucThamDinh", "HTXSNV")));
    signed("THAM_DINH");
    assertThat(h.task(pid).getTaskDefinitionKey()).isEqualTo("quyetDinh");
    assertThat(h.variable(pid, "boQuaDeXuat")).isEqualTo(true);
  }

  @Test
  void loiDiTruocThiDiTatVaSuKienCuBiBoQua() {
    String pid = start();
    // EVT-11 của bước đề xuất về khi tiến trình còn ở tự đánh giá.
    assertThat(evt11("DE_XUAT", "CHO_QUYET_DINH", UUID.randomUUID())).isEqualTo(Outcome.SHORTCUT);
    assertThat(h.task(pid).getTaskDefinitionKey()).isEqualTo("quyetDinh");
    // Sự kiện của bước đã qua, hoặc giao lại, không làm gì.
    assertThat(evt11("TU_DE_XUAT", "CHO_THAM_DINH", member)).isEqualTo(Outcome.IGNORED);
    assertThat(evt11("DE_XUAT", "CHO_QUYET_DINH", UUID.randomUUID())).isEqualTo(Outcome.IGNORED);
    assertThat(h.task(pid).getTaskDefinitionKey()).isEqualTo("quyetDinh");
  }

  @Test
  void quyetDinhYeuCauXemLaiVeBuocDeXuat() {
    String pid = start();
    evt11("DE_XUAT", "CHO_QUYET_DINH", UUID.randomUUID());
    assertThat(evt11("QUYET_DINH", "DA_THAM_DINH", UUID.randomUUID())).isEqualTo(Outcome.ADVANCED);
    assertThat(h.task(pid).getTaskDefinitionKey()).isEqualTo("deXuat");
  }

  @Test
  void ketQuaKhongHopLeVoiBuocBiTuChoi() {
    start();
    assertThatThrownBy(
            () ->
                h.service.advance(
                    new AdvanceStageCommand(
                        h.tenant, T, profile, "tuDanhGia", "DUYET", null, Map.of())))
        .isInstanceOf(ApprovalSetupException.class);
  }
}
