package vn.viettel.okrkpi.workflow.application.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandler;
import vn.viettel.okrkpi.workflow.application.saga.StepInvocation;
import vn.viettel.okrkpi.workflow.application.saga.StepResult;
import vn.viettel.okrkpi.workflow.domain.saga.RetryPolicy;
import vn.viettel.okrkpi.workflow.domain.saga.SagaDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;
import vn.viettel.okrkpi.workflow.domain.template.Release;
import vn.viettel.okrkpi.workflow.domain.template.ReleaseStatus;
import vn.viettel.okrkpi.workflow.domain.template.RolloutStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;

/**
 * Các bước của saga phát hành cuốn chiếu: chờ ký, chuyển hiệu lực và phát sự kiện, triển khai từng
 * cell có kiểm sức khoẻ, khung cấm và bù trừ đặt lại phiên bản trước (BS-01 "Triển khai lên cell").
 */
class TemplateReleaseSagaTest {

  static final UUID TENANT = UUID.randomUUID();
  static final UUID AUTHOR = UUID.randomUUID();
  static final UUID APPROVER = UUID.randomUUID();

  TemplateFakes.Store store;
  TemplateFakes.Releases releases;
  TemplateFakes.Rollouts rollouts;
  TemplateFakes.CodeHost codeHost;
  TemplateFakes.Cells cells;
  TemplateFakes.Calendar calendar;
  TemplateFakes.Events events;
  TemplateReleaseSaga saga;
  Map<String, SagaStepHandler> handlers;
  TemplateVersion pending;
  TemplateVersion previous;

  @BeforeEach
  void setUp() {
    store = new TemplateFakes.Store();
    releases = new TemplateFakes.Releases();
    rollouts = new TemplateFakes.Rollouts();
    codeHost = new TemplateFakes.CodeHost();
    cells = new TemplateFakes.Cells();
    calendar = new TemplateFakes.Calendar();
    events = new TemplateFakes.Events();
    ReleaseSettings settings =
        new ReleaseSettings(
            List.of("CELL-A", "CELL-B"), Duration.ofMinutes(30), RetryPolicy.standard(), 20);
    saga =
        new TemplateReleaseSaga(
            new TemplateReleaseSaga.Dependencies(
                store,
                releases,
                rollouts,
                codeHost,
                cells,
                calendar,
                events,
                new RolloutProgress(rollouts),
                TemplateFakes.CLOCK),
            settings);
    handlers = new HashMap<>();
    saga.handlers().forEach(h -> handlers.put(h.stepCode(), h));
    previous =
        TemplateVersion.draft(
                UUID.randomUUID(),
                TENANT,
                "mau-a",
                1,
                "<v1/>",
                WorkflowTemplateService.sha256("<v1/>"),
                Map.of(),
                AUTHOR,
                TemplateFakes.NOW)
            .markTested(WorkflowTemplateService.sha256("<v1/>"), AUTHOR, TemplateFakes.NOW)
            .submit("r", AUTHOR, TemplateFakes.NOW)
            .publish(new byte[] {1}, APPROVER, null, TemplateFakes.NOW);
    store.rows.put(previous.id(), previous);
    pending =
        TemplateVersion.draft(
                UUID.randomUUID(),
                TENANT,
                "mau-a",
                2,
                "<v2/>",
                WorkflowTemplateService.sha256("<v2/>"),
                Map.of(),
                AUTHOR,
                TemplateFakes.NOW)
            .markTested(WorkflowTemplateService.sha256("<v2/>"), AUTHOR, TemplateFakes.NOW)
            .submit("r", AUTHOR, TemplateFakes.NOW);
    store.rows.put(pending.id(), pending);
    releases.insert(
        new Release(pending.id(), "mr-2", null, ReleaseStatus.MO, AUTHOR, null, null, null, null),
        AUTHOR);
    rollouts.plan(pending.id(), List.of("CELL-A", "CELL-B"), UUID.randomUUID(), APPROVER);
    cells.current.put("CELL-A", 1);
    cells.current.put("CELL-B", 1);
  }

