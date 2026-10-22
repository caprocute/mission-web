package vn.viettel.okrkpi.workflow.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flowable.dmn.engine.configurator.DmnEngineConfigurator;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.task.api.Task;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalLevelResolver;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalLevelsAssembler;
import vn.viettel.okrkpi.workflow.application.approval.ApproverTransferService;
import vn.viettel.okrkpi.workflow.application.approval.CoreCallbackService;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalService;
import vn.viettel.okrkpi.workflow.application.port.BindingReader;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.application.port.OrgDirectory;
import vn.viettel.okrkpi.workflow.application.port.WorkingCalendar;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.OpenTask;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.ApprovalLevelsListener;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableApprovalProcesses;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableExternalJobQueue;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableTaskEngine;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableTaskReassignment;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateCatalog;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateDeployer;

/**
 * Engine Flowable 7 thật (BPMN + DMN, H2 trong bộ nhớ, lịch sử mức audit) với các thành phần thật
 * của DV-06-03: triển khai mẫu qua {@link TemplateDeployer} (gồm linter), listener phân giải cấp
 * duyệt, khởi tạo qua {@link StartApprovalService}, hoàn thành qua {@link FlowableTaskEngine},
 * chuyển người duyệt và external worker gọi lại lõi. Chỉ cây tổ chức, lịch nghỉ, tham số tenant và
 * lõi là bản giả trong bộ nhớ.
 */
final class ApprovalEngineHarness implements AutoCloseable {

  /** Ngày giả lập: thứ Tư 21/10/2026, 09:00 giờ Việt Nam. */
  static final Instant NOW = Instant.parse("2026-10-21T02:00:00Z");

  final ProcessEngine engine;
  final Org org = new Org();
  final Calendar calendar = new Calendar();
  final Bindings bindings = new Bindings();
  final Core core = new Core();
  final StartApprovalService starter;
  final FlowableTaskEngine taskEngine;
  final ApproverTransferService transfers;
  final CoreCallbackService callbacks;
  final FlowableExternalJobQueue jobs;

  ApprovalEngineHarness() {
    ObjectMapper json = new ObjectMapper();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ApprovalLevelsAssembler assembler =
        new ApprovalLevelsAssembler(new ApprovalLevelResolver(org, calendar, clock));
    StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
    cfg.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    cfg.setAsyncExecutorActivate(false);
    cfg.setHistory("audit");
    cfg.setDisableIdmEngine(true);
    cfg.setDisableEventRegistry(true);
    cfg.addConfigurator(new DmnEngineConfigurator());
    Map<Object, Object> beans = new HashMap<>();
    beans.put("phanGiaiCapDuyet", new ApprovalLevelsListener(assembler, json));
    cfg.setBeans(beans);
    engine = cfg.buildProcessEngine();
    new TemplateDeployer(engine.getRepositoryService(), engine.getRuntimeService(), json)
        .deploy(TemplateCatalog.load(json));
    FlowableApprovalProcesses processes =
        new FlowableApprovalProcesses(engine.getRuntimeService(), engine.getHistoryService(), null) {
          @Override
          public void lockStart(UUID tenantId, String templateCode, BusinessKey businessKey) {
            // Khoá tư vấn chỉ có trên PostgreSQL; kiểm ở ApprovalTemplatesIT.
          }
        };
    starter = new StartApprovalService(processes, bindings);
    taskEngine =
        new FlowableTaskEngine(
            engine.getTaskService(),
            engine.getRuntimeService(),
            engine.getHistoryService(),
            clock,
            new SimpleMeterRegistry());
    transfers =
        new ApproverTransferService(
            new FlowableTaskReassignment(engine.getTaskService(), engine.getRuntimeService()));
    jobs =
        new FlowableExternalJobQueue(
            engine.getManagementService(), engine.getRuntimeService(), engine.getHistoryService());
    callbacks = new CoreCallbackService(jobs, core);
  }

  /** User task đang mở duy nhất của tiến trình. */
  Task openTask(String processInstanceId) {
    return engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(processInstanceId)
        .singleResult();
  }

