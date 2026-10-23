package vn.viettel.okrkpi.workflow.application.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.port.WorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.application.saga.SagaCatalog;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.RetryPolicy;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;
import vn.viettel.okrkpi.workflow.domain.template.Release;
import vn.viettel.okrkpi.workflow.domain.template.ReleaseStatus;
import vn.viettel.okrkpi.workflow.domain.template.RolloutStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateLintFinding;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;

/** Thư viện, soạn, đề nghị và phê duyệt phát hành mẫu luồng (DV-06-09, BS-01 SCR-1505). */
class WorkflowTemplateServiceTest {

  static final UUID TENANT = UUID.randomUUID();
  static final UUID AUTHOR = UUID.randomUUID();
  static final UUID APPROVER = UUID.randomUUID();
  static final String XML = "<definitions/>";
  static final Map<String, Object> SCHEMA = Map.of("type", "object");

  TemplateFakes.Store store;
  TemplateFakes.Linter linter;
  TemplateFakes.Usage usage;
  TemplateFakes.Runs runs;
  TemplateFakes.Releases releases;
  TemplateFakes.Rollouts rollouts;
  TemplateFakes.CodeHost codeHost;
  TemplateFakes.Access access;
  TemplateFakes.Engine engine;
  TemplateFakes.Calendar calendar;
  TemplateFakes.Sagas sagaRows;
  ReleaseSettings settings;
  SagaService sagas;
  WorkflowTemplateService service;

  @BeforeEach
  void setUp() {
    store = new TemplateFakes.Store();
    linter = new TemplateFakes.Linter();
    usage = new TemplateFakes.Usage();
    runs = new TemplateFakes.Runs();
    releases = new TemplateFakes.Releases();
    rollouts = new TemplateFakes.Rollouts();
    codeHost = new TemplateFakes.CodeHost();
    access = new TemplateFakes.Access();
    engine = new TemplateFakes.Engine();
    calendar = new TemplateFakes.Calendar();
    sagaRows = new TemplateFakes.Sagas();
    settings =
        new ReleaseSettings(
            List.of("CELL-A", " CELL-B ", "", "CELL-A"),
            Duration.ofMinutes(30),
            RetryPolicy.standard(),
            20);
    TemplateReleaseSaga saga =
        new TemplateReleaseSaga(
            new TemplateReleaseSaga.Dependencies(
                store,
                releases,
                rollouts,
                codeHost,
                new TemplateFakes.Cells(),
                calendar,
                new TemplateFakes.Events(),
                new RolloutProgress(rollouts),
                TemplateFakes.CLOCK),
            settings);
    sagas =
        new SagaService(
            sagaRows,
            new SagaCatalog(List.of(saga), saga.handlers()),
            new TemplateFakes.SagaEngineStub(),
            (s, action, stepNo, success) -> {},
            TemplateFakes.CLOCK);
    service = build(sagas);
  }

  WorkflowTemplateService build(SagaService s) {
    return new WorkflowTemplateService(
        new WorkflowTemplateService.Ports(
            store, linter, usage, runs, releases, rollouts, codeHost, access, engine, calendar),
        s,
        settings,
        TemplateFakes.CLOCK);
  }

  TemplateView create(String code) {
    return service.create(
        new WorkflowTemplateService.CreateCommand(TENANT, code, null, XML, SCHEMA, AUTHOR));
  }

  TemplateVersion tested(String code) {
    TemplateVersion d = create(code).template();
    TemplateVersion t = d.markTested(d.bpmnSha256(), AUTHOR, TemplateFakes.NOW);
    return store.update(t, d.rowVersion()).orElseThrow();
  }

  TemplateVersion pending(String code) {
    TemplateVersion t = tested(code);
    return service.submit(t.id(), t.rowVersion(), AUTHOR).template();
  }

  static void assertCode(Throwable e, String code) {
    assertThat(e).isInstanceOf(BusinessException.class).hasMessage(code);
  }

