package vn.viettel.okrkpi.workflow.application.template;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.workflow.application.port.CellEngineGateway;
import vn.viettel.okrkpi.workflow.application.port.RolloutCalendar;
import vn.viettel.okrkpi.workflow.application.port.TemplateAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.TemplateBindingUsage;
import vn.viettel.okrkpi.workflow.application.port.TemplateBpmnLinter;
import vn.viettel.okrkpi.workflow.application.port.TemplateCodeHost;
import vn.viettel.okrkpi.workflow.application.port.TemplateEventPublisher;
import vn.viettel.okrkpi.workflow.application.port.TemplateReleaseStore;
import vn.viettel.okrkpi.workflow.application.port.TemplateRolloutStore;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestDispatcher;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestEngine;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestRunStore;
import vn.viettel.okrkpi.workflow.application.port.WorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.application.saga.SagaEngine;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaRepository;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;
import vn.viettel.okrkpi.workflow.domain.template.BindingUsage;
import vn.viettel.okrkpi.workflow.domain.template.CellRollout;
import vn.viettel.okrkpi.workflow.domain.template.Release;
import vn.viettel.okrkpi.workflow.domain.template.ReleaseStatus;
import vn.viettel.okrkpi.workflow.domain.template.RolloutStatus;
import vn.viettel.okrkpi.workflow.domain.template.RolloutWindow;
import vn.viettel.okrkpi.workflow.domain.template.TemplateLintFinding;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;
import vn.viettel.okrkpi.workflow.domain.template.TestCaseResult;
import vn.viettel.okrkpi.workflow.domain.template.TestReport;
import vn.viettel.okrkpi.workflow.domain.template.TestRun;
import vn.viettel.okrkpi.workflow.domain.template.TestRunStatus;

/** Bản giả trong bộ nhớ của các cổng mẫu luồng (DV-06-09). */
final class TemplateFakes {

