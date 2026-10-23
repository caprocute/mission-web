package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.workflow.application.CompleteTaskService;
import vn.viettel.okrkpi.workflow.application.dual.DualApprovalHooks;
import vn.viettel.okrkpi.workflow.application.dual.DualApprovalPlanner;
import vn.viettel.okrkpi.workflow.application.dual.DualApprovalService;
import vn.viettel.okrkpi.workflow.application.port.ApprovalAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.ExternalJobQueue.CallbackJob;
import vn.viettel.okrkpi.workflow.application.stage.StageProcessService.Outcome;
import vn.viettel.okrkpi.workflow.application.stage.StageScheduler;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.DualApprovalListener;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableApprovalProcesses;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableDualApprovalProcesses;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableExternalJobQueue;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableTaskEngine;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateCatalog;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateDeployer;

/**
 * Kiểm thử Flowable của mẫu {@code duyet-kep-chi-huy-cap-uy} (DV-06-12) trên engine thật (H2), gọi
 * từ một luồng cha bằng call activity, hoàn thành việc qua {@link CompleteTaskService} thật như hộp
 * việc: hai nhánh duyệt, một nhánh trả lại, hai nhánh kết thúc cùng lúc, quá hạn chuyển người được
 * uỷ quyền, một người ở hai nhánh, ký số tuỳ chọn mỗi nhánh.
 */
class DuyetKepChiHuyCapUyFlowTest {

  static final String PARENT = "duyet-thu-kep";

  ProcessEngine engine;
  final ApprovalEngineHarness.Org org = new ApprovalEngineHarness.Org();
  final ApprovalEngineHarness.Calendar calendar = new ApprovalEngineHarness.Calendar();
  final StageEngineHarness.Bindings bindings = new StageEngineHarness.Bindings();
  final UUID tenant = UUID.randomUUID();
  ApprovalEngineHarness.Tree tree;
  FlowableTaskEngine tasks;
  DualApprovalService dual;
  FlowableExternalJobQueue jobs;
  final ThreadLocal<Approver> caller = new ThreadLocal<>();
  CompleteTaskService completer;
  UUID submitter;
  UUID objectId;

  @BeforeEach
  void setUp() {
    ObjectMapper json = new ObjectMapper();
    Clock clock = Clock.fixed(StageEngineHarness.NOW, ZoneOffset.UTC);
    tree = org.tree(tenant);
    StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
    cfg.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    cfg.setAsyncExecutorActivate(false);
    cfg.setHistory("audit");
    cfg.setDisableIdmEngine(true);
    cfg.setDisableEventRegistry(true);
    Map<Object, Object> beans = new HashMap<>();
    beans.put(
        "lapLichKep",
        new DualApprovalListener(
            new DualApprovalHooks(
                bindings,
                new DualApprovalPlanner(json),
                new StageScheduler(org, calendar, clock))));
    cfg.setBeans(beans);
    engine = cfg.buildProcessEngine();
    new TemplateDeployer(engine.getRepositoryService(), engine.getRuntimeService(), json)
        .deploy(TemplateCatalog.load(json));
    engine
        .getRepositoryService()
        .createDeployment()
        .addClasspathResource("bpmn-test/duyet-thu-kep.bpmn20.xml")
        .deploy();
    tasks =
        new FlowableTaskEngine(
            engine.getTaskService(),
            engine.getRuntimeService(),
            engine.getHistoryService(),
            clock,
            new SimpleMeterRegistry());
    completer = new CompleteTaskService(tasks, caller::get, new AllowAll());
    dual =
        new DualApprovalService(
            new FlowableDualApprovalProcesses(
                engine.getRuntimeService(), engine.getHistoryService()));
    jobs =
        new FlowableExternalJobQueue(
            engine.getManagementService(), engine.getRuntimeService(), engine.getHistoryService());
    submitter = UUID.randomUUID();
    objectId = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    engine.close();
  }

