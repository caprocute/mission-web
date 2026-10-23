package vn.viettel.okrkpi.workflow.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flowable.dmn.engine.configurator.DmnEngineConfigurator;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalLevelResolver;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalLevelsAssembler;
import vn.viettel.okrkpi.workflow.application.binding.CelSkipConditions;
import vn.viettel.okrkpi.workflow.application.port.BindingReader;
import vn.viettel.okrkpi.workflow.application.port.ComplaintHandlerPolicy;
import vn.viettel.okrkpi.workflow.application.port.ExternalJobQueue.CallbackJob;
import vn.viettel.okrkpi.workflow.application.port.RoleHolderDirectory;
import vn.viettel.okrkpi.workflow.application.stage.AssigneeSelectionService;
import vn.viettel.okrkpi.workflow.application.stage.StagePlanner;
import vn.viettel.okrkpi.workflow.application.stage.StageProcessService;
import vn.viettel.okrkpi.workflow.application.stage.StageScheduler;
import vn.viettel.okrkpi.workflow.application.stage.StageStepHooks;
import vn.viettel.okrkpi.workflow.application.stage.StartStageCommand;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.ApprovalLevelsListener;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableApprovalProcesses;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableAssigneeWorkload;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableExternalJobQueue;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableStageProcesses;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.StageStepListener;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateCatalog;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateDeployer;

/**
 * Engine Flowable 7 thật (H2 trong bộ nhớ, lịch sử mức audit) cho bốn mẫu phần 2 với các thành phần
 * thật của DV-06-04: triển khai qua {@link TemplateDeployer} (gồm linter), listener {@code
 * lapLichBuoc}, {@link StageProcessService}, worker chọn người trên hàng việc external worker thật.
 * Chỉ cây tổ chức, lịch nghỉ, tham số tenant, người giữ vai trò và OPA là bản giả trong bộ nhớ; OPA
 * giả áp đúng quy tắc của {@code complaint_handler.rego}.
 */
final class StageEngineHarness implements AutoCloseable {

  static final Instant NOW = ApprovalEngineHarness.NOW;
  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

  final ProcessEngine engine;
  final ApprovalEngineHarness.Org org = new ApprovalEngineHarness.Org();
  final ApprovalEngineHarness.Calendar calendar = new ApprovalEngineHarness.Calendar();
  final Bindings bindings = new Bindings();
  final Holders holders = new Holders();
  final Opa opa = new Opa();
  final StageProcessService service;
  final AssigneeSelectionService selection;
  final FlowableExternalJobQueue jobs;
  final UUID tenant = UUID.randomUUID();
  final ApprovalEngineHarness.Tree tree;

  StageEngineHarness() {
    ObjectMapper json = new ObjectMapper();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    tree = org.tree(tenant);
    StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
    cfg.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    cfg.setAsyncExecutorActivate(false);
    cfg.setHistory("audit");
    cfg.setDisableIdmEngine(true);
    cfg.setDisableEventRegistry(true);
    cfg.addConfigurator(new DmnEngineConfigurator());
    StageScheduler scheduler = new StageScheduler(org, calendar, clock);
    Map<Object, Object> beans = new HashMap<>();
    beans.put(
        "phanGiaiCapDuyet",
        new ApprovalLevelsListener(
            new ApprovalLevelsAssembler(new ApprovalLevelResolver(org, calendar, clock)), json));
    beans.put("lapLichBuoc", new StageStepListener(new StageStepHooks(scheduler)));
    cfg.setBeans(beans);
    engine = cfg.buildProcessEngine();
    new TemplateDeployer(engine.getRepositoryService(), engine.getRuntimeService(), json)
        .deploy(TemplateCatalog.load(json));
    FlowableApprovalProcesses starter =
        new FlowableApprovalProcesses(
            engine.getRuntimeService(), engine.getHistoryService(), null) {
          @Override
          public void lockStart(UUID tenantId, String templateCode, BusinessKey businessKey) {
            // Khoá tư vấn chỉ có trên PostgreSQL.
          }
        };
    FlowableStageProcesses processes =
        new FlowableStageProcesses(
            engine.getRuntimeService(), engine.getTaskService(), engine.getHistoryService());
    service =
        new StageProcessService(
            starter, processes, bindings, new StagePlanner(json), new CelSkipConditions());
    jobs =
        new FlowableExternalJobQueue(
            engine.getManagementService(), engine.getRuntimeService(), engine.getHistoryService());
    selection =
        new AssigneeSelectionService(
            jobs, org, holders, new FlowableAssigneeWorkload(engine.getTaskService()), opa, clock);
  }

  /** Lệnh khởi tạo mặc định của mẫu, đơn vị gốc là Ban A. */
  StartStageCommand command(StageTemplate template, UUID businessId, UUID creator) {
    return new StartStageCommand(
        tenant,
        template,
        businessId,
        1,
        ApprovalFlow.DANG,
        tree.banA(),
        "Việc của " + template.code(),
        creator,
        template == StageTemplate.DUYET_HO_SO_DANH_GIA ? creator : null,
        null,
        false,
        null,
        Map.of(),
        template == StageTemplate.XU_LY_KHIEU_NAI ? NOW.plusSeconds(15L * 86_400) : null);
  }

  /** Tiến trình đang chạy (hoặc đã kết thúc gần nhất) của đối tượng. */
  String pid(StageTemplate template, UUID businessId) {
    return engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processDefinitionKey(template.code())
        .processInstanceBusinessKey(new BusinessKey(template.businessType(), businessId).value())
        .orderByProcessInstanceStartTime()
        .desc()
        .listPage(0, 1)
        .get(0)
        .getId();
  }

