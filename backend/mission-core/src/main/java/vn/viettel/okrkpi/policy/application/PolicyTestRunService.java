package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.policy.application.port.CelJsRunner;
import vn.viettel.okrkpi.policy.application.port.GoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyTestJobs;
import vn.viettel.okrkpi.policy.application.port.PolicyTestRunRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyTestRunRepository.CaseResult;
import vn.viettel.okrkpi.policy.application.port.PolicyTestRunRepository.NumberedRun;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyTestRun;
import vn.viettel.okrkpi.policy.domain.PolicyTransitionException;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.ResultComparison;
import vn.viettel.okrkpi.policy.domain.SemVer;
import vn.viettel.okrkpi.policy.domain.TestRunResult;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Chạy kiểm thử phiên bản Policy Pack (CORE-05-07, QT-15, SCR-1503, BR-15-07, ADR-19): {@code
 * runPolicyTests} chuyển phiên bản {@code NHAP} sang {@code DANG_KIEM_THU} và nhận tác vụ {@code
 * TEST_RUN}; sau khi giao dịch ghi xong, tác vụ chạy toàn bộ ca vàng {@code DA_DUYET} của gói (và
 * của gói cha khi kế thừa, X-PP-11) trên cel-java với bảng quyết định, đối chiếu từng biểu thức với
 * cel-js, kiểm thử thuộc tính, so sánh tác động khi có phiên bản so sánh, rồi lên {@code
 * DAT_KIEM_THU} hoặc về {@code NHAP} kèm MSG-15-06.
 */
public class PolicyTestRunService {

  static final String TX = PolicyPackService.TX;

  public static final int DEFAULT_DATASETS = 10_000;
  public static final int MAX_DATASETS = 100_000;

  /** Lần chạy dở quá thời gian này coi như đã hỏng (pod dừng giữa chừng, suy luận). */
  static final Duration STALE = Duration.ofMinutes(30);

  /** Số lần gọi CEL mỗi lô gửi bộ chạy cel-js. */
  static final int JS_BATCH = 2_000;

  static final String LOCKED_PERIOD_NOTE =
      "Chỉ so trên bộ ca kiểm thử, chưa áp dụng cho hồ sơ của kỳ đã khoá.";

  private static final Logger log = LoggerFactory.getLogger(PolicyTestRunService.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** Tuỳ chọn của {@code PolicyTestRunCreate}. */
  public record RunOptions(String compareWithVersion, Integer propertyDatasets) {}

  /** Lần chạy đã nhận: tác vụ, lần chạy và phiên bản ở {@code DANG_KIEM_THU}. */
  public record RunStarted(String jobId, UUID runId, PolicyPackVersion version) {}

  /** Một ca trong kết quả lần chạy (SCR-1503 bảng ca). */
  public record CaseView(
      UUID goldenTestCaseId,
      String caseCode,
      String description,
      Map<String, Object> expected,
      Map<String, Object> actual,
      Map<String, Object> actualJs,
      boolean passed,
      boolean engineMatch,
      Map<String, Object> diff) {}

  /** Lần chạy kèm số thứ tự, engine đã chạy, kiểm thử thuộc tính, tác động và từng ca. */
  public record RunView(
      PolicyTestRun run,
      int runNo,
      String version,
      List<String> engines,
      int engineMatchedCount,
      Map<String, Object> property,
      Map<String, Object> impact,
      List<CaseView> cases) {}

  private record Inputs(
      PolicyPackVersion version,
      PolicyEvaluator evaluator,
      PolicyEvaluator baseline,
      String baselineVersion,
      List<GoldenTestCase> cases,
      List<GoldenTestCase> parentCases) {}

  private record Evaluated(
      GoldenTestCase golden,
      boolean inherited,
      Map<String, Object> actual,
      Map<String, Object> diff,
      boolean passed,
      List<PolicyEvaluator.Trace> traces,
      Map<String, Object> outputs) {}

  private final PolicyPackRepository packs;
  private final GoldenTestCaseRepository cases;
  private final PolicyTestRunRepository runs;
  private final PolicyEvaluators evaluators;
  private final PolicyTestJobs jobs;
  private final CelJsRunner celJs;
  private final boolean celJsRequired;
  private final PolicyAudit audit;
  private final ObjectMapper json;
  private final Clock clock;
  private final Executor executor;
  private final TransactionOperations writes;

  @SuppressWarnings("java:S107")
  public PolicyTestRunService(
      PolicyPackRepository packs,
      GoldenTestCaseRepository cases,
      PolicyTestRunRepository runs,
      PolicyEvaluators evaluators,
      PolicyTestJobs jobs,
      CelJsRunner celJs,
      boolean celJsRequired,
      PolicyAudit audit,
      ObjectMapper json,
      Clock clock,
      Executor executor,
      TransactionOperations writes) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.cases = Objects.requireNonNull(cases, "cases");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.evaluators = Objects.requireNonNull(evaluators, "evaluators");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.celJs = Objects.requireNonNull(celJs, "celJs");
    this.celJsRequired = celJsRequired;
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.writes = Objects.requireNonNull(writes, "writes");
  }