  StepInvocation inv(int stepNo, String code, boolean compensation) {
    Map<String, Object> p = new HashMap<>();
    p.put(TemplateReleaseSaga.P_TEMPLATE_ID, pending.id().toString());
    p.put(TemplateReleaseSaga.P_TEMPLATE_CODE, "mau-a");
    p.put(TemplateReleaseSaga.P_VERSION_NO, 2);
    p.put(TemplateReleaseSaga.P_APPROVER, APPROVER.toString());
    p.put(TemplateReleaseSaga.P_CELLS, new ArrayList<>(List.of("CELL-A", "CELL-B")));
    return new StepInvocation(
        UUID.randomUUID(),
        TENANT,
        SagaType.PHAT_HANH_MAU_LUONG,
        pending.id(),
        stepNo,
        code,
        compensation ? TemplateReleaseSaga.COMPENSATION_CELL : null,
        1,
        p);
  }

  @Test
  void dinhNghiaTheoThuTuCellCauHinh() {
    SagaDefinition d = saga.definition();
    assertThat(d.type()).isEqualTo(SagaType.PHAT_HANH_MAU_LUONG);
    assertThat(d.steps())
        .extracting(s -> s.stepCode())
        .containsExactly(
            "CHO_KY_SO", "CHUYEN_HIEU_LUC", "TRIEN_KHAI_CELL_01", "TRIEN_KHAI_CELL_02");
    assertThat(d.step(1).awaitsReply()).isTrue();
    assertThat(d.step(3).compensationCode()).isEqualTo("DAT_LAI_MAC_DINH");
    assertThat(d.compensateOnExhaustion()).isTrue();
    assertThat(saga.exclusiveKey(Map.of())).isNull();
    assertThat(handlers).hasSize(4);
    assertThat(handlers.values()).allMatch(h -> h.sagaType() == SagaType.PHAT_HANH_MAU_LUONG);
  }

  @Test
  void choKySoRoiChuyenHieuLucVaPhatSuKien() {
    StepResult waiting = handlers.get("CHO_KY_SO").execute(inv(1, "CHO_KY_SO", false));
    assertThat(waiting).isInstanceOf(StepResult.AwaitingReply.class);
    assertThat(codeHost.calls).containsExactly("approve:mr-2");
    assertThatThrownBy(
            () -> handlers.get("CHUYEN_HIEU_LUC").execute(inv(2, "CHUYEN_HIEU_LUC", false)))
        .isInstanceOf(IllegalStateException.class);
    releases.signed(pending.id(), new byte[] {7, 7}, "pipeline-1");
    assertThat(handlers.get("CHO_KY_SO").execute(inv(1, "CHO_KY_SO", false)))
        .isInstanceOf(StepResult.Completed.class);
    assertThat(handlers.get("CHUYEN_HIEU_LUC").execute(inv(2, "CHUYEN_HIEU_LUC", false)))
        .isInstanceOf(StepResult.Completed.class);
    TemplateVersion published = store.find(pending.id()).orElseThrow();
    assertThat(published.status()).isEqualTo(TemplateStatus.HIEU_LUC);
    assertThat(published.signature()).containsExactly(7, 7);
    assertThat(published.approvedBy()).isEqualTo(APPROVER);
    assertThat(releases.find(pending.id()).orElseThrow().status())
        .isEqualTo(ReleaseStatus.DA_AP_DUNG);
    assertThat(events.published).containsExactly("mau-a@2:[CELL-A, CELL-B]");
    // Chạy lại: không phát trùng.
    handlers.get("CHUYEN_HIEU_LUC").execute(inv(2, "CHUYEN_HIEU_LUC", false));
    assertThat(events.published).hasSize(1);
  }

  @Test
  void trienKhaiCellKhoeGhiPhienBanTruoc() {
    StepResult r = handlers.get("TRIEN_KHAI_CELL_01").execute(inv(3, "TRIEN_KHAI_CELL_01", false));
    assertThat(r).isInstanceOf(StepResult.Completed.class);
    assertThat(rollouts.find(pending.id(), "CELL-A").orElseThrow().status())
        .isEqualTo(RolloutStatus.DA_TRIEN_KHAI);
    assertThat(rollouts.find(pending.id(), "CELL-A").orElseThrow().previousVersionNo())
        .isEqualTo(1);
    assertThat(rollouts.find(pending.id(), "CELL-A").orElseThrow().deploymentId())
        .isEqualTo("dep-CELL-A");
    // Chạy lại sau khi đã triển khai: không triển khai lại.
    handlers.get("TRIEN_KHAI_CELL_01").execute(inv(3, "TRIEN_KHAI_CELL_01", false));
    assertThat(cells.calls).containsExactly("deploy:CELL-A:2");
  }