  @Test
  void taoPhienBanDauVaPhienBanKeTiep() {
    TemplateView v1 = create("mau-a");
    assertThat(v1.template().versionNo()).isEqualTo(1);
    assertThat(v1.template().status()).isEqualTo(TemplateStatus.NHAP);
    assertThat(v1.testCount()).isEqualTo(7);
    assertThat(v1.template().sameContent(WorkflowTemplateService.sha256(XML))).isTrue();
    // Phiên bản 1 phát hành xong mới tạo được phiên bản 2.
    TemplateVersion released =
        v1.template()
            .markTested(v1.template().bpmnSha256(), AUTHOR, TemplateFakes.NOW)
            .submit("r", AUTHOR, TemplateFakes.NOW)
            .publish(new byte[] {1}, APPROVER, null, TemplateFakes.NOW);
    store.rows.put(released.id(), released);
    TemplateView v2 =
        service.create(
            new WorkflowTemplateService.CreateCommand(
                TENANT, "mau-a", released.id(), XML, SCHEMA, AUTHOR));
    assertThat(v2.template().versionNo()).isEqualTo(2);
    assertThat(access.calls).contains("soan");
  }

  @Test
  void taoMauKiemDuLieuVaLinter() {
    assertCode(
        catchThrown(
            () ->
                service.create(
                    new WorkflowTemplateService.CreateCommand(
                        TENANT, "Mau A", null, XML, SCHEMA, AUTHOR))),
        TemplateErrors.VALIDATION);
    assertCode(
        catchThrown(
            () ->
                service.create(
                    new WorkflowTemplateService.CreateCommand(
                        TENANT, null, null, XML, SCHEMA, AUTHOR))),
        TemplateErrors.VALIDATION);
    TemplateVersion other = create("mau-khac").template();
    assertCode(
        catchThrown(
            () ->
                service.create(
                    new WorkflowTemplateService.CreateCommand(
                        TENANT, "mau-b", other.id(), XML, SCHEMA, AUTHOR))),
        TemplateErrors.VALIDATION);
    assertCode(
        catchThrown(
            () ->
                service.create(
                    new WorkflowTemplateService.CreateCommand(
                        TENANT, "mau-b", null, XML, null, AUTHOR))),
        TemplateErrors.VALIDATION);
    assertCode(
        catchThrown(
            () ->
                service.create(
                    new WorkflowTemplateService.CreateCommand(
                        TENANT, "mau-b", null, XML, Map.of("type", "array"), AUTHOR))),
        TemplateErrors.VALIDATION);
    linter.findings = List.of(new TemplateLintFinding("R4_KHONG_GOI_DONG_BO", null, "x"));
    BusinessException lint =
        (BusinessException)
            catchThrown(
                () ->
                    service.create(
                        new WorkflowTemplateService.CreateCommand(
                            TENANT, "mau-b", null, XML, SCHEMA, AUTHOR)));
    assertThat(lint).hasMessage(TemplateErrors.VALIDATION);
    assertThat(lint.errors())
        .singleElement()
        .satisfies(e -> assertThat(e.rule()).isEqualTo("R4_KHONG_GOI_DONG_BO"));
  }

  @Test
  void mauDangCoBanNhapThiKhongTaoThem() {
    create("mau-a");
    assertCode(catchThrown(() -> create("mau-a")), TemplateErrors.DRAFT_EXISTS);
    store.duplicateOnInsert = true;
    assertCode(catchThrown(() -> create("mau-moi")), TemplateErrors.DRAFT_EXISTS);
  }