  /**
   * Nhận lần chạy (202): kiểm lần chạy đang dở ({@code POLICY-409-TEST-RUNNING}), khoá lạc quan,
   * phiên bản đã đóng băng ({@code POLICY-409-VERSION-FROZEN}), biên dịch mọi biểu thức ({@code
   * POLICY-422-CEL-TYPE}), rồi chuyển {@code DANG_KIEM_THU}. Phiên bản {@code DAT_KIEM_THU} chạy
   * lại được: về {@code NHAP} rồi kiểm thử lại.
   */
  @Transactional(TX)
  public RunStarted runTests(
      TenantId tenant,
      UUID actor,
      UUID packId,
      String version,
      long expectedRowVersion,
      RunOptions options) {
    RunOptions opts = options == null ? new RunOptions(null, null) : options;
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion current =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    Instant now = clock.instant();
    Optional<PolicyTestRun> stale = Optional.empty();
    if (current.status() == PolicyVersionStatus.DANG_KIEM_THU) {
      Optional<PolicyTestRun> running = runs.findRunning(tenant, current.id());
      if (running.isEmpty() || !running.get().staleAt(now, STALE)) {
        throw new BusinessException(
            "POLICY-409-TEST-RUNNING",
            "Phiên bản đang chạy kiểm thử, vui lòng chờ lần chạy hiện tại kết thúc");
      }
      stale = running;
    }
    if (current.rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("POLICY", expectedRowVersion, current.rowVersion());
    }
    if (current.status() != PolicyVersionStatus.NHAP
        && current.status() != PolicyVersionStatus.DAT_KIEM_THU
        && current.status() != PolicyVersionStatus.DANG_KIEM_THU) {
      throw new BusinessException(
          "POLICY-409-VERSION-FROZEN",
          "Phiên bản "
              + version
              + " đã gửi phê duyệt hoặc đã phát hành nên không chạy lại kiểm thử");
    }
    int datasets = datasets(opts.propertyDatasets());
    if (opts.compareWithVersion() != null) {
      String base =
          SemVer.parse(opts.compareWithVersion())
              .map(SemVer::toString)
              .orElseThrow(
                  () -> invalid("compareWithVersion", "Phiên bản so sánh phải có dạng 1.0.0"));
      if (packs.findVersion(tenant, packId, base).isEmpty()) {
        throw invalid("compareWithVersion", "Gói không có phiên bản " + base + " để so sánh");
      }
    }
    evaluator(tenant, current);
    PolicyPackVersion base = current;
    try {
      if (stale.isPresent()) {
        PolicyTestRun s = stale.get();
        runs.storeFinished(
            tenant, s.abort(summary(List.of(), 0, "Lần chạy bị gián đoạn"), now), s.rowVersion());
        base = base.moveTo(PolicyVersionStatus.NHAP, actor, now);
      } else if (base.status() == PolicyVersionStatus.DAT_KIEM_THU) {
        base = base.moveTo(PolicyVersionStatus.NHAP, actor, now);
      }
      base = base.moveTo(PolicyVersionStatus.DANG_KIEM_THU, actor, now);
    } catch (PolicyTransitionException e) {
      throw new BusinessException(e.code(), e.getMessage());
    }
    if (!packs.storeStatus(tenant, base, expectedRowVersion)) {
      throw new VersionConflictException("POLICY", expectedRowVersion, null);
    }
    PolicyTestRun run = PolicyTestRun.started(Ids.newUuidV7(), current.id(), actor, now);
    runs.insertRun(tenant, run);
    audit.changed(
        tenant,
        actor,
        "CHAY_KIEM_THU_POLICY_PACK",
        PolicyPackService.OBJECT_TYPE,
        pack.code() + "@" + version,
        current.id(),
        base.rowVersion());
    String jobId = Ids.newUuidV7().toString();
    jobs.accepted(tenant, jobId, actor);
    RunOptions accepted = new RunOptions(opts.compareWithVersion(), datasets);
    afterCommit(
        () ->
            executor.execute(
                () ->
                    TenantContext.run(
                        tenant,
                        () -> execute(tenant, actor, packId, version, run.id(), jobId, accepted))));
    return new RunStarted(jobId, run.id(), base);
  }

