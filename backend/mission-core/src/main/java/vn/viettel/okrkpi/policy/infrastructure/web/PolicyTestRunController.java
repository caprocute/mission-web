package vn.viettel.okrkpi.policy.infrastructure.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.policies.model.PolicyEngine;
import vn.viettel.okrkpi.contract.policies.model.PolicyImpact;
import vn.viettel.okrkpi.contract.policies.model.PolicyPropertyResult;
import vn.viettel.okrkpi.contract.policies.model.PolicyTestCaseResult;
import vn.viettel.okrkpi.contract.policies.model.PolicyTestRun;
import vn.viettel.okrkpi.contract.policies.model.PolicyTestRunCreate;
import vn.viettel.okrkpi.contract.policies.model.PolicyTestRunList;
import vn.viettel.okrkpi.contract.policies.model.PolicyTestRunResult;
import vn.viettel.okrkpi.contract.policies.model.PolicyTestRunSummary;
import vn.viettel.okrkpi.contract.policies.model.Ref;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService.CaseView;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService.RunOptions;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService.RunStarted;
import vn.viettel.okrkpi.policy.application.PolicyTestRunService.RunView;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Chạy kiểm thử phiên bản Policy Pack trong {@code policies.yaml} (CORE-05-07, QT-15, SCR-1503):
 * {@code runPolicyTests} (202, tác vụ {@code TEST_RUN}), {@code listPolicyTestRuns}, {@code
 * getPolicyTestRun}.
 */
@RestController
@RequestMapping("/api/v1/policies/packs/{id}/versions/{v}/test-runs")
public class PolicyTestRunController {

  /** Mặc định 10 lần gần nhất (SCR-1503 bảng trường dòng 6). */
  static final int DEFAULT_LIMIT = 10;

  private final PolicyTestRunService runs;
  private final PolicyIdempotency idempotency;
  private final CursorCodec cursors;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PolicyCaller> callers;

