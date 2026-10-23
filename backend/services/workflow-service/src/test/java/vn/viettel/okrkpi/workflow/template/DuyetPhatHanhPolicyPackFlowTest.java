package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Kiểm thử Flowable của luồng {@code duyet-phat-hanh-policy-pack} (QT-15, tên mẫu suy luận): người
 * phê duyệt khác người soạn (BR-15-09), phê duyệt xong khi nhận EVT-18, gọi lõi khởi chạy saga phát
 * hành rồi chờ EVT-13; quá hạn nhắc lại cùng người với hạn mới.
 */
class DuyetPhatHanhPolicyPackFlowTest {

  static final StageTemplate T = StageTemplate.DUYET_PHAT_HANH_POLICY_PACK;

  StageEngineHarness h;
  UUID version;
  UUID author;
  UUID approver;

  @BeforeEach
  void setUp() {
    h = new StageEngineHarness();
    version = UUID.randomUUID();
    author = UUID.randomUUID();
    approver = UUID.randomUUID();
    h.holders.put("decider", h.tree.banA(), author, approver);
  }

  @AfterEach
  void tearDown() {
    h.close();
  }

  String start() {
    assertThat(h.service.start(h.command(T, version, author))).isEqualTo(Outcome.STARTED);
    String pid = h.pid(T, version);
    h.choose(pid);
    return pid;
  }

  @Test
  void nguoiSoanKhongPheDuyetDuocVaDuongChinhChoPhatHanh() {
    String pid = start();
    Task approve = h.task(pid);
    assertThat(approve.getTaskDefinitionKey()).isEqualTo("pheDuyet");
    assertThat(approve.getAssignee()).isEqualTo(approver.toString());
    assertThat(h.service.onSigned(h.tenant, "policy", "PHAT_HANH_POLICY", version, approver))
        .isEqualTo(Outcome.ADVANCED);
    assertThat(h.active(pid)).containsExactly("khoiChayPhatHanh");
    h.completeExternal(pid, "core-policy-pack-publish");
    assertThat(h.active(pid)).containsExactly("choPhatHanh");
    assertThat(h.service.onPolicyPublished(h.tenant, version)).isEqualTo(Outcome.SIGNALLED);
    assertThat(h.endActivity(pid)).isEqualTo("daPhatHanh");
  }

  @Test
  void phatHanhXongTruocKhiLoiBaoKhoiChayThiKhongCho() {
    String pid = start();
    h.service.onSigned(h.tenant, "policy", "PHAT_HANH_POLICY", version, approver);
    assertThat(h.service.onPolicyPublished(h.tenant, version)).isEqualTo(Outcome.SIGNALLED);
    h.completeExternal(pid, "core-policy-pack-publish");
    assertThat(h.endActivity(pid)).isEqualTo("daPhatHanh");
  }

  @Test
  void tuChoiKetThucLuong() {
    String pid = start();
    h.service.advance(
        new AdvanceStageCommand(h.tenant, T, version, "pheDuyet", "TU_CHOI", approver, Map.of()));
    assertThat(h.endActivity(pid)).isEqualTo("tuChoi");
  }

  @Test
  void quaHanNhacLaiCungNguoiVoiHanMoi() {
    String pid = start();
    h.fireTimer(pid, "quaHan_pheDuyet");
    Task again = h.task(pid);
    assertThat(again.getAssignee()).isEqualTo(approver.toString());
    assertThat(again.getDueDate().toInstant())
        .isEqualTo(StageEngineHarness.at17(LocalDate.of(2026, 10, 26)));
    assertThat(h.variable(pid, "soLanQuaHan")).isEqualTo("pheDuyet=1");
  }
}