  /** Lịch sử chạy của phiên bản, mới nhất trước. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<RunView> listRuns(
      TenantId tenant, UUID packId, String version, UUID beforeId, int limit) {
    PolicyPackVersion v = version(tenant, packId, version);
    return runs.listRuns(tenant, v.id(), beforeId, limit).stream()
        .map(n -> view(n, v.version(), List.of()))
        .toList();
  }

  /** Kết quả đầy đủ một lần chạy kèm từng ca. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<RunView> findRun(TenantId tenant, UUID packId, String version, UUID runId) {
    PolicyPackVersion v = version(tenant, packId, version);
    return runs.findRun(tenant, v.id(), runId)
        .map(n -> view(n, v.version(), caseViews(tenant, n.run())));
  }

  /** Bước nền của tác vụ {@code TEST_RUN}. */
  void execute(
      TenantId tenant,
      UUID actor,
      UUID packId,
      String version,
      UUID runId,
      String jobId,
      RunOptions options) {
    jobs.running(tenant, jobId, 5);
    try {
      Inputs in = inTx(() -> inputs(tenant, packId, version, options));
      List<Evaluated> evaluated = new ArrayList<>();
      for (GoldenTestCase c : in.cases()) {
        evaluated.add(evaluate(in.evaluator(), c, false));
      }
      for (GoldenTestCase c : in.parentCases()) {
        Evaluated e = evaluate(in.evaluator(), c, true);
        if (e != null) {
          evaluated.add(e);
        }
      }
      jobs.running(tenant, jobId, 40);
      EngineCheck engines = compareEngines(evaluated);
      jobs.running(tenant, jobId, 60);
      PolicyPropertyCheck.Report property =
          new PolicyPropertyCheck(
                  in.evaluator(), runId.getMostSignificantBits() ^ runId.getLeastSignificantBits())
              .run(options.propertyDatasets());
      jobs.running(tenant, jobId, 90);
      Map<String, Object> impact = in.baseline() == null ? null : impact(in, evaluated);
      PolicyTestRun finished =
          inTx(() -> store(tenant, actor, runId, in, evaluated, engines, property, impact));
      NumberedRun numbered =
          inTx(() -> runs.findRun(tenant, in.version().id(), runId).orElseThrow());
      String message =
          finished.result() == TestRunResult.DAT
              ? "Lần chạy #"
                  + numbered.runNo()
                  + ": "
                  + finished.passed()
                  + "/"
                  + finished.total()
                  + " ca đạt. Phiên bản đã đạt kiểm thử."
              : "Lần chạy #"
                  + numbered.runNo()
                  + ": "
                  + finished.passed()
                  + "/"
                  + finished.total()
                  + " ca đạt. Phiên bản chưa đạt kiểm thử nên chưa thể gửi phê duyệt.";
      jobs.finished(tenant, jobId, location(packId, version, runId), message);
    } catch (RuntimeException e) {
      log.error("Chạy kiểm thử Policy Pack {} phiên bản {} lỗi", packId, version, e);
      try {
        writes.executeWithoutResult(s -> abort(tenant, actor, packId, version, runId));
      } catch (RuntimeException ignored) {
        log.error("Không ghi được lần chạy {} bị lỗi", runId, ignored);
      }
      jobs.failed(tenant, jobId, "Không chạy được kiểm thử, vui lòng chạy lại.");
    }
  }