  @Test
  void cellKhongKhoeTruocKhiTrienKhaiThiLoiVaKhongTrienKhai() {
    cells.unhealthyBefore.add("CELL-B");
    assertThatThrownBy(
            () -> handlers.get("TRIEN_KHAI_CELL_02").execute(inv(4, "TRIEN_KHAI_CELL_02", false)))
        .isInstanceOf(TemplateReleaseSaga.CellRolloutException.class)
        .hasMessageContaining("CELL-B");
    assertThat(rollouts.find(pending.id(), "CELL-B").orElseThrow().status())
        .isEqualTo(RolloutStatus.LOI);
    assertThat(cells.calls).isEmpty();
  }

  @Test
  void cellKhongKhoeSauKhiTrienKhaiThiDatLaiNgay() {
    cells.unhealthyAfter.add("CELL-A");
    assertThatThrownBy(
            () -> handlers.get("TRIEN_KHAI_CELL_01").execute(inv(3, "TRIEN_KHAI_CELL_01", false)))
        .isInstanceOf(TemplateReleaseSaga.CellRolloutException.class);
    assertThat(cells.calls).containsExactly("deploy:CELL-A:2", "restore:CELL-A:1:true");
    assertThat(cells.current).containsEntry("CELL-A", 1);
    assertThat(rollouts.errors.get("CELL-A")).contains("sau khi triển khai");
  }

  @Test
  void trongKhungCamThiKhongTrienKhai() {
    calendar.blockNow();
    assertThatThrownBy(
            () -> handlers.get("TRIEN_KHAI_CELL_01").execute(inv(3, "TRIEN_KHAI_CELL_01", false)))
        .isInstanceOf(TemplateReleaseSaga.CellRolloutException.class)
        .hasMessageContaining("giờ cao điểm");
    assertThat(cells.calls).isEmpty();
  }

  @Test
  void buTruDatLaiPhienBanTruocLamMacDinh() {
    handlers.get("TRIEN_KHAI_CELL_01").execute(inv(3, "TRIEN_KHAI_CELL_01", false));
    handlers.get("TRIEN_KHAI_CELL_01").compensate(inv(3, "TRIEN_KHAI_CELL_01", true));
    assertThat(cells.calls).containsExactly("deploy:CELL-A:2", "restore:CELL-A:1:true");
    assertThat(rollouts.find(pending.id(), "CELL-A").orElseThrow().status())
        .isEqualTo(RolloutStatus.DA_BU_TRU);
    // Bù trừ lại hoặc bù trừ cell chưa triển khai: không làm gì thêm.
    handlers.get("TRIEN_KHAI_CELL_01").compensate(inv(3, "TRIEN_KHAI_CELL_01", true));
    assertThat(cells.calls).hasSize(2);
  }

  @Test
  void cellChuaTungCoMauThiBuTruGoBanMoi() {
    cells.current.clear();
    handlers.get("TRIEN_KHAI_CELL_02").execute(inv(4, "TRIEN_KHAI_CELL_02", false));
    assertThat(rollouts.find(pending.id(), "CELL-B").orElseThrow().previousVersionNo()).isNull();
    handlers.get("TRIEN_KHAI_CELL_02").compensate(inv(4, "TRIEN_KHAI_CELL_02", true));
    assertThat(cells.calls).containsExactly("deploy:CELL-B:2", "restore:CELL-B:null:false");
  }

  @Test
  void thamSoThieuCellThiLoi() {
    StepInvocation base = inv(4, "TRIEN_KHAI_CELL_02", false);
    Map<String, Object> p = new HashMap<>(base.parameters());
    p.put(TemplateReleaseSaga.P_CELLS, List.of("CELL-A"));
    StepInvocation broken =
        new StepInvocation(
            base.sagaId(),
            TENANT,
            base.sagaType(),
            base.correlationId(),
            4,
            base.stepCode(),
            null,
            1,
            p);
    assertThatThrownBy(() -> handlers.get("TRIEN_KHAI_CELL_02").execute(broken))
        .isInstanceOf(IllegalStateException.class);
    p.put(TemplateReleaseSaga.P_CELLS, "khong-phai-danh-sach");
    assertThat(TemplateReleaseSaga.cells(broken)).isEmpty();
  }
}