  public PolicyTestRunController(
      PolicyTestRunService runs,
      PolicyIdempotency idempotency,
      CursorCodec cursors,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PolicyCaller> callers) {
    this.runs = Objects.requireNonNull(runs, "runs");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @PostMapping
  @RequiresPermission(action = "SCR-1503.chay", operationId = "runPolicyTests", module = "policies")
  public ResponseEntity<Job> runPolicyTests(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) PolicyTestRunCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser();
    long expected = ETags.expectedVersion(ifMatch, "POLICY");
    PolicyTestRunCreate request = body == null ? new PolicyTestRunCreate() : body;
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        "/api/v1/policies/packs/" + id + "/versions/" + version + "/test-runs",
        request,
        Job.class,
        j -> 0,
        () -> {
          RunStarted started =
              notFound(
                  () ->
                      runs.runTests(
                          tenant,
                          actor,
                          id,
                          version,
                          expected,
                          new RunOptions(
                              request.getCompareWithVersion(), request.getPropertyDatasets())));
          return Jobs.accepted(
              new Job(
                  started.jobId(),
                  "TEST_RUN",
                  vn.viettel.okrkpi.shared.job.JobStatus.CHO,
                  0,
                  null,
                  null,
                  null,
                  java.time.OffsetDateTime.now(ZoneOffset.UTC)));
        });
  }

  @GetMapping
  @RequiresPermission(operationId = "listPolicyTestRuns", module = "policies")
  public PolicyTestRunList listPolicyTestRuns(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PageRequest page =
        cursors.pageRequest(
            limit == null ? DEFAULT_LIMIT : limit, cursor, "test-runs:" + id + ":" + version);
    List<RunView> found =
        notFound(
            () ->
                runs.listRuns(
                    TenantContext.require(),
                    id,
                    version,
                    page.afterKey().map(k -> UUID.fromString(k.getString(0))).orElse(null),
                    page.fetchSize()));
    CursorPage<RunView> result =
        cursors.page(found, page, r -> CursorKey.of(r.run().id().toString()));
    return new PolicyTestRunList(
        result.items().stream().map(PolicyTestRunController::summary).toList(),
        result.nextCursor());
  }

  @GetMapping("/{runId}")
  @RequiresPermission(operationId = "getPolicyTestRun", module = "policies")
  public PolicyTestRun getPolicyTestRun(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @PathVariable("runId") UUID runId) {
    RunView v =
        notFound(() -> runs.findRun(TenantContext.require(), id, version, runId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    vn.viettel.okrkpi.policy.domain.PolicyTestRun r = v.run();
    PolicyTestRun dto =
        new PolicyTestRun(
                r.id(),
                r.versionId(),
                v.version(),
                PolicyTestRunResult.fromValue(r.result().name()),
                r.total(),
                r.passed(),
                r.failed(),
                r.engineMatch(),
                time(r.startedAt()))
            .runNo(v.runNo())
            .engines(v.engines().stream().map(PolicyEngine::fromValue).toList())
            .engineMatchedCount(v.engineMatchedCount())
            .finishedAt(time(r.finishedAt()))
            .durationMs(r.durationMs() == null ? null : r.durationMs().intValue())
            .createdBy(new Ref(r.createdBy(), ""))
            .cases(v.cases().stream().map(PolicyTestRunController::caseDto).toList())
            .configDiff(null)
            .impact(impact(v.impact()));
    if (v.property() != null) {
      dto.propertyResult(property(v.property()));
    }
    return dto;
  }

  private static PolicyTestRunSummary summary(RunView v) {
    vn.viettel.okrkpi.policy.domain.PolicyTestRun r = v.run();
    return new PolicyTestRunSummary(
            r.id(),
            r.versionId(),
            v.version(),
            PolicyTestRunResult.fromValue(r.result().name()),
            r.total(),
            r.passed(),
            r.failed(),
            r.engineMatch(),
            time(r.startedAt()))
        .runNo(v.runNo())
        .engines(v.engines().stream().map(PolicyEngine::fromValue).toList())
        .engineMatchedCount(v.engineMatchedCount())
        .finishedAt(time(r.finishedAt()))
        .durationMs(r.durationMs() == null ? null : r.durationMs().intValue())
        .createdBy(new Ref(r.createdBy(), ""));
  }

  private static PolicyTestCaseResult caseDto(CaseView c) {
    PolicyTestCaseResult dto =
        new PolicyTestCaseResult(c.goldenTestCaseId(), c.caseCode(), c.passed())
            .expected(c.expected())
            .actual(c.actual())
            .actualJs(c.actualJs())
            .engineMatch(c.engineMatch())
            .diff(c.diff());
    if (c.description() != null) {
      dto.description(c.description());
    }
    return dto;
  }

  @SuppressWarnings("unchecked")
  private static PolicyPropertyResult property(Map<String, Object> p) {
    PolicyPropertyResult dto =
        new PolicyPropertyResult(number(p.get("datasets")), number(p.get("violations")));
    if (p.get("invariants") instanceof List<?> l) {
      dto.invariants(l.stream().map(String::valueOf).toList());
    }
    return dto;
  }

  private static PolicyImpact impact(Map<String, Object> m) {
    if (m == null) {
      return null;
    }
    return new PolicyImpact(
            String.valueOf(m.get("baseVersion")), number(m.get("changedRatingCount")))
        .changedScoreCount(number(m.get("changedScoreCount")))
        .lockedPeriodNote(String.valueOf(m.get("lockedPeriodNote")));
  }

  private static int number(Object v) {
    return v instanceof Number n ? n.intValue() : 0;
  }

  private static java.time.OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private UUID currentUser() {
    PolicyCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId().orElseThrow(PolicyTestRunController::unauthorized);
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw unauthorized();
    }
    try {
      return UUID.fromString(subject.userId());
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  private static <T> T notFound(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