  private Inputs inputs(TenantId tenant, UUID packId, String version, RunOptions options) {
    PolicyPackVersion v =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    PolicyEvaluator evaluator = evaluator(tenant, v);
    PolicyEvaluator baseline = null;
    String baselineVersion = null;
    if (options.compareWithVersion() != null) {
      PolicyPackVersion b =
          packs
              .findVersion(
                  tenant,
                  packId,
                  SemVer.parse(options.compareWithVersion()).orElseThrow().toString())
              .orElseThrow(NoSuchElementException::new);
      baseline = evaluator(tenant, b);
      baselineVersion = b.version();
    }
    List<GoldenTestCase> parent = List.of();
    if (v.inheritsFromParent()) {
      parent =
          packs
              .findVersionById(tenant, v.sourceVersionId())
              .filter(p -> !p.packId().equals(packId))
              .map(p -> approved(tenant, p.packId()))
              .orElse(List.of());
    }
    return new Inputs(v, evaluator, baseline, baselineVersion, approved(tenant, packId), parent);
  }

  private List<GoldenTestCase> approved(TenantId tenant, UUID packId) {
    List<GoldenTestCase> all = new ArrayList<>();
    String after = null;
    while (true) {
      List<GoldenTestCase> page =
          cases.listCases(tenant, packId, GoldenCaseStatus.DA_DUYET, null, after, 200);
      all.addAll(page);
      if (page.size() < 200) {
        return all;
      }
      after = page.get(page.size() - 1).caseCode();
    }
  }

  /** Bộ đánh giá của phiên bản (đệm chung với tính thử một ca). */
  PolicyEvaluator evaluator(TenantId tenant, PolicyPackVersion v) {
    return evaluators.of(tenant, v);
  }

