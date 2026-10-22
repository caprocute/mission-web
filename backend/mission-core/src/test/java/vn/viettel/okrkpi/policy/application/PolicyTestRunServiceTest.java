package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService.RunOptions;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService.RunStarted;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService.RunView;
import vn.viettel.okrkpi.policy.application.port.CelJsRunner;
import vn.viettel.okrkpi.policy.application.port.GoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyDecisionTableRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyTestJobs;
import vn.viettel.okrkpi.policy.application.port.PolicyTestRunRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.GoldenCaseSource;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyTestRun;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.TestRunResult;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ chạy kiểm thử của CORE-05-07 với kho trong bộ nhớ và bộ chạy đồng bộ: bộ 63 ca của DL-03
 * đạt thì {@code DAT_KIEM_THU}; một ca sai, một biểu thức lệch cel-js, thiếu bộ chạy cel-js thì về
 * {@code NHAP}; ca của gói cha cho mức cao hơn bị đánh trượt; so sánh tác động; các lỗi 409, 422.
 */
class PolicyTestRunServiceTest {

  private static final String PACK = "khoi-dang/quy-dinh-moi";
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Kho lần chạy trong bộ nhớ. */
  static final class Runs implements PolicyTestRunRepository {
    final Map<UUID, PolicyTestRun> runs = new LinkedHashMap<>();
    final Map<UUID, List<CaseResult>> results = new HashMap<>();

    @Override
    public void insertRun(TenantId tenant, PolicyTestRun run) {
      runs.put(run.id(), run);
    }

    @Override
    public boolean storeFinished(TenantId tenant, PolicyTestRun run, long expected) {
      if (runs.get(run.id()).rowVersion() != expected) {
        return false;
      }
      runs.put(run.id(), run);
      return true;
    }

    @Override
    public void insertCaseResults(TenantId tenant, UUID runId, List<CaseResult> r) {
      results.computeIfAbsent(runId, k -> new ArrayList<>()).addAll(r);
    }

    @Override
    public Optional<NumberedRun> findRun(TenantId tenant, UUID versionId, UUID runId) {
      return Optional.ofNullable(runs.get(runId))
          .filter(r -> r.versionId().equals(versionId))
          .map(r -> new NumberedRun(r, new ArrayList<>(runs.keySet()).indexOf(runId) + 1));
    }

    @Override
    public Optional<PolicyTestRun> findRunning(TenantId tenant, UUID versionId) {
      return runs.values().stream()
          .filter(r -> r.versionId().equals(versionId) && r.running())
          .findFirst();
    }

    @Override
    public List<NumberedRun> listRuns(TenantId tenant, UUID versionId, UUID beforeId, int limit) {
      List<UUID> ids = new ArrayList<>(runs.keySet());
      return runs.values().stream()
          .filter(r -> r.versionId().equals(versionId))
          .filter(r -> beforeId == null || r.id().compareTo(beforeId) < 0)
          .sorted(Comparator.comparing(PolicyTestRun::id).reversed())
          .limit(limit)
          .map(r -> new NumberedRun(r, ids.indexOf(r.id()) + 1))
          .toList();
    }

    @Override
    public List<CaseResult> caseResults(TenantId tenant, UUID runId) {
      return results.getOrDefault(runId, List.of());
    }
  }

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final PolicyPackRepository packs = mock(PolicyPackRepository.class);
  private final GoldenTestCaseRepository cases = mock(GoldenTestCaseRepository.class);
  private final PolicyTestJobs jobs = mock(PolicyTestJobs.class);
  private final PolicyAudit audit = mock(PolicyAudit.class);
  private final Runs runs = new Runs();
  private final Map<UUID, AtomicReference<PolicyPackVersion>> versions = new HashMap<>();
  private final List<DecisionTable> tables = new ArrayList<>();
  private final Map<UUID, List<GoldenTestCase>> golden = new HashMap<>();
  private Function<CelJsRunner.Evaluation, CelJsRunner.Result> js;
  private boolean jsAvailable = true;
  private PolicyPack pack;
  private PolicyPackVersion draft;

  private PolicyTestRunService service(boolean required) {
    CelJsRunner runner =
        new CelJsRunner() {
          @Override
          public boolean available() {
            return jsAvailable;
          }

          @Override
          public List<Result> evaluate(List<Evaluation> evaluations) {
            return evaluations.stream().map(js).toList();
          }
        };
    return new PolicyTestRunService(
        packs,
        cases,
        runs,
        new PolicyDecisionTableService(
            packs, mock(PolicyDecisionTableRepository.class), audit, JSON),
        jobs,
        runner,
        required,
        audit,
        JSON,
        Clock.systemUtc(),
        Runnable::run,
        TransactionOperations.withoutTransaction());
  }