  Task task(String pid) {
    return engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult();
  }

  List<String> groups(Task task) {
    List<String> out = new ArrayList<>();
    engine.getTaskService().getIdentityLinksForTask(task.getId()).stream()
        .filter(l -> "candidate".equals(l.getType()))
        .forEach(l -> out.add(l.getGroupId()));
    return out;
  }

  String group(String role, UUID unit) {
    return tenant + ":" + role + ":" + unit;
  }

  /** Phần tử đang chạy, bỏ sự kiện biên nhắc và quá hạn. */
  Set<String> active(String pid) {
    Set<String> out = new TreeSet<>(engine.getRuntimeService().getActiveActivityIds(pid));
    out.removeIf(id -> id.startsWith("nhac_") || id.startsWith("quaHan_"));
    return out;
  }

  Object variable(String pid, String name) {
    return engine.getRuntimeService().getVariable(pid, name);
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

  /** Một lượt worker chọn người rồi áp việc hoàn thành (job bất đồng bộ của engine). */
  void choose(String pid) {
    selection.runOnce("worker-test");
    runAsyncJobs(pid);
  }

  /** Hoàn thành mọi external worker của chủ đề (vai trò worker ngoài như lõi, thông báo). */
  void completeExternal(String pid, String topic) {
    for (CallbackJob job : jobs.acquire(Set.of(topic), 10, java.time.Duration.ofMinutes(1), "w")) {
      jobs.complete(job, "w");
    }
    runAsyncJobs(pid);
  }

  void runAsyncJobs(String pid) {
    for (Job job : engine.getManagementService().createJobQuery().processInstanceId(pid).list()) {
      engine.getManagementService().executeJob(job.getId());
    }
  }

  /** Bắn timer biên {@code elementId} (không chờ tới hạn). */
  void fireTimer(String pid, String elementId) {
    Job timer =
        engine.getManagementService().createTimerJobQuery().processInstanceId(pid).list().stream()
            .filter(j -> elementId.equals(j.getElementId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Không có timer " + elementId));
    Job executable = engine.getManagementService().moveTimerToExecutableJob(timer.getId());
    engine.getManagementService().executeJob(executable.getId());
  }

  /** Hạn của timer biên. */
  Instant timerDue(String pid, String elementId) {
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

  /** 17:00 giờ Việt Nam của ngày. */
  static Instant at17(LocalDate day) {
    return day.atTime(17, 0).atZone(VN).toInstant();
  }

  static Instant at8(LocalDate day) {
    return day.atTime(8, 0).atZone(VN).toInstant();
  }

  /** Thêm một việc đang mở cho người (khối lượng việc). */
  void busy(UUID person) {
    Task t = engine.getTaskService().newTask();
    t.setTenantId(tenant.toString());
    t.setAssignee(person.toString());
    t.setName("Việc khác");
    engine.getTaskService().saveTask(t);
  }

  @Override
  public void close() {
    engine.close();
  }

  /** Tham số tenant giả: chỉ có dạng thô cho mẫu phần 2. */
  static final class Bindings implements BindingReader {
    final Map<String, String> raw = new HashMap<>();

    @Override
    public Optional<ApprovalParams> find(UUID tenantId, String processKind, ApprovalFlow flow) {
      return Optional.empty();
    }

    @Override
    public Optional<String> rawParams(UUID tenantId, String processKind, ApprovalFlow flow) {
      return Optional.ofNullable(raw.get(processKind));
    }
  }

  /** Người giữ vai trò giả theo (vai trò, đơn vị). */
  static final class Holders implements RoleHolderDirectory {
    final Map<String, Map<UUID, List<UUID>>> byRole = new HashMap<>();

    void put(String role, UUID unit, UUID... people) {
      byRole.computeIfAbsent(role, r -> new HashMap<>()).put(unit, List.of(people));
    }

    @Override
    public Map<UUID, List<UUID>> holders(
        UUID tenantId, String role, List<UUID> units, LocalDate on) {
      Map<UUID, List<UUID>> out = new HashMap<>();
      Map<UUID, List<UUID>> all = byRole.getOrDefault(role, Map.of());
      for (UUID u : units) {
        if (all.containsKey(u)) {
          out.put(u, all.get(u));
        }
      }
      return out;
    }
  }

  /** OPA giả: cùng quy tắc với {@code complaint_handler.rego}, ghi lại đầu vào. */
  static final class Opa implements ComplaintHandlerPolicy {
    final List<Map<String, List<UUID>>> participantsSeen = new ArrayList<>();

    @Override
    public Optional<Decision> decide(
        List<UUID> units, Map<UUID, List<UUID>> appraisers, Map<String, List<UUID>> participants) {
      participantsSeen.add(participants);
      Set<UUID> excluded = new TreeSet<>();
      participants.values().forEach(excluded::addAll);
      for (int i = 0; i < units.size(); i++) {
        Set<UUID> eligible = new TreeSet<>(appraisers.getOrDefault(units.get(i), List.of()));
        eligible.removeAll(excluded);
        if (!eligible.isEmpty()) {
          List<UUID> sorted = new ArrayList<>(eligible);
          sorted.sort(java.util.Comparator.comparing(UUID::toString));
          return Optional.of(new Decision(units.get(i), sorted, i > 0));
        }
      }
      return Optional.empty();
    }
  }
}