  @Test
  void suaNhapVaLechPhienBan() {
    TemplateVersion d = create("mau-a").template();
    TemplateView saved =
        service.update(
            new WorkflowTemplateService.UpdateCommand(d.id(), "<x/>", SCHEMA, 0, AUTHOR));
    assertThat(saved.template().rowVersion()).isEqualTo(1);
    assertThat(saved.template().bpmnXml()).isEqualTo("<x/>");
    assertThatThrownBy(
            () ->
                service.update(
                    new WorkflowTemplateService.UpdateCommand(d.id(), "<y/>", SCHEMA, 0, AUTHOR)))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () ->
                service.update(
                    new WorkflowTemplateService.UpdateCommand(
                        UUID.randomUUID(), "<y/>", SCHEMA, 0, AUTHOR)))
        .isInstanceOf(WorkflowNotFoundException.class);
  }

  @Test
  void suaPhienBanHieuLucBiTuChoi() {
    TemplateVersion p = pending("mau-a");
    TemplateVersion published = p.publish(new byte[] {1}, APPROVER, null, TemplateFakes.NOW);
    store.rows.put(published.id(), published);
    assertCode(
        catchThrown(
            () ->
                service.update(
                    new WorkflowTemplateService.UpdateCommand(
                        p.id(), "<y/>", SCHEMA, published.rowVersion(), AUTHOR))),
        TemplateErrors.IMMUTABLE);
  }

  @Test
  void deNghiPhatHanhMoMergeRequest() {
    TemplateVersion d = create("mau-a").template();
    assertCode(
        catchThrown(() -> service.submit(d.id(), d.rowVersion(), AUTHOR)),
        TemplateErrors.NOT_TESTED);
    TemplateVersion t = tested("mau-b");
    TemplateView submitted = service.submit(t.id(), t.rowVersion(), AUTHOR);
    assertThat(submitted.template().status()).isEqualTo(TemplateStatus.CHO_PHE_DUYET);
    assertThat(codeHost.calls).singleElement().asString().startsWith("open:mau-luong/mau-b-v1:3");
    assertThat(releases.find(t.id())).get().extracting(Release::status).isEqualTo(ReleaseStatus.MO);
  }

  @Test
  void nguoiSoanTuPheDuyetBiTuChoi() {
    TemplateVersion p = pending("mau-a");
    assertCode(
        catchThrown(() -> service.approve(p.id(), p.rowVersion(), AUTHOR, TENANT)),
        TemplateErrors.SAME_AUTHOR);
    assertThat(access.calls).doesNotContain("phe-duyet");
    assertThat(sagaRows.rows).isEmpty();
  }

  @Test
  void pheDuyetKhoiDongSagaVaLapKeHoachCell() {
    TemplateVersion p = pending("mau-a");
    SagaInstance saga = service.approve(p.id(), p.rowVersion(), APPROVER, TENANT);
    assertThat(saga.type()).isEqualTo(SagaType.PHAT_HANH_MAU_LUONG);
    assertThat(saga.steps()).hasSize(4);
    assertThat(saga.parameters())
        .containsEntry(TemplateReleaseSaga.P_TEMPLATE_CODE, "mau-a")
        .containsEntry(TemplateReleaseSaga.P_CELLS, List.of("CELL-A", "CELL-B"));
    assertThat(access.lastApprove.participants().get("initiator")).contains(AUTHOR.toString());
    assertThat(releases.find(p.id()).orElseThrow().approvedBy()).isEqualTo(APPROVER);
    assertThat(releases.find(p.id()).orElseThrow().status()).isEqualTo(ReleaseStatus.DA_DUYET);
    assertThat(service.rollouts(p.id()))
        .extracting(r -> r.cellCode() + ":" + r.status())
        .containsExactly("CELL-A:" + RolloutStatus.CHO, "CELL-B:" + RolloutStatus.CHO);
    // Gọi lại: trả saga cũ; người khác phê duyệt lại: 409.
    assertThat(service.approve(p.id(), p.rowVersion(), APPROVER, TENANT).id()).isEqualTo(saga.id());
    assertCode(
        catchThrown(() -> service.approve(p.id(), p.rowVersion(), UUID.randomUUID(), TENANT)),
        TemplateErrors.IMMUTABLE);
  }

  @Test
  void pheDuyetTrongKhungCamHoacThieuDeNghi() {
    TemplateVersion p = pending("mau-a");
    calendar.blockNow();
    assertCode(
        catchThrown(() -> service.approve(p.id(), p.rowVersion(), APPROVER, TENANT)),
        TemplateErrors.ROLLOUT_WINDOW);
    calendar = new TemplateFakes.Calendar();
    service = build(sagas);
    releases.rows.clear();
    assertCode(
        catchThrown(() -> service.approve(p.id(), p.rowVersion(), APPROVER, TENANT)),
        TemplateErrors.NOT_TESTED);
    TemplateVersion q = pending("mau-b");
    assertThatThrownBy(() -> build(null).approve(q.id(), q.rowVersion(), APPROVER, TENANT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.approve(q.id(), q.rowVersion() + 5, APPROVER, TENANT))
        .isInstanceOf(VersionConflictException.class);
    TemplateVersion d = create("mau-c").template();
    assertCode(
        catchThrown(() -> service.approve(d.id(), d.rowVersion(), APPROVER, TENANT)),
        TemplateErrors.NOT_TESTED);
  }

  @Test
  void ghiChuKyVaTraLoiSaga() {
    TemplateVersion p = pending("mau-a");
    assertCode(
        catchThrown(() -> service.recordSignature(p.id(), new byte[0], "p")),
        TemplateErrors.VALIDATION);
    assertCode(
        catchThrown(
            () -> service.recordSignature(create("mau-z").template().id(), new byte[] {1}, "p")),
        TemplateErrors.NOT_TESTED);
    // Chưa phê duyệt: ghi chữ ký nhưng chưa có saga để trả lời.
    assertThat(service.recordSignature(p.id(), new byte[] {9}, "pipeline-1")).isFalse();
    assertThat(releases.find(p.id()).orElseThrow().signed()).isTrue();
    TemplateVersion q = pending("mau-b");
    SagaInstance saga = service.approve(q.id(), q.rowVersion(), APPROVER, TENANT);
    assertThat(service.recordSignature(q.id(), new byte[] {9}, "pipeline-2")).isTrue();
    assertThat(sagaRows.rows.get(saga.id())).isNotNull();
    assertThat(build(null).recordSignature(q.id(), new byte[] {9}, "pipeline-2")).isFalse();
  }

  @Test
  void thuVienVaXemMau() {
    TemplateVersion a = create("mau-a").template();
    create("mau-b");
    store.tests.put(a.id(), 26);
    store.urls.put(a.id(), "https://ma-nguon.example.invalid/mr/1");
    List<TemplateView> page = service.list(null, null, null, 10);
    assertThat(page).hasSize(2);
    assertThat(page.get(0).testCount()).isEqualTo(26);
    assertThat(page.get(0).mergeRequestUrl()).isNotNull();
    assertThat(service.list(TemplateStatus.HIEU_LUC, null, null, 10)).isEmpty();
    assertThat(service.list(null, "b", null, 10)).hasSize(1);
    assertThat(service.list(null, null, WorkflowTemplateStore.cursorKey(a), 10))
        .extracting(v -> v.template().templateCode())
        .containsExactly("mau-b");
    assertThat(service.get(a.id()).template().id()).isEqualTo(a.id());
    assertThatThrownBy(() -> service.get(UUID.randomUUID()))
        .isInstanceOf(WorkflowNotFoundException.class);
    assertThatThrownBy(() -> service.rollouts(UUID.randomUUID()))
        .isInstanceOf(WorkflowNotFoundException.class);
    assertThat(service.scenarioCount()).isEqualTo(7);
    assertThat(access.calls).contains("thu-vien");
  }

  @Test
  void quyenBiTuChoiThiDungTruocKhiGhi() {
    access.denied.add("soan");
    assertThatThrownBy(() -> create("mau-a")).isInstanceOf(BusinessException.class);
    assertThat(store.rows).isEmpty();
  }

  @Test
  void anhXaLoiVongDoi() {
    assertThat(
            TemplateErrors.of(
                new vn.viettel.okrkpi.workflow.domain.template.TemplateRuleViolation(
                    vn.viettel.okrkpi.workflow.domain.template.TemplateRuleViolation.Reason
                        .KHONG_CHO_PHE_DUYET,
                    "x"),
                TemplateStatus.HIEU_LUC))
        .hasMessage(TemplateErrors.IMMUTABLE);
    assertThat(
            TemplateErrors.of(
                new vn.viettel.okrkpi.workflow.domain.template.TemplateRuleViolation(
                    vn.viettel.okrkpi.workflow.domain.template.TemplateRuleViolation.Reason
                        .THIEU_CHU_KY,
                    "x"),
                TemplateStatus.CHO_PHE_DUYET))
        .hasMessage(TemplateErrors.NOT_TESTED);
    assertThat(
            TemplateErrors.lint(List.of(new TemplateLintFinding("R1", "duyetCap1", "m")))
                .errors()
                .get(0)
                .field())
        .isEqualTo("duyetCap1");
    assertThatThrownBy(
            () -> new ReleaseSettings(List.of(), Duration.ofMinutes(1), RetryPolicy.standard(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TemplateView(null, 0, 0, null))
        .isInstanceOf(NullPointerException.class);
    String json = Json.write(Map.of("b", 1, "a", 2));
    assertThat(json.indexOf("\"a\"")).isLessThan(json.indexOf("\"b\""));
  }

  static Throwable catchThrown(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      return e;
    }
    throw new AssertionError("Mong đợi ngoại lệ");
  }
}