  /** cel-js giả: chạy lại bằng cel-java, cho kết quả trùng. */
  static CelJsRunner.Result echo(CelJsRunner.Evaluation e) {
    Map<String, CelValueType> types = new HashMap<>();
    e.variableTypes().forEach((k, v) -> types.put(k, CelValueType.valueOf(v)));
    Object v =
        PolicyEvaluatorTest.CEL.evaluate(
            e.expression(), types, CelValueType.valueOf(e.returnType()), e.inputs());
    return new CelJsRunner.Result(true, v, null, null);
  }

  private PolicyPackVersion version(String number, PolicyVersionStatus status, UUID source)
      throws Exception {
    Map<String, Object> manifest = PolicyEvaluatorTest.manifest(PACK);
    PolicyPackVersion v =
        new PolicyPackVersion(
            UUID.randomUUID(),
            pack.id(),
            number,
            status,
            null,
            JSON.writeValueAsString(manifest),
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            source,
            "Nháp",
            Instant.now(),
            author,
            Instant.now(),
            author,
            3);
    versions.put(v.id(), new AtomicReference<>(v));
    return v;
  }

  @BeforeEach
  void setUp() throws Exception {
    js = PolicyTestRunServiceTest::echo;
    pack =
        new PolicyPack(
            UUID.randomUUID(),
            PACK,
            "Gói",
            "KHOI_DANG",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    draft = version("2.1.0", PolicyVersionStatus.NHAP, null);
    Map<String, Object> manifest = PolicyEvaluatorTest.manifest(PACK);
    DmnSpecs.of(PolicyEvaluatorTest.DATA.resolve(PACK), manifest)
        .forEach(
            (key, spec) ->
                tables.add(
                    new DecisionTable(
                        UUID.randomUUID(),
                        draft.id(),
                        key,
                        "<dmn/>",
                        spec.hitPolicy(),
                        new byte[32],
                        "",
                        DmnSpecs.rulesJson(spec),
                        "{}")));
    List<GoldenTestCase> own = new ArrayList<>();
    for (GoldenSuiteYaml.Entry e : PolicyEvaluatorTest.suite(PACK).entries()) {
      own.add(golden(pack.id(), e.caseCode(), e.input(), e.expected()));
    }
    golden.put(pack.id(), own);
    when(packs.findPack(tenant, pack.id())).thenReturn(Optional.of(pack));
    when(packs.findVersion(any(), any(), anyString()))
        .thenAnswer(
            a ->
                versions.values().stream()
                    .map(AtomicReference::get)
                    .filter(v -> v.packId().equals(a.getArgument(1)))
                    .filter(v -> v.version().equals(a.getArgument(2)))
                    .findFirst());
    when(packs.findVersionById(any(), any()))
        .thenAnswer(
            a ->
                Optional.ofNullable(versions.get(a.<UUID>getArgument(1)))
                    .map(AtomicReference::get));
    when(packs.storeStatus(any(), any(), anyLong()))
        .thenAnswer(a -> store(a.getArgument(1), a.getArgument(2)));
    when(packs.storeTestOutcome(any(), any(), anyLong()))
        .thenAnswer(a -> store(a.getArgument(1), a.getArgument(2)));
    when(packs.decisionTables(any(), any())).thenAnswer(a -> tables);
    when(packs.rules(any(), any())).thenReturn(List.of());
    when(cases.listCases(any(), any(), eq(GoldenCaseStatus.DA_DUYET), any(), any(), eq(200)))
        .thenAnswer(a -> golden.getOrDefault(a.<UUID>getArgument(1), List.of()));
    when(cases.findCase(any(), any()))
        .thenAnswer(
            a ->
                golden.values().stream()
                    .flatMap(List::stream)
                    .filter(c -> c.id().equals(a.getArgument(1)))
                    .findFirst());
  }

  private boolean store(PolicyPackVersion v, long expected) {
    AtomicReference<PolicyPackVersion> ref = versions.get(v.id());
    if (ref.get().rowVersion() != expected) {
      return false;
    }
    ref.set(v);
    return true;
  }

  private GoldenTestCase golden(
      UUID packId, String code, Map<String, Object> input, Map<String, Object> expected)
      throws Exception {
    return new GoldenTestCase(
        UUID.randomUUID(),
        packId,
        code,
        "Ca " + code,
        JSON.writeValueAsString(input),
        JSON.writeValueAsString(expected),
        "",
        GoldenCaseSource.BA,
        GoldenCaseStatus.DA_DUYET,
        UUID.randomUUID(),
        Instant.now(),
        new byte[] {1},
        author,
        1);
  }

  private PolicyPackVersion current() {
    return versions.get(draft.id()).get();
  }

  private RunView lastRun(PolicyTestRunService s) {
    return s.findRun(
            tenant,
            pack.id(),
            "2.1.0",
            runs.runs.keySet().stream().reduce((a, b) -> b).orElseThrow())
        .orElseThrow();
  }

  @Test
  void wholeSuitePassesAndVersionIsTested() {
    PolicyTestRunService s = service(true);
    RunStarted started =
        s.runTests(tenant, author, pack.id(), "2.1.0", 3, new RunOptions(null, 300));
    assertThat(started.version().status()).isEqualTo(PolicyVersionStatus.DANG_KIEM_THU);
    RunView v = lastRun(s);
    assertThat(v.run().result()).isEqualTo(TestRunResult.DAT);
    assertThat(v.run().total()).isEqualTo(63);
    assertThat(v.run().passed()).isEqualTo(63);
    assertThat(v.run().engineMatch()).isTrue();
    assertThat(v.engines()).containsExactly("CEL_JAVA", "CEL_JS");
    assertThat(v.engineMatchedCount()).isEqualTo(63);
    assertThat(v.property()).containsEntry("datasets", 300).containsEntry("violations", 0);
    assertThat(v.cases()).hasSize(63).allMatch(c -> c.passed() && c.engineMatch());
    assertThat(v.runNo()).isEqualTo(1);
    assertThat(current().status()).isEqualTo(PolicyVersionStatus.DAT_KIEM_THU);
    assertThat(current().goldenPassRate()).isEqualByComparingTo("1");
    assertThat(current().lastTestRunId()).isEqualTo(v.run().id());
    verify(jobs).accepted(tenant, started.jobId(), author);
    verify(jobs)
        .finished(
            eq(tenant),
            eq(started.jobId()),
            eq(PolicyTestRunService.location(pack.id(), "2.1.0", v.run().id())),
            eq("Lần chạy #1: 63/63 ca đạt. Phiên bản đã đạt kiểm thử."));
    assertThat(s.listRuns(tenant, pack.id(), "2.1.0", null, 10)).hasSize(1);

    // Phiên bản đã đạt chạy lại được: về nháp rồi kiểm thử lại.
    s.runTests(tenant, author, pack.id(), "2.1.0", current().rowVersion(), new RunOptions(null, 0));
    assertThat(current().status()).isEqualTo(PolicyVersionStatus.DAT_KIEM_THU);
    assertThat(s.listRuns(tenant, pack.id(), "2.1.0", null, 10))
        .extracting(RunView::runNo)
        .containsExactly(2, 1);
  }

  @Test
  void oneWrongExpectationSendsVersionBackToDraft() throws Exception {
    Map<String, Object> wrong = new LinkedHashMap<>(Map.of("diem", 12.3));
    golden
        .get(pack.id())
        .add(
            golden(
                pack.id(),
                "Q-SAI",
                PolicyEvaluatorTest.suite(PACK).entries().get(0).input(),
                wrong));
    service(true).runTests(tenant, author, pack.id(), "2.1.0", 3, new RunOptions(null, 0));
    RunView v = lastRun(service(true));
    assertThat(v.run().result()).isEqualTo(TestRunResult.KHONG_DAT);
    assertThat(v.run().failed()).isEqualTo(1);
    assertThat(current().status()).isEqualTo(PolicyVersionStatus.NHAP);
    assertThat(current().goldenPassRate()).isEqualByComparingTo("0.9843");
    PolicyTestRunService.CaseView bad =
        v.cases().stream().filter(c -> !c.passed()).findFirst().orElseThrow();
    assertThat(bad.caseCode()).isEqualTo("Q-SAI");
    assertThat(bad.diff()).containsKey("diem");
    verify(jobs)
        .finished(
            eq(tenant),
            anyString(),
            anyString(),
            eq(
                "Lần chạy #1: 63/64 ca đạt. Phiên bản chưa đạt kiểm thử nên chưa thể gửi phê"
                    + " duyệt."));
  }

  @Test
  void engineMismatchFailsWithMsg1507() {
    js =
        e ->
            e.expression().contains("so_luong_duyet")
                ? new CelJsRunner.Result(true, 0.5, null, null)
                : echo(e);
    service(true).runTests(tenant, author, pack.id(), "2.1.0", 3, new RunOptions(null, 0));
    RunView v = lastRun(service(true));
    assertThat(v.run().result()).isEqualTo(TestRunResult.KHONG_DAT);
    assertThat(v.run().engineMatch()).isFalse();
    assertThat(v.run().passed()).isEqualTo(63);
    assertThat(v.engineMatchedCount()).isLessThan(63);
    PolicyTestRunService.CaseView c =
        v.cases().stream().filter(x -> !x.engineMatch()).findFirst().orElseThrow();
    assertThat(c.actualJs()).isNotEmpty();
    assertThat(v.run().casesJson()).contains("cho kết quả khác nhau giữa cel-java");
    assertThat(current().status()).isEqualTo(PolicyVersionStatus.NHAP);
  }

  @Test
  void runnerErrorsAndMissingRunner() {
    js = e -> new CelJsRunner.Result(false, null, "POLICY-422-CEL-TYPE", "lỗi");
    service(true).runTests(tenant, author, pack.id(), "2.1.0", 3, new RunOptions(null, 0));
    assertThat(lastRun(service(true)).run().engineMatch()).isFalse();

    js =
        e -> {
          throw new IllegalStateException("không phản hồi");
        };
    service(true)
        .runTests(
            tenant, author, pack.id(), "2.1.0", current().rowVersion(), new RunOptions(null, 0));
    assertThat(lastRun(service(true)).run().casesJson()).contains("không phản hồi");

    jsAvailable = false;
    service(true)
        .runTests(
            tenant, author, pack.id(), "2.1.0", current().rowVersion(), new RunOptions(null, 0));
    RunView strict = lastRun(service(true));
    assertThat(strict.run().result()).isEqualTo(TestRunResult.KHONG_DAT);
    assertThat(strict.engines()).containsExactly("CEL_JAVA");

    service(false)
        .runTests(
            tenant, author, pack.id(), "2.1.0", current().rowVersion(), new RunOptions(null, 0));
    RunView lenient = lastRun(service(false));
    assertThat(lenient.run().result()).isEqualTo(TestRunResult.DAT);
    assertThat(lenient.engines()).containsExactly("CEL_JAVA");
    assertThat(current().status()).isEqualTo(PolicyVersionStatus.DAT_KIEM_THU);
  }

  @Test
  void parentCasesMustNotGiveHigherLevelAndImpactIsCompared() throws Exception {
    PolicyPack parentPack =
        new PolicyPack(
            UUID.randomUUID(),
            "goc",
            "Gói gốc",
            "KHOI_DANG",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    PolicyPackVersion parentVersion = version("1.0.0", PolicyVersionStatus.DA_PHAT_HANH, null);
    versions.put(
        parentVersion.id(),
        new AtomicReference<>(
            new PolicyPackVersion(
                parentVersion.id(),
                parentPack.id(),
                "1.0.0",
                PolicyVersionStatus.DA_PHAT_HANH,
                null,
                parentVersion.manifestJson(),
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                "Gốc",
                Instant.now(),
                author,
                Instant.now(),
                author,
                0)));
    GoldenSuiteYaml.Entry q1 = PolicyEvaluatorTest.suite(PACK).entries().get(0);
    golden.put(
        parentPack.id(),
        List.of(
            golden(parentPack.id(), "P-1", q1.input(), Map.of("muc_cuoi", "HTNV")),
            golden(parentPack.id(), "P-2", q1.input(), Map.of("muc_cuoi", "HTXSNV")),
            golden(parentPack.id(), "P-3", q1.input(), Map.of("kpi_C", List.of(1.0)))));
    PolicyPackVersion child = version("2.2.0", PolicyVersionStatus.NHAP, parentVersion.id());
    version("2.0.0", PolicyVersionStatus.DA_PHAT_HANH, null);
    PolicyTestRunService s = service(true);
    s.runTests(tenant, author, pack.id(), "2.2.0", 3, new RunOptions("2.0.0", 0));
    RunView v =
        s.findRun(tenant, pack.id(), "2.2.0", runs.runs.keySet().iterator().next()).orElseThrow();
    assertThat(v.run().total()).isEqualTo(65);
    assertThat(v.run().failed()).isEqualTo(1);
    assertThat(v.cases())
        .filteredOn(c -> !c.passed())
        .singleElement()
        .satisfies(c -> assertThat(c.caseCode()).isEqualTo("P-1"));
    assertThat(v.impact())
        .containsEntry("baseVersion", "2.0.0")
        .containsEntry("changedScoreCount", 0)
        .containsEntry("changedRatingCount", 0);
    assertThat(versions.get(child.id()).get().status()).isEqualTo(PolicyVersionStatus.NHAP);
  }

  @Test
  void rejectsRunningFrozenConflictingAndInvalidRequests() throws Exception {
    PolicyTestRunService s = service(true);
    assertThatThrownBy(() -> s.runTests(tenant, author, pack.id(), "2.1.0", 2, null))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () -> s.runTests(tenant, author, pack.id(), "2.1.0", 3, new RunOptions(null, 100_001)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () -> s.runTests(tenant, author, pack.id(), "2.1.0", 3, new RunOptions("9.9.9", 1)))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () -> s.runTests(tenant, author, pack.id(), "2.1.0", 3, new RunOptions("x", 1)))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> s.runTests(tenant, author, pack.id(), "9.0.0", 3, null))
        .isInstanceOf(java.util.NoSuchElementException.class);

    version("3.0.0", PolicyVersionStatus.CHO_PHE_DUYET, null);
    assertThatThrownBy(() -> s.runTests(tenant, author, pack.id(), "3.0.0", 3, null))
        .hasMessage("POLICY-409-VERSION-FROZEN");

    PolicyPackVersion running = version("3.1.0", PolicyVersionStatus.DANG_KIEM_THU, null);
    assertThatThrownBy(() -> s.runTests(tenant, author, pack.id(), "3.1.0", 3, null))
        .hasMessage("POLICY-409-TEST-RUNNING");
    runs.insertRun(
        tenant,
        PolicyTestRun.started(
            UUID.randomUUID(), running.id(), author, Instant.now().minusSeconds(3600)));
    s.runTests(tenant, author, pack.id(), "3.1.0", 3, new RunOptions(null, 0));
    assertThat(runs.runs.values())
        .filteredOn(r -> r.versionId().equals(running.id()))
        .extracting(PolicyTestRun::result)
        .containsExactlyInAnyOrder(TestRunResult.KHONG_DAT, TestRunResult.DAT);

    UUID v = UUID.randomUUID();
    when(packs.rules(any(), eq(draft.id())))
        .thenReturn(
            List.of(
                new PolicyRule(
                    UUID.randomUUID(),
                    v,
                    "kpi_A",
                    "nv.ke_hoach + 'a'",
                    "DECIMAL",
                    "{}",
                    "",
                    10,
                    "{}",
                    true)));
    assertThatThrownBy(() -> s.runTests(tenant, author, pack.id(), "2.1.0", 3, null))
        .hasMessage("POLICY-422-CEL-TYPE");
  }

