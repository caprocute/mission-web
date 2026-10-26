package vn.viettel.okrkpi.workflow.infrastructure.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flowable.dmn.engine.configurator.DmnEngineConfigurator;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.repository.Deployment;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalLevelResolver;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalLevelsAssembler;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.approval.CoreCallbackService;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalCommand;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalService;
import vn.viettel.okrkpi.workflow.application.port.BindingSimulator;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.application.port.OrgDirectory;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestEngine;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.OpenTask;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;
import vn.viettel.okrkpi.workflow.domain.binding.ParamsLinter;
import vn.viettel.okrkpi.workflow.domain.binding.SampleRun;
import vn.viettel.okrkpi.workflow.domain.binding.SimulationSample;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateInfo;
import vn.viettel.okrkpi.workflow.domain.template.BindingUsage;
import vn.viettel.okrkpi.workflow.domain.template.TestCaseResult;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.ApprovalLevelsListener;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableApprovalProcesses;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableExternalJobQueue;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableTaskEngine;
import vn.viettel.okrkpi.workflow.infrastructure.persistence.JdbcBindingReader;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateCatalog;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateDeployer;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateSource;

/**
 * Engine Flowable thử tách biệt (TL-02b SCR-1504 "Mô phỏng luồng", DV-06-08): engine BPMN và DMN
 * trên H2 trong bộ nhớ, không chạm CSDL của cell, triển khai cùng danh mục mẫu qua {@link
 * TemplateDeployer} (gồm linter tám quy tắc) và dùng đúng các thành phần khởi động, phân giải cấp,
 * hoàn thành việc, gọi lại lõi của engine thật. Chỉ cây tổ chức (một cây mẫu cho mỗi lần chạy),
 * lịch nghỉ (không có ngày nghỉ) và lõi (luôn áp dụng được) là bản giả. Engine dựng sẵn khi dịch vụ
 * sẵn sàng (xem {@link #warmUp()}) và chạy tuần tự; mỗi lần chạy dùng một tenant ngẫu nhiên rồi xoá lịch sử.
 */