  static final Instant NOW = Instant.parse("2026-10-20T15:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private TemplateFakes() {}

  static final class Store implements WorkflowTemplateStore {
    final Map<UUID, TemplateVersion> rows = new LinkedHashMap<>();
    final Map<UUID, Integer> tests = new HashMap<>();
    final Map<UUID, String> urls = new HashMap<>();
    boolean duplicateOnInsert;

    @Override
    public Optional<TemplateVersion> find(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public int maxVersion(String code) {
      return rows.values().stream()
          .filter(t -> t.templateCode().equals(code))
          .mapToInt(TemplateVersion::versionNo)
          .max()
          .orElse(0);
    }

    @Override
    public boolean hasOpenDraft(String code) {
      return rows.values().stream()
          .anyMatch(t -> t.templateCode().equals(code) && !t.status().released());
    }

    @Override
    public Optional<TemplateVersion> findReleased(String code, int versionNo) {
      return rows.values().stream()
          .filter(
              t ->
                  t.templateCode().equals(code)
                      && t.versionNo() == versionNo
                      && t.status().released())
          .findFirst();
    }

    @Override
    public List<TemplateVersion> page(TemplateStatus status, String q, String afterKey, int limit) {
      return rows.values().stream()
          .filter(t -> status == null || t.status() == status)
          .filter(t -> q == null || t.templateCode().contains(q))
          .filter(
              t -> afterKey == null || WorkflowTemplateStore.cursorKey(t).compareTo(afterKey) > 0)
          .sorted(
              (a, b) ->
                  WorkflowTemplateStore.cursorKey(a).compareTo(WorkflowTemplateStore.cursorKey(b)))
          .limit(limit)
          .toList();
    }

    @Override
    public Map<UUID, Integer> testCounts(Collection<UUID> ids) {
      return tests;
    }

    @Override
    public Map<UUID, String> mergeRequestUrls(Collection<UUID> ids) {
      return urls;
    }

    @Override
    public TemplateVersion insert(TemplateVersion v) {
      if (duplicateOnInsert) {
        throw new org.springframework.dao.DuplicateKeyException("ux_wf_template__dang_soan");
      }
      rows.put(v.id(), v);
      return v;
    }

    @Override
    public Optional<TemplateVersion> update(TemplateVersion v, long expected) {
      TemplateVersion current = rows.get(v.id());
      if (current == null || current.rowVersion() != expected) {
        return Optional.empty();
      }
      TemplateVersion saved = v.withRowVersion(expected + 1, NOW);
      rows.put(v.id(), saved);
      return Optional.of(saved);
    }
  }

  static final class Linter implements TemplateBpmnLinter {
    List<TemplateLintFinding> findings = List.of();

    @Override
    public List<TemplateLintFinding> lint(String code, String xml) {
      return findings;
    }
  }

  static final class Usage implements TemplateBindingUsage {
    List<BindingUsage> bindings = List.of();

    @Override
    public List<BindingUsage> bindings(String code) {
      return bindings;
    }

    @Override
    public Map<UUID, Integer> tenantCounts(Collection<UUID> ids) {
      Map<UUID, Integer> out = new HashMap<>();
      ids.forEach(id -> out.put(id, bindings.size()));
      return out;
    }
  }

  static final class Runs implements TemplateTestRunStore {
    final Map<UUID, TestRun> rows = new LinkedHashMap<>();

    @Override
    public void insert(TestRun run) {
      rows.put(run.id(), run);
    }

    @Override
    public Optional<TestRun> find(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<TestRun> active(UUID templateId, byte[] sha) {
      return rows.values().stream()
          .filter(r -> r.templateId().equals(templateId) && !r.status().terminal())
          .filter(r -> java.util.Arrays.equals(r.bpmnSha256(), sha))
          .findFirst();
    }

    @Override
    public Optional<TestRun> latestFinished(UUID templateId) {
      return rows.values().stream()
          .filter(r -> r.templateId().equals(templateId) && r.status().terminal())
          .reduce((a, b) -> b);
    }

    @Override
    public void markRunning(UUID id) {
      TestRun r = rows.get(id);
      rows.put(id, with(r, TestRunStatus.DANG_CHAY, null));
    }

    @Override
    public void finish(UUID id, TestRunStatus status, TestReport report) {
      rows.put(id, with(rows.get(id), status, report));
    }

    static TestRun with(TestRun r, TestRunStatus s, TestReport report) {
      return new TestRun(
          r.id(), r.tenantId(), r.templateId(), r.bpmnSha256(), s, report, r.requestedBy(), NOW);
    }
  }

  static final class Releases implements TemplateReleaseStore {
    final Map<UUID, Release> rows = new LinkedHashMap<>();

    @Override
    public void insert(Release r, UUID actor) {
      rows.putIfAbsent(r.templateId(), r);
    }

    @Override
    public Optional<Release> find(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public void approved(UUID id, UUID approver, UUID sagaId) {
      Release r = rows.get(id);
      ReleaseStatus s = r.status() == ReleaseStatus.MO ? ReleaseStatus.DA_DUYET : r.status();
      rows.put(
          id,
          new Release(
              id,
              r.mergeRequestRef(),
              r.mergeRequestUrl(),
              s,
              r.submittedBy(),
              approver,
              sagaId,
              r.signature(),
              r.pipelineRef()));
    }

    @Override
    public boolean signed(UUID id, byte[] signature, String pipelineRef) {
      Release r = rows.get(id);
      if (r.signed()) {
        return false;
      }
      rows.put(
          id,
          new Release(
              id,
              r.mergeRequestRef(),
              r.mergeRequestUrl(),
              ReleaseStatus.DA_KY,
              r.submittedBy(),
              r.approvedBy(),
              r.sagaId(),
              signature,
              pipelineRef));
      return true;
    }

    @Override
    public void applied(UUID id) {
      Release r = rows.get(id);
      rows.put(
          id,
          new Release(
              id,
              r.mergeRequestRef(),
              r.mergeRequestUrl(),
              ReleaseStatus.DA_AP_DUNG,
              r.submittedBy(),
              r.approvedBy(),
              r.sagaId(),
              r.signature(),
              r.pipelineRef()));
    }

    @Override
    public List<Release> signedNotApplied(int limit) {
      return rows.values().stream().filter(r -> r.status() == ReleaseStatus.DA_KY).toList();
    }
  }

  static final class Rollouts implements TemplateRolloutStore {
    final Map<String, CellRollout> rows = new LinkedHashMap<>();
    final Map<String, String> errors = new HashMap<>();

    @Override
    public void plan(UUID templateId, List<String> cells, UUID sagaId, UUID actor) {
      for (int i = 0; i < cells.size(); i++) {
        rows.putIfAbsent(
            templateId + cells.get(i),
            new CellRollout(templateId, cells.get(i), i + 1, RolloutStatus.CHO, null, null, null));
      }
    }

    @Override
    public List<CellRollout> list(UUID templateId) {
      return rows.values().stream().filter(r -> r.templateId().equals(templateId)).toList();
    }

    @Override
    public Optional<CellRollout> find(UUID templateId, String cell) {
      return Optional.ofNullable(rows.get(templateId + cell));
    }

    @Override
    public void deployed(UUID templateId, String cell, String dep, Integer prev, Instant at) {
      CellRollout r = rows.get(templateId + cell);
      rows.put(
          templateId + cell,
          new CellRollout(templateId, cell, r.seqNo(), RolloutStatus.DA_TRIEN_KHAI, dep, at, prev));
    }

    @Override
    public void mark(UUID templateId, String cell, RolloutStatus status, String error) {
      CellRollout r = rows.get(templateId + cell);
      rows.put(
          templateId + cell,
          new CellRollout(
              templateId,
              cell,
              r.seqNo(),
              status,
              r.deploymentId(),
              r.deployedAt(),
              r.previousVersionNo()));
      errors.put(cell, error);
    }
  }

  static final class CodeHost implements TemplateCodeHost {
    final List<String> calls = new ArrayList<>();

    @Override
    public MergeRequest open(ReleasePackage pkg, UUID actor) {
      calls.add("open:" + pkg.branch() + ":" + pkg.files().size());
      return new MergeRequest("mr-" + pkg.templateId(), "https://ma-nguon.example.invalid/mr/1");
    }

    @Override
    public void approve(UUID templateId, String ref, UUID approver) {
      calls.add("approve:" + ref);
    }
  }

  static final class Access implements TemplateAccessPolicy {
    final List<String> calls = new ArrayList<>();
    final Set<String> denied = new HashSet<>();
    AuthzResource lastApprove;

    private void check(String what) {
      calls.add(what);
      if (denied.contains(what)) {
        throw new BusinessException("COMMON-403-OUT-OF-SCOPE");
      }
    }

    @Override
    public void checkLibrary(AuthzResource r) {
      check("thu-vien");
    }

    @Override
    public void checkAuthor(AuthzResource r) {
      check("soan");
    }

    @Override
    public void checkTest(AuthzResource r) {
      check("chay-test");
    }

    @Override
    public void checkSubmit(AuthzResource r) {
      check("de-nghi-phat-hanh");
    }

    @Override
    public void checkApprove(AuthzResource r) {
      lastApprove = r;
      check("phe-duyet");
    }
  }

  static final class Engine implements TemplateTestEngine {
    List<TestCaseResult> results =
        List.of(new TestCaseResult("CHAY_HET_LUONG", "Tham số mặc định", "ketThuc", true, 2, 13));
    RuntimeException failure;
    List<BindingUsage> seen;

    @Override
    public List<TestCaseResult> run(String code, String xml, List<BindingUsage> bindings) {
      seen = bindings;
      if (failure != null) {
        throw failure;
      }
      return results;
    }

    @Override
    public int scenarioCount() {
      return 7;
    }
  }

  static final class Calendar implements RolloutCalendar {
    RolloutWindow window = new RolloutWindow(ZoneId.of("UTC"), List.of(), false, List.of(), 3);

    @Override
    public RolloutWindow window() {
      return window;
    }

    void blockNow() {
      window =
          new RolloutWindow(
              ZoneId.of("UTC"),
              List.of(RolloutWindow.PeakHours.parse("00:00-23:59")),
              true,
              List.of(),
              3);
    }
  }

  static final class Cells implements CellEngineGateway {
    final Set<String> unhealthyBefore = new HashSet<>();
    final Set<String> unhealthyAfter = new HashSet<>();
    final Map<String, Integer> current = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    private final Set<String> deployedNow = new HashSet<>();

    @Override
    public boolean healthy(String cell) {
      if (deployedNow.contains(cell)) {
        return !unhealthyAfter.contains(cell);
      }
      return !unhealthyBefore.contains(cell);
    }

    @Override
    public Integer currentVersion(String cell, String code) {
      return current.get(cell);
    }

    @Override
    public String deploy(String cell, String code, int versionNo, String xml) {
      calls.add("deploy:" + cell + ":" + versionNo);
      deployedNow.add(cell);
      current.put(cell, versionNo);
      return "dep-" + cell;
    }

    @Override
    public void restorePrevious(String cell, String code, Integer prev, String prevXml) {
      calls.add("restore:" + cell + ":" + prev + ":" + (prevXml != null));
      deployedNow.remove(cell);
      if (prev == null) {
        current.remove(cell);
      } else {
        current.put(cell, prev);
      }
    }
  }

  static final class Events implements TemplateEventPublisher {
    final List<String> published = new ArrayList<>();

    @Override
    public void published(TemplateVersion t, List<String> cells) {
      published.add(t.templateCode() + "@" + t.versionNo() + ":" + cells);
    }
  }

  static final class Dispatcher implements TemplateTestDispatcher {
    final List<Runnable> queued = new ArrayList<>();

    @Override
    public void dispatch(Runnable work) {
      queued.add(work);
    }
  }

  /** Kho saga trong bộ nhớ cho {@code SagaService}. */
  static final class Sagas implements SagaRepository {
    final Map<UUID, SagaInstance> rows = new HashMap<>();

    @Override
    public Optional<SagaInstance> findById(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<SagaInstance> findByCorrelation(SagaType type, UUID correlationId) {
      return rows.values().stream()
          .filter(s -> s.type() == type && s.correlationId().equals(correlationId))
          .findFirst();
    }

    @Override
    public void lockExclusiveKey(SagaType type, String key) {}

    @Override
    public boolean existsBlockingFor(SagaType type, String key) {
      return rows.values().stream()
          .anyMatch(s -> key.equals(s.exclusiveKey()) && s.status() != SagaStatus.DA_BU_TRU);
    }

    @Override
    public void insert(SagaInstance saga) {
      rows.put(saga.id(), saga);
    }

    @Override
    public void update(SagaInstance saga) {
      rows.put(saga.id(), saga);
    }
  }

  static final class SagaEngineStub implements SagaEngine {
    final List<UUID> launched = new ArrayList<>();
    final List<UUID> woken = new ArrayList<>();

    @Override
    public String launch(UUID sagaId, UUID tenantId, String businessKey) {
      launched.add(sagaId);
      return "pi";
    }

    @Override
    public boolean wake(UUID sagaId) {
      woken.add(sagaId);
      return true;
    }

    @Override
    public void completeTurn(String turnId, Next next) {}

    @Override
    public void failTurn(String turnId, String error, Duration backoff) {}
  }
}