  String start() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("tenantId", tenant.toString());
    vars.put("khoi", "DANG");
    vars.put("donViId", tree.banA().toString());
    vars.put("tieuDe", "Kế hoạch huấn luyện quý I");
    return new FlowableApprovalProcesses(
            engine.getRuntimeService(), engine.getHistoryService(), engine.getTaskService(), null)
        .start(tenant, PARENT, new BusinessKey("OKR_OBJECTIVE", objectId), submitter, vars);
  }

  String child(String parent) {
    return engine
        .getRuntimeService()
        .createProcessInstanceQuery()
        .superProcessInstanceId(parent)
        .singleResult()
        .getId();
  }

  Task open(String child, String branch) {
    return engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(child)
        .taskDefinitionKey(branch)
        .singleResult();
  }

  List<String> groups(Task task) {
    List<String> out = new ArrayList<>();
    engine.getTaskService().getIdentityLinksForTask(task.getId()).stream()
        .filter(l -> "candidate".equals(l.getType()))
        .forEach(l -> out.add(l.getGroupId()));
    return out;
  }

  String group(UUID unit) {
    return tenant + ":leader:" + unit;
  }

  Approver leader(UUID person, UUID unit) {
    return new Approver(tenant, person, Set.of("leader"), unit);
  }

  CompleteTaskService.Result complete(Task task, Approver who, Decision decision) {
    caller.set(who);
    try {
      int revision = tasks.findOpen(tenant, task.getId()).orElseThrow().revision();
      return completer.complete(
          new CompleteTaskService.Command(
              task.getId(),
              revision,
              decision,
              decision == Decision.TRA_LAI ? "Đề nghị bổ sung chỉ tiêu." : null));
    } finally {
      caller.remove();
    }
  }

  boolean ended(String pid) {
    return engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count()
        == 0;
  }

  String endActivity(String pid) {
    return engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceId(pid)
        .singleResult()
        .getEndActivityId();
  }

  void fireTimer(String pid, String elementId) {
    Job timer =
        engine.getManagementService().createTimerJobQuery().processInstanceId(pid).list().stream()
            .filter(j -> elementId.equals(j.getElementId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Không có timer " + elementId));
    Job executable = engine.getManagementService().moveTimerToExecutableJob(timer.getId());
    engine.getManagementService().executeJob(executable.getId());
  }

  @Test
  void caHaiNhanhDuyetThiLuongChaDiTiep() {
    String parent = start();
    String child = child(parent);
    Task command = open(child, "chiHuy");
    Task committee = open(child, "capUy");
    assertThat(command.getTenantId()).isEqualTo(tenant.toString());
    assertThat(groups(command)).containsExactly(group(tree.banA()));
    // Cấp uỷ: tổ chức Đảng có cấp uỷ gần nhất phía trên (Đảng uỷ).
    assertThat(groups(committee)).containsExactly(group(tree.dangUy()));
    assertThat(command.getName()).isEqualTo("Chỉ huy duyệt: Kế hoạch huấn luyện quý I");
    // Thứ Tư 21/10 + 3 ngày làm việc = thứ Hai 26/10; nhắc 08:00 thứ Sáu 23/10 (EVT-21).
    assertThat(command.getDueDate().toInstant())
        .isEqualTo(StageEngineHarness.at17(LocalDate.of(2026, 10, 26)));
    assertThat(timerDue(child, "nhac_capUy"))
        .isEqualTo(StageEngineHarness.at8(LocalDate.of(2026, 10, 23)));
    // Việc của tiến trình con mang đối tượng và người trình của luồng cha.
    assertThat(tasks.findOpen(tenant, command.getId()).orElseThrow().initiatorId())
        .isEqualTo(submitter);

    CompleteTaskService.Result first =
        complete(command, leader(UUID.randomUUID(), tree.banA()), Decision.DUYET);
    assertThat(first.businessKey().id()).isEqualTo(objectId);
    assertThat(first.processEnded()).isFalse();
    assertThat(ended(parent)).isFalse();
    complete(committee, leader(UUID.randomUUID(), tree.dangUy()), Decision.DUYET);
    assertThat(ended(parent)).isTrue();
    assertThat(endActivity(parent)).isEqualTo("daDuyet");
    assertThat(endActivity(child)).isEqualTo("daDuyet");
  }

  java.time.Instant timerDue(String pid, String elementId) {
    return engine
        .getManagementService()
        .createTimerJobQuery()
        .processInstanceId(pid)
        .list()
        .stream()
        .filter(j -> elementId.equals(j.getElementId()))
        .findFirst()
        .orElseThrow()
        .getDuedate()
        .toInstant();
  }

  @Test
  void capUyTraLaiSauKhiChiHuyDuyetThiCaLuotVeNguoiTrinh() {
    String parent = start();
    String child = child(parent);
    complete(open(child, "chiHuy"), leader(UUID.randomUUID(), tree.banA()), Decision.DUYET);
    complete(open(child, "capUy"), leader(UUID.randomUUID(), tree.dangUy()), Decision.TRA_LAI);
    assertThat(ended(parent)).isTrue();
    assertThat(endActivity(parent)).isEqualTo("traLai");
    assertThat(endActivity(child)).isEqualTo("daTraLai");
  }

  @Test
  void chiHuyTraLaiThiHuyViecCuaCapUy() {
    String parent = start();
    String child = child(parent);
    Task committee = open(child, "capUy");
    complete(open(child, "chiHuy"), leader(UUID.randomUUID(), tree.banA()), Decision.TRA_LAI);
    assertThat(ended(parent)).isTrue();
    assertThat(endActivity(parent)).isEqualTo("traLai");
    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(child).count()).isZero();
    assertThat(engine.getManagementService().createTimerJobQuery().processInstanceId(child).count())
        .isZero();
    // Việc của cấp uỷ đã bị huỷ: hoàn thành tiếp trả 404 hoặc "đã xử lý".
    caller.set(leader(UUID.randomUUID(), tree.dangUy()));
    try {
      assertThatThrownBy(
              () ->
                  completer.complete(
                      new CompleteTaskService.Command(committee.getId(), 1, Decision.DUYET, null)))
          .isInstanceOf(RuntimeException.class);
    } finally {
      caller.remove();
    }
  }

  @Test
  void haiNhanhKetThucCungLucThiBenXungDotThuLaiDuoc() throws Exception {
    String parent = start();
    String child = child(parent);
    Task command = open(child, "chiHuy");
    Task committee = open(child, "capUy");
    CyclicBarrier together = new CyclicBarrier(2);
    AtomicInteger conflicts = new AtomicInteger();
    CompletableFuture<Void> a =
        CompletableFuture.runAsync(
            () -> withRetry(together, conflicts, command, leader(UUID.randomUUID(), tree.banA())));
    CompletableFuture<Void> b =
        CompletableFuture.runAsync(
            () ->
                withRetry(
                    together, conflicts, committee, leader(UUID.randomUUID(), tree.dangUy())));
    CompletableFuture.allOf(a, b).get(60, TimeUnit.SECONDS);
    assertThat(ended(parent)).isTrue();
    assertThat(endActivity(parent)).isEqualTo("daDuyet");
    assertThat(
            engine
                .getHistoryService()
                .createHistoricTaskInstanceQuery()
                .processInstanceId(child)
                .finished()
                .count())
        .isEqualTo(2);
    assertThat(conflicts.get()).isBetween(0, 1);
  }

  private void withRetry(CyclicBarrier together, AtomicInteger conflicts, Task task, Approver who) {
    try {
      together.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        complete(task, who, Decision.DUYET);
        return;
      } catch (VersionConflictException e) {
        // Client thử lại khi nhận 409 (TL-01 "Khoá lạc quan trên bảng ACT_RU").
        conflicts.incrementAndGet();
      } catch (RuntimeException e) {
        if (!(e.getCause()
                instanceof org.flowable.common.engine.api.FlowableOptimisticLockingException)
            && !(e instanceof org.flowable.common.engine.api.FlowableOptimisticLockingException)) {
          throw e;
        }
        conflicts.incrementAndGet();
      }
    }
    throw new AssertionError("Không hoàn thành được sau ba lần thử");
  }

  @Test
  void quaHanChuyenNguoiDuocUyQuyenCuaNhanhDo() {
    UUID deputy = UUID.randomUUID();
    bindings.raw.put(DualApproval.PROCESS_KIND, "{\"nguoi_thay\":{\"CAP_UY\":\"" + deputy + "\"}}");
    String parent = start();
    String child = child(parent);
    fireTimer(child, "quaHan_capUy");
    Task committee = open(child, "capUy");
    assertThat(committee.getAssignee()).isEqualTo(deputy.toString());
    assertThat(open(child, "chiHuy").getAssignee()).isNull();
    assertThat(engine.getRuntimeService().getVariable(child, DualApproval.OVERDUE_COUNTS))
        .isEqualTo("capUy=1");
    // Nhánh chỉ huy không khai người được uỷ quyền: lên cấp trên (Chi bộ).
    fireTimer(child, "quaHan_chiHuy");
    assertThat(groups(open(child, "chiHuy"))).containsExactly(group(tree.chiBo()));
    // Người được uỷ quyền duyệt được nhánh của mình.
    complete(committee, new Approver(tenant, deputy, Set.of(), null), Decision.DUYET);
    assertThat(open(child, "capUy")).isNull();
  }

  @Test
  void motNguoiKhongDuyetCaHaiNhanhVaNguoiTrinhKhongDuyet() {
    bindings.raw.put(
        DualApproval.PROCESS_KIND,
        "{\"cap\":[{\"buoc\":\"CAP_UY\",\"vai_tro\":\"leader\",\"pham_vi\":\"DON_VI_TRUC_TIEP\"}]}");
    String parent = start();
    String child = child(parent);
    Approver same = leader(UUID.randomUUID(), tree.banA());
    assertThatThrownBy(
            () -> complete(open(child, "chiHuy"), leader(submitter, tree.banA()), Decision.DUYET))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              assertThat(((BusinessException) e).errorCode().code())
                  .isEqualTo("WORKFLOW-403-SELF-APPROVAL");
              assertThat(((BusinessException) e).msg()).isEqualTo("MSG-02-11");
            });
    complete(open(child, "chiHuy"), same, Decision.DUYET);
    Task committee = open(child, "capUy");
    assertThat(groups(committee)).containsExactly(group(tree.banA()));
    assertThatThrownBy(() -> complete(committee, same, Decision.DUYET))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).detail()).contains("nhánh còn lại"));
    assertThat(open(child, "capUy")).isNotNull();
    complete(committee, leader(UUID.randomUUID(), tree.banA()), Decision.DUYET);
    assertThat(endActivity(parent)).isEqualTo("daDuyet");
  }

  @Test
  void kySoTuyChonCuaNhanhChoChuKyCuaDungNguoiDuyet() {
    bindings.raw.put(
        DualApproval.PROCESS_KIND, "{\"cap\":[{\"buoc\":\"CHI_HUY\",\"ky_so\":true}]}");
    String parent = start();
    String child = child(parent);
    UUID commander = UUID.randomUUID();
    assertThat(dual.onSigned(tenant, objectId, commander)).isEqualTo(Outcome.NO_PROCESS);
    complete(open(child, "chiHuy"), leader(commander, tree.banA()), Decision.DUYET);
    assertThat(engine.getRuntimeService().getActiveActivityIds(child)).contains("choKy_chiHuy");
    complete(open(child, "capUy"), leader(UUID.randomUUID(), tree.dangUy()), Decision.DUYET);
    assertThat(ended(parent)).isFalse();
    assertThat(dual.onSigned(tenant, objectId, UUID.randomUUID())).isEqualTo(Outcome.IGNORED);
    assertThat(dual.onSigned(tenant, UUID.randomUUID(), commander)).isEqualTo(Outcome.NO_PROCESS);
    assertThat(dual.onSigned(tenant, objectId, commander)).isEqualTo(Outcome.SIGNALLED);
    assertThat(ended(parent)).isTrue();
    assertThat(endActivity(parent)).isEqualTo("daDuyet");
  }

  @Test
  void kyLoCuaHeThongKhiChiMotNhanhCho() {
    bindings.raw.put(DualApproval.PROCESS_KIND, "{\"cap\":[{\"buoc\":\"CAP_UY\",\"ky_so\":true}]}");
    String parent = start();
    String child = child(parent);
    complete(open(child, "capUy"), leader(UUID.randomUUID(), tree.dangUy()), Decision.DUYET);
    assertThat(dual.onSigned(tenant, objectId, null)).isEqualTo(Outcome.SIGNALLED);
    complete(open(child, "chiHuy"), leader(UUID.randomUUID(), tree.banA()), Decision.DUYET);
    assertThat(endActivity(parent)).isEqualTo("daDuyet");
  }

  @Test
  void nhacTruocHanKhongDongNhanh() {
    String parent = start();
    String child = child(parent);
    fireTimer(child, "nhac_chiHuy");
    assertThat(engine.getRuntimeService().getActiveActivityIds(child))
        .contains("chiHuy", "nhacViec_chiHuy");
    for (CallbackJob job : jobs.acquire(Set.of("notify-remind"), 10, Duration.ofMinutes(1), "w")) {
      jobs.complete(job, "w");
    }
    for (Job job : engine.getManagementService().createJobQuery().processInstanceId(child).list()) {
      engine.getManagementService().executeJob(job.getId());
    }
    assertThat(engine.getRuntimeService().getActiveActivityIds(child))
        .doesNotContain("nhacViec_chiHuy")
        .contains("chiHuy", "capUy");
    assertThat(ended(parent)).isFalse();
  }

  /** OPA cho phép mọi thao tác: ở đây chỉ kiểm phần tách vai trò của lượt duyệt kép. */
  static final class AllowAll implements ApprovalAccessPolicy {
    @Override
    public void checkView(AuthzResource resource) {}

    @Override
    public void checkApprove(AuthzResource resource) {}

    @Override
    public void checkBatchApprove(AuthzResource resource) {}

    @Override
    public void checkReturn(AuthzResource resource) {}
  }
}