  @Test
  void failureDuringRunAbortsAndReportsJobFailure() {
    doThrow(new IllegalStateException("hỏng"))
        .when(audit)
        .changed(any(), any(), eq("KIEM_THU_POLICY_PACK_DAT"), any(), any(), any(), anyLong());
    PolicyTestRunService s = service(true);
    RunStarted started = s.runTests(tenant, author, pack.id(), "2.1.0", 3, new RunOptions(null, 0));
    verify(jobs).failed(tenant, started.jobId(), "Không chạy được kiểm thử, vui lòng chạy lại.");
    assertThat(current().status()).isIn(PolicyVersionStatus.NHAP, PolicyVersionStatus.DAT_KIEM_THU);
  }

  @Test
  void runViewWithoutSummaryUsesDefaults() {
    PolicyTestRun r = PolicyTestRun.started(UUID.randomUUID(), draft.id(), author, Instant.now());
    runs.insertRun(tenant, r);
    RunView v = service(true).findRun(tenant, pack.id(), "2.1.0", r.id()).orElseThrow();
    assertThat(v.engines()).containsExactly("CEL_JAVA");
    assertThat(v.property()).isNull();
    assertThat(v.impact()).isNull();
    assertThat(v.cases()).isEmpty();
    assertThat(service(true).findRun(tenant, pack.id(), "2.1.0", UUID.randomUUID())).isEmpty();
  }

  @Test
  void missingRunnerIsUnavailable() {
    assertThat(CelJsRunner.NONE.available()).isFalse();
    assertThatThrownBy(() -> CelJsRunner.NONE.evaluate(List.of()))
        .isInstanceOf(IllegalStateException.class);
  }
}
