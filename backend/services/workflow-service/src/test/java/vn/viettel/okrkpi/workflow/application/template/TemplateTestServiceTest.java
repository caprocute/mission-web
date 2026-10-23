package vn.viettel.okrkpi.workflow.application.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.domain.template.BindingUsage;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;
import vn.viettel.okrkpi.workflow.domain.template.TestCaseResult;
import vn.viettel.okrkpi.workflow.domain.template.TestRun;
import vn.viettel.okrkpi.workflow.domain.template.TestRunStatus;

/** Bộ test mẫu luồng trên engine thử và ngân sách câu SQL (BS-01 "Kiểm thử", "Giới hạn tải"). */
class TemplateTestServiceTest {

  static final UUID TENANT = UUID.randomUUID();
  static final UUID AUTHOR = UUID.randomUUID();

  TemplateFakes.Store store;
  TemplateFakes.Runs runs;
  TemplateFakes.Usage usage;
  TemplateFakes.Engine engine;
  TemplateFakes.Access access;
  TemplateFakes.Dispatcher dispatcher;
  TemplateTestService service;
  TemplateVersion draft;

  @BeforeEach
  void setUp() {
    store = new TemplateFakes.Store();
    runs = new TemplateFakes.Runs();
    usage = new TemplateFakes.Usage();
    engine = new TemplateFakes.Engine();
    access = new TemplateFakes.Access();
    dispatcher = new TemplateFakes.Dispatcher();
    service =
        new TemplateTestService(
            store,
            runs,
            usage,
            engine,
            access,
            dispatcher,
            TransactionOperations.withoutTransaction(),
            20,
            TemplateFakes.CLOCK);
    draft =
        store.insert(
            TemplateVersion.draft(
                UUID.randomUUID(),
                TENANT,
                "mau-a",
                1,
                "<definitions/>",
                WorkflowTemplateService.sha256("<definitions/>"),
                Map.of(),
                AUTHOR,
                TemplateFakes.NOW));
  }

  TestRun runOnce() {
    TestRun run = service.request(draft.id(), AUTHOR);
    assertThat(run.status()).isEqualTo(TestRunStatus.CHO);
    assertThat(dispatcher.queued).hasSize(1);
    dispatcher.queued.remove(0).run();
    return service.find(run.id()).orElseThrow();
  }

  @Test
  void datHetVaTrongNganSachThiDatKiemThu() {
    usage.bindings =
        List.of(new BindingUsage(UUID.randomUUID(), UUID.randomUUID(), "OKR", "DANG", Map.of()));
    TestRun done = runOnce();
    assertThat(done.status()).isEqualTo(TestRunStatus.HOAN_TAT);
    assertThat(done.report().bindingCount()).isEqualTo(1);
    assertThat(engine.seen).hasSize(1);
    assertThat(store.find(draft.id()).orElseThrow().status())
        .isEqualTo(TemplateStatus.DAT_KIEM_THU);
    assertThat(access.calls).contains("chay-test");
  }

  @Test
  void vuotNganSachSqlThiKhongDat() {
    engine.results =
        List.of(new TestCaseResult("CHAY_HET_LUONG", "Tham số mặc định", "ketThuc", true, 1, 113));
    TestRun done = runOnce();
    assertThat(done.status()).isEqualTo(TestRunStatus.THAT_BAI);
    assertThat(done.message()).contains("113").contains("vượt ngân sách 20");
    assertThat(store.find(draft.id()).orElseThrow().status()).isEqualTo(TemplateStatus.NHAP);
  }

  @Test
  void engineLoiThiLanChayThatBai() {
    engine.failure = new IllegalStateException("engine thử hỏng");
    TestRun done = runOnce();
    assertThat(done.status()).isEqualTo(TestRunStatus.THAT_BAI);
    assertThat(done.report().total()).isZero();
  }

  @Test
  void noiDungDoiTrongLucChayThiKhongDanhDauDat() {
    TestRun run = service.request(draft.id(), AUTHOR);
    TemplateVersion edited =
        draft.edit(
            "<khac/>",
            WorkflowTemplateService.sha256("<khac/>"),
            Map.of(),
            AUTHOR,
            TemplateFakes.NOW);
    store.update(edited, draft.rowVersion());
    dispatcher.queued.remove(0).run();
    assertThat(service.find(run.id()).orElseThrow().status()).isEqualTo(TestRunStatus.HOAN_TAT);
    assertThat(store.find(draft.id()).orElseThrow().status()).isEqualTo(TemplateStatus.NHAP);
  }

  @Test
  void dangChayThiTraLanCuVaKetThucRoiThiBoQua() {
    TestRun first = service.request(draft.id(), AUTHOR);
    TestRun again = service.request(draft.id(), AUTHOR);
    assertThat(again.id()).isEqualTo(first.id());
    dispatcher.queued.remove(0).run();
    // Lần chạy đã kết thúc: chạy lại việc nền không làm gì.
    service.execute(first.id());
    service.execute(UUID.randomUUID());
    assertThat(runs.rows).hasSize(1);
  }

  @Test
  void mauBiXoaTrongLucChoThiThatBai() {
    TestRun run = service.request(draft.id(), AUTHOR);
    store.rows.clear();
    dispatcher.queued.remove(0).run();
    assertThat(runs.find(run.id()).orElseThrow().status()).isEqualTo(TestRunStatus.THAT_BAI);
  }

  @Test
  void mauDaPhatHanhKhongChayTest() {
    TemplateVersion released =
        draft
            .markTested(draft.bpmnSha256(), AUTHOR, TemplateFakes.NOW)
            .submit("r", AUTHOR, TemplateFakes.NOW)
            .publish(new byte[] {1}, UUID.randomUUID(), null, TemplateFakes.NOW);
    store.rows.put(released.id(), released);
    assertThatThrownBy(() -> service.request(draft.id(), AUTHOR))
        .isInstanceOf(BusinessException.class)
        .hasMessage(TemplateErrors.IMMUTABLE);
    assertThatThrownBy(() -> service.request(UUID.randomUUID(), AUTHOR))
        .isInstanceOf(WorkflowNotFoundException.class);
  }

  @Test
  void trongGiaoDichThiChayNenSauKhiCommit() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.request(draft.id(), AUTHOR);
      assertThat(dispatcher.queued).isEmpty();
      TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
      assertThat(dispatcher.queued).hasSize(1);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }
}