  /**
   * Một ca: ca của gói so toàn bộ khoá mong đợi; ca của gói cha chỉ kiểm mức không cao hơn mức mong
   * đợi (X-PP-11), ca không có mức thì bỏ.
   */
  private Evaluated evaluate(PolicyEvaluator evaluator, GoldenTestCase c, boolean inherited) {
    Map<String, Object> expected = read(c.expectedJson());
    String levelKey = evaluator.levelKey();
    Object expectedLevel =
        expected.containsKey(levelKey) ? expected.get(levelKey) : expected.get("xep_loai");
    if (inherited && expectedLevel == null) {
      return null;
    }
    try {
      PolicyEvaluator.Outcome out = evaluator.evaluate(read(c.inputJson()), true);
      Map<String, Object> outputs = out.outputs();
      if (inherited) {
        Object level =
            outputs.containsKey(levelKey) ? outputs.get(levelKey) : outputs.get("xep_loai");
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put(levelKey, level);
        Map<String, Object> diff = new LinkedHashMap<>();
        boolean ok = level != null && !ResultComparison.higherThan(level, expectedLevel);
        if (!ok) {
          diff.put(levelKey, Map.of("expected", expectedLevel, "actual", String.valueOf(level)));
        }
        return new Evaluated(c, true, actual, diff, ok, out.traces(), outputs);
      }
      Map<String, Object> diff = ResultComparison.diff(expected, outputs);
      return new Evaluated(
          c,
          false,
          ResultComparison.actualFor(expected, outputs),
          diff,
          diff.isEmpty(),
          out.traces(),
          outputs);
    } catch (RuntimeException e) {
      Map<String, Object> diff = new LinkedHashMap<>();
      diff.put("loi", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      return new Evaluated(c, inherited, Map.of(), diff, false, List.of(), Map.of());
    }
  }

  /** Kết quả đối chiếu cel-js theo ca. */
  private record EngineCheck(
      List<String> engines,
      Map<UUID, Map<String, Object>> mismatches,
      boolean all,
      String note,
      List<String> warnings) {}

  private EngineCheck compareEngines(List<Evaluated> evaluated) {
    if (!celJs.available()) {
      if (celJsRequired) {
        return new EngineCheck(
            List.of(PolicyTestRun.CEL_JAVA),
            unmatched(evaluated, "Chưa cấu hình bộ chạy cel-js"),
            false,
            "Chưa cấu hình bộ chạy cel-js nên chưa đối chiếu được hai engine",
            List.of());
      }
      return new EngineCheck(
          List.of(PolicyTestRun.CEL_JAVA),
          Map.of(),
          true,
          "Môi trường không bắt buộc đối chiếu cel-js",
          List.of());
    }
    List<CelJsRunner.Evaluation> all = new ArrayList<>();
    evaluated.forEach(e -> e.traces().forEach(t -> all.add(t.evaluation())));
    List<CelJsRunner.Result> results = new ArrayList<>();
    try {
      for (int i = 0; i < all.size(); i += JS_BATCH) {
        List<CelJsRunner.Evaluation> batch = all.subList(i, Math.min(all.size(), i + JS_BATCH));
        List<CelJsRunner.Result> r = celJs.evaluate(batch);
        if (r.size() != batch.size()) {
          throw new IllegalStateException("Bộ chạy cel-js trả thiếu kết quả");
        }
        results.addAll(r);
      }
    } catch (RuntimeException e) {
      log.warn("Không đối chiếu được cel-js", e);
      return new EngineCheck(
          List.of(PolicyTestRun.CEL_JAVA),
          unmatched(evaluated, "Bộ chạy cel-js không phản hồi"),
          false,
          "Bộ chạy cel-js không phản hồi nên chưa đối chiếu được hai engine",
          List.of());
    }
    Map<UUID, Map<String, Object>> mismatches = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    int k = 0;
    for (Evaluated e : evaluated) {
      Map<String, Object> js = new LinkedHashMap<>();
      for (PolicyEvaluator.Trace t : e.traces()) {
        CelJsRunner.Result r = results.get(k++);
        boolean same = r.ok() && ResultComparison.same(t.javaResult(), r.value(), 9);
        if (!same) {
          Object shown = r.ok() ? r.value() : "Lỗi " + r.errorCode();
          js.put(t.label(), shown);
          if (warnings.size() < 20) {
            // MSG-15-07
            warnings.add(
                "Ca "
                    + e.golden().caseCode()
                    + " cho kết quả khác nhau giữa cel-java ("
                    + t.javaResult()
                    + ") và cel-js ("
                    + shown
                    + ").");
          }
        }
      }
      if (!js.isEmpty()) {
        mismatches.put(e.golden().id(), js);
      }
    }
    return new EngineCheck(
        List.of(PolicyTestRun.CEL_JAVA, PolicyTestRun.CEL_JS),
        mismatches,
        mismatches.isEmpty(),
        null,
        warnings);
  }

  private static Map<UUID, Map<String, Object>> unmatched(List<Evaluated> evaluated, String why) {
    Map<UUID, Map<String, Object>> m = new LinkedHashMap<>();
    evaluated.forEach(e -> m.put(e.golden().id(), Map.of("loi", why)));
    return m;
  }

  /** So sánh tác động với phiên bản {@code compareWithVersion} trên ca của gói (PC-06). */
  private Map<String, Object> impact(Inputs in, List<Evaluated> evaluated) {
    int score = 0;
    int rating = 0;
    String levelKey = in.evaluator().levelKey();
    for (Evaluated e : evaluated) {
      if (e.inherited() || e.outputs().isEmpty()) {
        continue;
      }
      Map<String, Object> base;
      try {
        base = in.baseline().evaluate(read(e.golden().inputJson()), false).outputs();
      } catch (RuntimeException ex) {
        rating++;
        score++;
        continue;
      }
      Object a = e.outputs().get(PolicyEvaluator.SCORE_KEY);
      Object b = base.get(PolicyEvaluator.SCORE_KEY);
      if ((a != null || b != null) && !ResultComparison.same(a, b, ResultComparison.SCALE)) {
        score++;
      }
      Object la = e.outputs().get(levelKey);
      Object lb = base.get(levelKey);
      if ((la != null || lb != null) && !Objects.equals(la, lb)) {
        rating++;
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("baseVersion", in.baselineVersion());
    m.put("changedScoreCount", score);
    m.put("changedRatingCount", rating);
    m.put("lockedPeriodNote", LOCKED_PERIOD_NOTE);
    return m;
  }

  private PolicyTestRun store(
      TenantId tenant,
      UUID actor,
      UUID runId,
      Inputs in,
      List<Evaluated> evaluated,
      EngineCheck engines,
      PolicyPropertyCheck.Report property,
      Map<String, Object> impact) {
    PolicyTestRun run = runs.findRun(tenant, in.version().id(), runId).orElseThrow().run();
    List<CaseResult> results = new ArrayList<>();
    List<Map<String, Object>> caseSummary = new ArrayList<>();
    int passed = 0;
    for (Evaluated e : evaluated) {
      Map<String, Object> js = engines.mismatches().get(e.golden().id());
      results.add(
          new CaseResult(
              e.golden().id(),
              e.passed(),
              write(e.actual()),
              e.diff().isEmpty() ? null : write(e.diff())));
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("id", e.golden().id().toString());
      s.put("engineMatch", js == null);
      s.put("actualJs", js);
      s.put("inherited", e.inherited());
      caseSummary.add(s);
      if (e.passed()) {
        passed++;
      }
    }
    runs.insertCaseResults(tenant, runId, results);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("engines", engines.engines());
    summary.put("engineMatchedCount", evaluated.size() - engines.mismatches().size());
    summary.put("engineNote", engines.note());
    summary.put("warnings", engines.warnings());
    summary.put("impact", impact);
    summary.put("cases", caseSummary);
    Instant now = clock.instant();
    PolicyTestRun finished =
        run.finish(
            evaluated.size(),
            passed,
            engines.all(),
            property.violations(),
            write(property.json()),
            write(summary),
            now);
    if (!runs.storeFinished(tenant, finished, run.rowVersion())) {
      throw new VersionConflictException("POLICY", run.rowVersion(), null);
    }
    moveVersion(tenant, actor, in.version().id(), finished, now);
    return finished;
  }

  private void abort(TenantId tenant, UUID actor, UUID packId, String version, UUID runId) {
    Instant now = clock.instant();
    Optional<PolicyPackVersion> v = packs.findVersion(tenant, packId, version);
    Optional<NumberedRun> n = v.flatMap(x -> runs.findRun(tenant, x.id(), runId));
    if (n.isEmpty() || !n.get().run().running()) {
      return;
    }
    PolicyTestRun aborted =
        n.get().run().abort(summary(List.of(), 0, "Lần chạy gặp lỗi hệ thống"), now);
    runs.storeFinished(tenant, aborted, n.get().run().rowVersion());
    moveVersion(tenant, actor, v.get().id(), aborted, now);
  }

  private void moveVersion(
      TenantId tenant, UUID actor, UUID versionId, PolicyTestRun finished, Instant now) {
    PolicyPackVersion v =
        packs.findVersionById(tenant, versionId).orElseThrow(NoSuchElementException::new);
    if (v.status() != PolicyVersionStatus.DANG_KIEM_THU) {
      return;
    }
    boolean ok = finished.result() == TestRunResult.DAT;
    PolicyPackVersion moved = v.testOutcome(ok, finished.passRate(), finished.id(), actor, now);
    if (ok) {
      moved =
          moved.withContentHash(
              PolicyContentHash.of(
                  json,
                  v.manifestJson(),
                  packs.rules(tenant, v.id()),
                  packs.decisionTables(tenant, v.id())));
    }
    if (!packs.storeTestOutcome(tenant, moved, v.rowVersion())) {
      throw new VersionConflictException("POLICY", v.rowVersion(), null);
    }
    audit.changed(
        tenant,
        actor,
        ok ? "KIEM_THU_POLICY_PACK_DAT" : "KIEM_THU_POLICY_PACK_KHONG_DAT",
        PolicyPackService.OBJECT_TYPE,
        v.version(),
        v.id(),
        moved.rowVersion());
  }

  private String summary(List<String> engines, int matched, String note) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("engines", engines);
    m.put("engineMatchedCount", matched);
    m.put("engineNote", note);
    return write(m);
  }

  @SuppressWarnings("unchecked")
  private RunView view(NumberedRun n, String version, List<CaseView> caseViews) {
    Map<String, Object> summary = read(n.run().casesJson());
    List<String> engines =
        summary.get("engines") instanceof List<?> l
            ? l.stream().map(String::valueOf).toList()
            : List.of(PolicyTestRun.CEL_JAVA);
    int matched = summary.get("engineMatchedCount") instanceof Number x ? x.intValue() : 0;
    Map<String, Object> impact =
        summary.get("impact") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    Map<String, Object> property =
        n.run().propertyResultJson() == null ? null : read(n.run().propertyResultJson());
    return new RunView(n.run(), n.runNo(), version, engines, matched, property, impact, caseViews);
  }

  @SuppressWarnings("unchecked")
  private List<CaseView> caseViews(TenantId tenant, PolicyTestRun run) {
    Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    if (read(run.casesJson()).get("cases") instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Map<?, ?> m) {
          byId.put(String.valueOf(m.get("id")), (Map<String, Object>) m);
        }
      }
    }
    List<CaseView> out = new ArrayList<>();
    for (CaseResult r : runs.caseResults(tenant, run.id())) {
      Optional<GoldenTestCase> golden = cases.findCase(tenant, r.goldenTestCaseId());
      Map<String, Object> s = byId.getOrDefault(r.goldenTestCaseId().toString(), Map.of());
      out.add(
          new CaseView(
              r.goldenTestCaseId(),
              golden.map(GoldenTestCase::caseCode).orElse(""),
              golden.map(GoldenTestCase::description).orElse(null),
              golden.map(g -> read(g.expectedJson())).orElse(Map.of()),
              read(r.actualJson()),
              s.get("actualJs") instanceof Map<?, ?> js ? (Map<String, Object>) js : null,
              r.passed(),
              !Boolean.FALSE.equals(s.get("engineMatch")),
              r.diffJson() == null ? null : read(r.diffJson())));
    }
    return out;
  }

  private PolicyPackVersion version(TenantId tenant, UUID packId, String version) {
    packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    return packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
  }

  /** Giao dịch ghi riêng của tác vụ nền; kết quả không bao giờ null. */
  private <T> T inTx(java.util.function.Supplier<T> work) {
    return Objects.requireNonNull(writes.execute(s -> work.get()), "kết quả giao dịch");
  }

  static String location(UUID packId, String version, UUID runId) {
    return "/api/v1/policies/packs/" + packId + "/versions/" + version + "/test-runs/" + runId;
  }

  private static int datasets(Integer requested) {
    if (requested == null) {
      return DEFAULT_DATASETS;
    }
    if (requested < 0 || requested > MAX_DATASETS) {
      throw invalid("propertyDatasets", "Số bộ dữ liệu kiểm thử thuộc tính từ 0 đến 100000");
    }
    return requested;
  }

  private Map<String, Object> read(String value) {
    try {
      return value == null || value.isBlank() ? Map.of() : json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Nội dung JSON không đọc được", e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được JSON", e);
    }
  }

  static void afterCommit(Runnable work) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              work.run();
            }
          });
    } else {
      work.run();
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