  /** Nhóm ứng viên của task. */
  List<String> candidateGroups(Task task) {
    List<String> groups = new ArrayList<>();
    engine.getTaskService().getIdentityLinksForTask(task.getId()).stream()
        .filter(l -> "candidate".equals(l.getType()))
        .forEach(l -> groups.add(l.getGroupId()));
    return groups;
  }

  /** Hoàn thành task qua bộ chuyển engine thật của DV-06-02 (nhận việc, biến quyết định, ý kiến). */
  void complete(UUID tenant, Task task, UUID actor, Decision decision, String comment) {
    OpenTask open = taskEngine.findOpen(tenant, task.getId()).orElseThrow();
    taskEngine.complete(open, new Approver(tenant, actor, Set.of(), null), decision, comment);
  }

  /**
   * Chạy các job bất đồng bộ đến hạn của tiến trình (vai trò async executor ở Deployment worker):
   * hoàn thành external worker được engine áp tiếp bằng job bất đồng bộ.
   */
  void runAsyncJobs(String processInstanceId) {
    for (org.flowable.job.api.Job job :
        engine.getManagementService().createJobQuery().processInstanceId(processInstanceId).list()) {
      engine.getManagementService().executeJob(job.getId());
    }
  }

  boolean ended(String processInstanceId) {
    return engine
            .getRuntimeService()
            .createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .count()
        == 0;
  }

  /** Bước kết thúc của tiến trình đã kết thúc. */
  String endActivity(String processInstanceId) {
    return engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceId(processInstanceId)
        .singleResult()
        .getEndActivityId();
  }

  @Override
  public void close() {
    engine.close();
  }

  /**
   * Cây tổ chức mẫu của một tenant: Đảng uỷ cơ sở (TO_CHUC_DANG) → Chi bộ (CHI_BO) → Ban A; cây
   * chuyên môn: Cơ quan (CO_QUAN) → Ban A.
   */
  static final class Org implements OrgDirectory {
    final Map<UUID, Map<UUID, OrgUnit>> units = new HashMap<>();

    Tree tree(UUID tenant) {
      Tree t =
          new Tree(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
      Map<UUID, OrgUnit> m = units.computeIfAbsent(tenant, k -> new HashMap<>());
      m.put(t.dangUy(), new OrgUnit(t.dangUy(), "TO_CHUC_DANG", null, null));
      m.put(t.chiBo(), new OrgUnit(t.chiBo(), "CHI_BO", t.dangUy(), null));
      m.put(t.coQuan(), new OrgUnit(t.coQuan(), "CO_QUAN", null, null));
      m.put(t.banA(), new OrgUnit(t.banA(), "PHONG_BAN", t.chiBo(), t.coQuan()));
      return t;
    }

    void put(UUID tenant, OrgUnit unit) {
      units.computeIfAbsent(tenant, k -> new HashMap<>()).put(unit.id(), unit);
    }

    @Override
    public Optional<OrgUnit> unit(UUID tenantId, UUID unitId, LocalDate on) {
      return Optional.ofNullable(units.getOrDefault(tenantId, Map.of()).get(unitId));
    }
  }

  record Tree(UUID tenant, UUID dangUy, UUID chiBo, UUID coQuan, UUID banA) {}

  /** Lịch nghỉ giả. */
  static final class Calendar implements WorkingCalendar {
    final Map<LocalDate, Boolean> days = new HashMap<>();

    @Override
    public Map<LocalDate, Boolean> overrides(UUID tenantId, LocalDate from, LocalDate to) {
      return Map.copyOf(days);
    }
  }

  /** Tham số tenant giả. */
  static final class Bindings implements BindingReader {
    final Map<UUID, ApprovalParams> params = new HashMap<>();

    @Override
    public Optional<ApprovalParams> find(UUID tenantId, String processKind, ApprovalFlow flow) {
      return Optional.ofNullable(params.get(tenantId));
    }
  }

  /** Lõi giả: ghi lại lời gọi, trả kết quả cấu hình được. */
  static final class Core implements CoreApprovalGateway {
    final List<Request> calls = new ArrayList<>();
    Outcome next = Outcome.APPLIED;

    @Override
    public Outcome apply(Request request) {
      calls.add(request);
      return next;
    }
  }
}
