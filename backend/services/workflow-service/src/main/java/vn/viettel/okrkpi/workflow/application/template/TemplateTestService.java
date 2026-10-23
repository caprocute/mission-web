package vn.viettel.okrkpi.workflow.application.template;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.port.TemplateAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.TemplateBindingUsage;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestDispatcher;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestEngine;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestRunStore;
import vn.viettel.okrkpi.workflow.application.port.WorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.domain.template.BindingUsage;
import vn.viettel.okrkpi.workflow.domain.template.TemplateRuleViolation;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;
import vn.viettel.okrkpi.workflow.domain.template.TestCaseResult;
import vn.viettel.okrkpi.workflow.domain.template.TestReport;
import vn.viettel.okrkpi.workflow.domain.template.TestRun;
import vn.viettel.okrkpi.workflow.domain.template.TestRunStatus;

/**
 * {@code runWorkflowTemplateTests} (BS-01 "Kiểm thử", "Giới hạn để không ảnh hưởng mức tải"): nhận
 * yêu cầu, trả lần chạy ở {@code CHO} (202 + Job), rồi ở luồng nền nạp mẫu vào engine thử, chạy mọi
 * kịch bản với tham số của mọi {@code wf_binding} đang dùng mẫu và đếm câu SQL mỗi lần hoàn thành
 * user task. Đạt hết và trong ngân sách thì mẫu chuyển {@code DAT_KIEM_THU}; không thì giữ {@code
 * NHAP} và không đề nghị phát hành được.
 */
public class TemplateTestService {

  private static final Logger LOG = LoggerFactory.getLogger(TemplateTestService.class);

  private final WorkflowTemplateStore store;
  private final TemplateTestRunStore runs;
  private final TemplateBindingUsage usage;
  private final TemplateTestEngine engine;
  private final TemplateAccessPolicy access;
  private final TemplateTestDispatcher dispatcher;
  private final TransactionOperations tx;
  private final int sqlBudget;
  private final Clock clock;

  public TemplateTestService(
      WorkflowTemplateStore store,
      TemplateTestRunStore runs,
      TemplateBindingUsage usage,
      TemplateTestEngine engine,
      TemplateAccessPolicy access,
      TemplateTestDispatcher dispatcher,
      TransactionOperations tx,
      int sqlBudget,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.usage = Objects.requireNonNull(usage, "usage");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.access = Objects.requireNonNull(access, "access");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.sqlBudget = sqlBudget;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Nhận yêu cầu chạy test: mẫu phải ở {@code NHAP} hoặc {@code DAT_KIEM_THU}; đang có lần chạy
   * cùng nội dung thì trả lần đó. Lần chạy bắt đầu sau khi giao dịch commit.
   */
  @Transactional
  public TestRun request(UUID templateId, UUID actor) {
    access.checkTest(
        AuthzResource.builder()
            .type("wf_template")
            .id(templateId.toString())
            .classification("C0")
            .build());
    TemplateVersion template = store.find(templateId).orElseThrow(WorkflowNotFoundException::new);
    try {
      template.requireTestable();
    } catch (TemplateRuleViolation v) {
      throw TemplateErrors.of(v, template.status());
    }
    Optional<TestRun> active = runs.active(templateId, template.bpmnSha256());
    if (active.isPresent()) {
      return active.get();
    }
    TestRun run =
        new TestRun(
            Ids.newUuidV7(),
            template.tenantId(),
            templateId,
            template.bpmnSha256(),
            TestRunStatus.CHO,
            null,
            actor,
            clock.instant());
    runs.insert(run);
    UUID tenant = template.tenantId();
    Runnable work = () -> TenantContext.run(TenantId.of(tenant), () -> execute(run.id()));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatcher.dispatch(work);
            }
          });
    } else {
      dispatcher.dispatch(work);
    }
    return run;
  }

  @Transactional(readOnly = true)
  public Optional<TestRun> find(UUID runId) {
    return runs.find(runId);
  }

  /**
   * Chạy một lần test (luồng nền, đã có ngữ cảnh tenant). Engine thử chạy ngoài giao dịch CSDL của
   * cell; chỉ đọc đầu vào và ghi kết quả là hai giao dịch ngắn.
   */
  public void execute(UUID runId) {
    Prepared p = tx.execute(status -> prepare(runId));
    if (p == null) {
      return;
    }
    TestReport report;
    try {
      List<TestCaseResult> cases =
          engine.run(p.template().templateCode(), p.template().bpmnXml(), p.bindings());
      report = new TestReport(cases, p.bindings().size(), sqlBudget);
    } catch (RuntimeException e) {
      LOG.warn(
          "Lần chạy test {} của mẫu {} lỗi: {}", runId, p.template().templateCode(), e.toString());
      report = new TestReport(List.of(), p.bindings().size(), sqlBudget);
    }
    TestReport done = report;
    tx.executeWithoutResult(status -> finish(p, done));
  }

  private Prepared prepare(UUID runId) {
    Optional<TestRun> run = runs.find(runId);
    if (run.isEmpty() || run.get().status().terminal()) {
      return null;
    }
    Optional<TemplateVersion> template = store.find(run.get().templateId());
    if (template.isEmpty()) {
      runs.finish(runId, TestRunStatus.THAT_BAI, new TestReport(List.of(), 0, sqlBudget));
      return null;
    }
    runs.markRunning(runId);
    List<BindingUsage> bindings = usage.bindings(template.get().templateCode());
    return new Prepared(run.get(), template.get(), bindings);
  }

  private void finish(Prepared p, TestReport report) {
    boolean passed = report.passed();
    runs.finish(p.run().id(), passed ? TestRunStatus.HOAN_TAT : TestRunStatus.THAT_BAI, report);
    if (!passed) {
      return;
    }
    store
        .find(p.template().id())
        .filter(t -> t.status().editable() && t.sameContent(p.run().bpmnSha256()))
        .ifPresent(
            t ->
                store.update(
                    t.markTested(p.run().bpmnSha256(), p.run().requestedBy(), clock.instant()),
                    t.rowVersion()));
  }

  private record Prepared(TestRun run, TemplateVersion template, List<BindingUsage> bindings) {}
}