@Component
public class SandboxBindingSimulator
    implements BindingSimulator, TemplateTestEngine, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SandboxBindingSimulator.class);
  private static final String WORKER = "mo-phong";

  /** Mỗi lần chạy tối đa ngần này việc, chặn vòng lặp nếu mẫu sai. */
  private static final int MAX_TASKS = 20;

  private final ObjectMapper json;
  private final TemplateCatalog catalog;
  private final Org org = new Org();
  private final Map<UUID, ApprovalParams> params = new HashMap<>();
  private final SqlStatementCounter sql = new SqlStatementCounter();
  private Sandbox sandbox;

  public SandboxBindingSimulator(ObjectMapper json, TemplateCatalog catalog) {
    this.json = json;
    this.catalog = catalog;
  }

  @Override
  public synchronized SampleRun run(
      TemplateInfo template,
      Map<String, Object> rawParams,
      SimulationSample sample,
      List<Integer> skippedOrders) {
    ApprovalTemplate approval =
        ApprovalTemplate.byCode(template.key())
            .orElseThrow(() -> new IllegalArgumentException("Mẫu không chạy được trên engine thử"));
    ApprovalFlow flow = ApprovalFlow.valueOf(sample.flow());
    List<String> skipped = new ArrayList<>();
    Map<String, Object> effective = withoutSkipped(rawParams, skippedOrders, skipped);
    int expectedTasks = levelCount(effective);
    Sandbox s = sandbox();
    UUID tenant = UUID.randomUUID();
    String pid = null;
    try {
      params.put(tenant, JdbcBindingReader.parse(json, json.writeValueAsString(effective), flow));
      UUID unit = org.tree(tenant);
      pid = s.starter.start(command(tenant, approval, flow, unit, sample)).processInstanceId();
      int tasks = 0;
      Task open = openTask(s, pid);
      while (open != null && tasks < MAX_TASKS) {
        tasks++;
        boolean last = tasks == expectedTasks;
        Decision decision = sample.returnAtLast() && last ? Decision.TRA_LAI : Decision.DUYET;
        OpenTask task = s.taskEngine.findOpen(tenant, open.getId()).orElseThrow();
        s.taskEngine.complete(
            task, new Approver(tenant, UUID.randomUUID(), Set.of(), null), decision, null);
        open = openTask(s, pid);
      }
      s.callbacks.runOnce(WORKER);
      for (Job job :
          s.engine.getManagementService().createJobQuery().processInstanceId(pid).list()) {
        s.engine.getManagementService().executeJob(job.getId());
      }
      String end = endState(s, pid);
      boolean passed = tasks == expectedTasks && sample.expectedEnd().equals(end);
      return new SampleRun(sample, skipped, end, passed);
    } catch (ApprovalSetupException | IllegalArgumentException | JsonProcessingException e) {
      log.debug("Hồ sơ mẫu {} không chạy được với tham số đã chọn", sample, e);
      return new SampleRun(sample, skipped, "KHONG_KHOI_DONG_DUOC", false);
    } finally {
      params.remove(tenant);
      org.drop(tenant);
      cleanUp(s, pid);
    }
  }

  // --- Bộ test của mẫu soạn trên SCR-1505 (DV-06-09) ---------------------------------------------

  /** Kịch bản của mẫu không phải mẫu duyệt: chạy từ đầu đến cuối, hoàn thành lần lượt mọi việc. */
  static final String GENERIC_SCENARIO = "CHAY_HET_LUONG";

  @Override
  public int scenarioCount() {
    return SimulationSample.values().length;
  }

  /**
   * Nạp mẫu soạn vào engine thử như phiên bản mới nhất của mã mẫu, chạy mọi kịch bản với mọi bộ
   * tham số và đếm câu SQL của mỗi lần hoàn thành user task, rồi gỡ lần triển khai để engine thử
   * trở về danh mục mẫu đóng gói.
   */
  @Override
  public synchronized List<TestCaseResult> run(
      String templateCode, String bpmnXml, List<BindingUsage> bindings) {
    Sandbox s = sandbox();
    Deployment deployment =
        s.engine
            .getRepositoryService()
            .createDeployment()
            .name("mau-soan-" + templateCode)
            .addString(templateCode + ".bpmn20.xml", bpmnXml)
            .deploy();
    try {
      Optional<ApprovalTemplate> approval = ApprovalTemplate.byCode(templateCode);
      List<TestCaseResult> results = new ArrayList<>();
      List<ParamSet> sets = paramSets(templateCode, bindings);
      for (ParamSet set : sets) {
        if (approval.isPresent()) {
          for (SimulationSample sample : SimulationSample.values()) {
            if (set.flow() == null || set.flow().equals(sample.flow())) {
              results.add(approvalCase(s, approval.get(), set, sample));
            }
          }
        } else {
          results.add(genericCase(s, templateCode, set));
        }
      }
      return results;
    } finally {
      s.engine.getRepositoryService().deleteDeployment(deployment.getId(), true);
    }
  }

  private TestCaseResult approvalCase(
      Sandbox s, ApprovalTemplate approval, ParamSet set, SimulationSample sample) {
    ApprovalFlow flow = ApprovalFlow.valueOf(sample.flow());
    int expectedTasks = levelCount(set.params());
    UUID tenant = UUID.randomUUID();
    String pid = null;
    int tasks = 0;
    int maxSql = 0;
    try {
      params.put(
          tenant, JdbcBindingReader.parse(json, json.writeValueAsString(set.params()), flow));
      UUID unit = org.tree(tenant);
      pid = s.starter.start(command(tenant, approval, flow, unit, sample)).processInstanceId();
      Task open = openTask(s, pid);
      while (open != null && tasks < MAX_TASKS) {
        tasks++;
        boolean last = tasks == expectedTasks;
        Decision decision = sample.returnAtLast() && last ? Decision.TRA_LAI : Decision.DUYET;
        OpenTask task = s.taskEngine.findOpen(tenant, open.getId()).orElseThrow();
        SqlStatementCounter.Count statements =
            sql.count(
                () ->
                    s.taskEngine.complete(
                        task,
                        new Approver(tenant, UUID.randomUUID(), Set.of(), null),
                        decision,
                        null));
        maxSql = Math.max(maxSql, statements.writes());
        open = openTask(s, pid);
      }
      s.callbacks.runOnce(WORKER);
      runJobs(s, pid);
      String end = endState(s, pid);
      boolean passed = tasks == expectedTasks && sample.expectedEnd().equals(end);
      return new TestCaseResult(sample.name(), set.label(), end, passed, tasks, maxSql);
    } catch (ApprovalSetupException | IllegalArgumentException | JsonProcessingException e) {
      log.debug("Mẫu soạn không chạy được ca {} với {}", sample, set.label(), e);
      return new TestCaseResult(
          sample.name(), set.label(), "KHONG_KHOI_DONG_DUOC", false, tasks, maxSql);
    } finally {
      params.remove(tenant);
      org.drop(tenant);
      cleanUp(s, pid);
    }
  }

  private TestCaseResult genericCase(Sandbox s, String templateCode, ParamSet set) {
    String pid = null;
    int tasks = 0;
    int maxSql = 0;
    try {
      Map<String, Object> variables = new HashMap<>(set.params());
      variables.put("tenantId", UUID.randomUUID().toString());
      pid =
          s.engine
              .getRuntimeService()
              .startProcessInstanceByKey(templateCode, "MAU-THU:" + UUID.randomUUID(), variables)
              .getId();
      Task open = openTask(s, pid);
      while (open != null && tasks < MAX_TASKS) {
        tasks++;
        String taskId = open.getId();
        maxSql =
            Math.max(maxSql, sql.count(() -> s.engine.getTaskService().complete(taskId)).writes());
        open = openTask(s, pid);
      }
      runJobs(s, pid);
      String end = endState(s, pid);
      boolean passed = open == null && !"CHUA_KET_THUC".equals(end);
      return new TestCaseResult(GENERIC_SCENARIO, set.label(), end, passed, tasks, maxSql);
    } catch (RuntimeException e) {
      log.debug("Mẫu soạn {} không chạy được với {}", templateCode, set.label(), e);
      return new TestCaseResult(
          GENERIC_SCENARIO, set.label(), "KHONG_KHOI_DONG_DUOC", false, tasks, maxSql);
    } finally {
      cleanUp(s, pid);
    }
  }

  private static void runJobs(Sandbox s, String pid) {
    for (Job job : s.engine.getManagementService().createJobQuery().processInstanceId(pid).list()) {
      s.engine.getManagementService().executeJob(job.getId());
    }
  }

  /** Tham số của mọi wf_binding; không có binding thì dùng cấp mặc định của khai báo mẫu. */
  private List<ParamSet> paramSets(String templateCode, List<BindingUsage> bindings) {
    if (!bindings.isEmpty()) {
      return bindings.stream()
          .map(b -> new ParamSet(b.label(), b.flow(), new HashMap<>(b.params())))
          .toList();
    }
    Map<String, Object> defaults = new HashMap<>();
    catalog.templates().stream()
        .map(TemplateSource::descriptor)
        .filter(d -> d != null && templateCode.equals(d.templateCode()))
        .findFirst()
        .map(d -> d.params().get("capMacDinh"))
        .ifPresent(levels -> defaults.put("cap", levels));
    if (!defaults.containsKey("cap") && ApprovalTemplate.byCode(templateCode).isPresent()) {
      defaults.put(
          "cap",
          List.of(
              Map.of("vai_tro", "manager", "pham_vi", "DON_VI_TRUC_TIEP"),
              Map.of("vai_tro", "leader", "pham_vi", "CAP_TREN_TRUC_TIEP")));
    }
    return List.of(new ParamSet("Tham số mặc định", null, defaults));
  }

  private record ParamSet(String label, String flow, Map<String, Object> params) {}

  @Override
  public synchronized void close() {
    if (sandbox != null) {
      sandbox.engine.close();
      sandbox = null;
    }
  }

  /**
   * Dựng engine thử ngay khi dịch vụ sẵn sàng thay vì ở lần mô phỏng đầu: dựng mất khoảng 7–10 giây,
   * vượt hạn đọc 3 giây của REST nội bộ nên lần mô phỏng đầu sau mỗi lần khởi động từng trả 503 ở
   * mission-core (HT-37). Lỗi dựng chỉ ghi nhật ký; lần mô phỏng sau sẽ thử dựng lại.
   */
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void warmUp() {
    try {
      sandbox();
    } catch (RuntimeException e) {
      log.warn("Chưa dựng sẵn được engine Flowable thử, sẽ dựng ở lần mô phỏng đầu", e);
    }
  }

  private Sandbox sandbox() {
    if (sandbox == null) {
      sandbox = new Sandbox();
    }
    return sandbox;
  }

  private static StartApprovalCommand command(
      UUID tenant, ApprovalTemplate template, ApprovalFlow flow, UUID unit, SimulationSample s) {
    boolean plan = template == ApprovalTemplate.DUYET_KE_HOACH_QUY;
    boolean request = template == ApprovalTemplate.DUYET_DE_NGHI;
    return new StartApprovalCommand(
        tenant,
        template,
        UUID.randomUUID(),
        1,
        flow,
        unit,
        s.headOfUnit(),
        UUID.randomUUID(),
        null,
        s.label(),
        null,
        plan ? 1 : null,
        request ? UUID.randomUUID() : null,
        request ? "KHACH_QUAN" : null);
  }

  /** Bỏ các cấp có điều kiện bỏ qua đúng với ca mẫu, ghi lại mã bước bị bỏ. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> withoutSkipped(
      Map<String, Object> raw, List<Integer> skippedOrders, List<String> skipped) {
    Map<String, Object> copy = new HashMap<>(raw);
    if (!(raw.get("cap") instanceof List<?> levels)) {
      return copy;
    }
    List<Object> kept = new ArrayList<>();
    for (int i = 0; i < levels.size(); i++) {
      Object level = levels.get(i);
      if (skippedOrders.contains(i + 1) && level instanceof Map<?, ?> m) {
        skipped.add(ParamsLinter.stepKey((Map<String, Object>) m, i + 1));
      } else {
        kept.add(level);
      }
    }
    copy.put("cap", kept);
    copy.remove("so_cap");
    return copy;
  }

  private static int levelCount(Map<String, Object> effective) {
    return effective.get("cap") instanceof List<?> l ? l.size() : 0;
  }

  private static Task openTask(Sandbox s, String pid) {
    List<Task> open = s.engine.getTaskService().createTaskQuery().processInstanceId(pid).list();
    return open.isEmpty() ? null : open.get(0);
  }

  private static String endState(Sandbox s, String pid) {
    HistoricProcessInstance h =
        s.engine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceId(pid)
            .singleResult();
    if (h == null || h.getEndTime() == null) {
      return "CHUA_KET_THUC";
    }
    return switch (String.valueOf(h.getEndActivityId())) {
      case "endApproved" -> SimulationSample.DA_DUYET;
      case "endReturned" -> SimulationSample.TRA_LAI;
      default -> String.valueOf(h.getEndActivityId());
    };
  }

  private static void cleanUp(Sandbox s, String pid) {
    if (pid == null) {
      return;
    }
    try {
      if (s.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count()
          > 0) {
        s.engine.getRuntimeService().deleteProcessInstance(pid, "mo-phong");
      }
      s.engine.getHistoryService().deleteHistoricProcessInstance(pid);
    } catch (RuntimeException e) {
      log.debug("Không xoá được tiến trình mô phỏng {}", pid, e);
    }
  }

  /** Engine thử và các thành phần thật chạy trên nó. */
  private final class Sandbox {
    final ProcessEngine engine;
    final StartApprovalService starter;
    final FlowableTaskEngine taskEngine;
    final CoreCallbackService callbacks;

    Sandbox() {
      Clock clock = Clock.systemUTC();
      ApprovalLevelsAssembler assembler =
          new ApprovalLevelsAssembler(
              new ApprovalLevelResolver(org, (tenantId, from, to) -> Map.of(), clock));
      StandaloneInMemProcessEngineConfiguration cfg =
          new StandaloneInMemProcessEngineConfiguration();
      cfg.setJdbcUrl("jdbc:h2:mem:mo-phong-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      cfg.setAsyncExecutorActivate(false);
      cfg.setHistory("audit");
      cfg.setDisableIdmEngine(true);
      cfg.setDisableEventRegistry(true);
      cfg.addConfigurator(new DmnEngineConfigurator());
      Map<Object, Object> beans = new HashMap<>();
      beans.put("phanGiaiCapDuyet", new ApprovalLevelsListener(assembler, json));
      cfg.setBeans(beans);
      cfg.setCustomMybatisInterceptors(List.of(sql));
      engine = cfg.buildProcessEngine();
      new TemplateDeployer(engine.getRepositoryService(), engine.getRuntimeService(), json)
          .deploy(catalog);
      FlowableApprovalProcesses processes =
          new FlowableApprovalProcesses(
              engine.getRuntimeService(),
              engine.getHistoryService(),
              engine.getTaskService(),
              null) {
            @Override
            public void lockStart(UUID tenantId, String templateCode, BusinessKey businessKey) {
              // Engine thử chạy tuần tự, không cần khoá tư vấn của PostgreSQL.
            }
          };
      starter =
          new StartApprovalService(
              processes, (tenantId, kind, flow) -> Optional.ofNullable(params.get(tenantId)));
      taskEngine =
          new FlowableTaskEngine(
              engine.getTaskService(),
              engine.getRuntimeService(),
              engine.getHistoryService(),
              clock,
              new SimpleMeterRegistry());
      callbacks =
          new CoreCallbackService(
              new FlowableExternalJobQueue(
                  engine.getManagementService(),
                  engine.getRuntimeService(),
                  engine.getHistoryService()),
              request -> CoreApprovalGateway.Outcome.APPLIED);
      log.info("Đã dựng engine Flowable thử cho mô phỏng tham số luồng");
    }
  }

  /**
   * Cây tổ chức mẫu của mỗi lần chạy: Đảng uỷ cấp trên → Đảng uỷ cơ sở → Chi bộ → Phòng; cây chuyên
   * môn: Bộ → Cơ quan → Phòng. Đủ cho mọi phạm vi phân giải ở cả hai luồng.
   */
  private static final class Org implements OrgDirectory {
    private final Map<UUID, Map<UUID, OrgUnit>> units = new HashMap<>();

    synchronized UUID tree(UUID tenant) {
      UUID dangUyTren = UUID.randomUUID();
      UUID dangUy = UUID.randomUUID();
      UUID chiBo = UUID.randomUUID();
      UUID bo = UUID.randomUUID();
      UUID coQuan = UUID.randomUUID();
      UUID phong = UUID.randomUUID();
      Map<UUID, OrgUnit> m = new HashMap<>();
      m.put(dangUyTren, new OrgUnit(dangUyTren, OrgUnit.PARTY_ORGANIZATION, null, null));
      m.put(dangUy, new OrgUnit(dangUy, OrgUnit.PARTY_ORGANIZATION, dangUyTren, null));
      m.put(chiBo, new OrgUnit(chiBo, "CHI_BO", dangUy, coQuan));
      m.put(bo, new OrgUnit(bo, "CO_QUAN", null, null));
      m.put(coQuan, new OrgUnit(coQuan, "CO_QUAN", dangUy, bo));
      m.put(phong, new OrgUnit(phong, "PHONG_BAN", chiBo, coQuan));
      units.put(tenant, m);
      return phong;
    }

    synchronized void drop(UUID tenant) {
      units.remove(tenant);
    }

    @Override
    public synchronized Optional<OrgUnit> unit(UUID tenantId, UUID unitId, LocalDate on) {
      return Optional.ofNullable(units.getOrDefault(tenantId, Map.of()).get(unitId));
    }
  }
}
