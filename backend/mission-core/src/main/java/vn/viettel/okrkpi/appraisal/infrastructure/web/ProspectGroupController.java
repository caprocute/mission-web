package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardResource;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.BoardView;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.EntryInput;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.EntryView;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardSubmission;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectBoardStatus;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectGroupBoard;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectGroupBoardInput;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectGroupBoardInputEntriesInner;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectGroupCode;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectGroupEntry;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevel;
import vn.viettel.okrkpi.contract.appraisals.model.Ref;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code getProspectGroupBoard}, {@code saveProspectGroupBoard}, {@code submitProspectGroupBoard}
 * của {@code appraisals.yaml} (CORE-04-12, QT-10, SCR-1002). Vai trò theo thao tác ({@code leader},
 * {@code appraiser}; trình chỉ {@code leader}); phạm vi đơn vị kiểm ở OPA trên {@link
 * ProspectBoardResource}. Dữ liệu C2: không ghi nhóm hay lý do vào nhật ký.
 */
@RestController
@RequestMapping("/api/v1/appraisals/prospect-groups/{periodId}/{orgUnitId}")
public class ProspectGroupController {

  private final ProspectBoardService service;
  private final ProspectBoardSubmission submission;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<AppraisalReviewGuard> guards;
  private final ObjectProvider<PolicyQueryApi> policies;

  public ProspectGroupController(
      ProspectBoardService service,
      ProspectBoardSubmission submission,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<AppraisalReviewGuard> guards,
      ObjectProvider<PolicyQueryApi> policies) {
    this.service = Objects.requireNonNull(service, "service");
    this.submission = Objects.requireNonNull(submission, "submission");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.guards = Objects.requireNonNull(guards, "guards");
    this.policies = Objects.requireNonNull(policies, "policies");
  }

  @GetMapping
  @RequiresPermission(
      operationId = "getProspectGroupBoard",
      module = "appraisals",
      resourceType = ProspectBoardResource.TYPE)
  public ResponseEntity<ProspectGroupBoard> getProspectGroupBoard(
      @PathVariable("periodId") UUID periodId, @PathVariable("orgUnitId") UUID orgUnitId) {
    TenantId tenant = TenantContext.require();
    guard(tenant, periodId, orgUnitId, AppraisalReviewGuard::viewProspects);
    return respond(tenant, found(() -> service.view(tenant, periodId, orgUnitId)));
  }

  @PutMapping
  @Idempotent
  @RequiresPermission(
      operationId = "saveProspectGroupBoard",
      module = "appraisals",
      resourceType = ProspectBoardResource.TYPE)
  public ResponseEntity<ProspectGroupBoard> saveProspectGroupBoard(
      @PathVariable("periodId") UUID periodId,
      @PathVariable("orgUnitId") UUID orgUnitId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody ProspectGroupBoardInput body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "APPRAISAL");
    guard(tenant, periodId, orgUnitId, AppraisalReviewGuard::arrangeProspects);
    List<EntryInput> inputs =
        body == null || body.getEntries() == null
            ? List.of()
            : body.getEntries().stream().map(ProspectGroupController::input).toList();
    UUID actor = actor();
    return respond(
        tenant, found(() -> service.save(tenant, periodId, orgUnitId, expected, inputs, actor)));
  }

  @PostMapping("/submit")
  @Idempotent
  @RequiresPermission(
      operationId = "submitProspectGroupBoard",
      module = "appraisals",
      resourceType = ProspectBoardResource.TYPE)
  public ResponseEntity<ProspectGroupBoard> submitProspectGroupBoard(
      @PathVariable("periodId") UUID periodId,
      @PathVariable("orgUnitId") UUID orgUnitId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "APPRAISAL");
    guard(tenant, periodId, orgUnitId, AppraisalReviewGuard::submitProspects);
    UUID actor = actor();
    return respond(
        tenant, found(() -> submission.submit(tenant, periodId, orgUnitId, expected, actor)));
  }

  private interface GuardCall {
    void check(AppraisalReviewGuard guard, ProspectBoardResource resource);
  }

  private void guard(TenantId tenant, UUID periodId, UUID orgUnitId, GuardCall call) {
    AppraisalReviewGuard guard = guards.getIfAvailable();
    if (guard != null) {
      call.check(guard, new ProspectBoardResource(tenant.value(), periodId, orgUnitId));
    }
  }

  private ResponseEntity<ProspectGroupBoard> respond(TenantId tenant, BoardView view) {
    return ResponseEntity.ok().eTag(ETags.of(view.rowVersion())).body(board(tenant, view));
  }

  ProspectGroupBoard board(TenantId tenant, BoardView v) {
    ProspectGroupBoard out =
        new ProspectGroupBoard(
            v.periodId(),
            new Ref(v.orgUnitId(), null),
            ProspectBoardStatus.fromValue(v.status()),
            v.entries().stream().map(ProspectGroupController::entry).toList(),
            (int) v.rowVersion());
    out.setRejectionReason(v.rejectionReason());
    PolicyQueryApi policy = policies.getIfAvailable();
    if (policy != null && v.flow() != null) {
      policy
          .resolve(tenant, PeriodId.of(v.periodId()), v.flow(), null, v.orgUnitId())
          .ifPresent(
              p -> {
                out.setPolicyPackId(p.policyPackId());
                out.setPolicyVersion(p.code() + " " + p.version());
              });
    }
    return out;
  }

  static ProspectGroupEntry entry(EntryView e) {
    ProspectGroupEntry out =
        new ProspectGroupEntry(
            new Ref(e.personId(), e.personName()), ProspectGroupCode.fromValue(e.groupCode()));
    out.setId(e.id());
    out.setAnnualRatingCode(
        e.annualRating() == null ? null : RatingLevel.fromValue(e.annualRating()));
    out.setReassignFlag(e.reassignFlag());
    out.setSuggestedGroup(
        e.suggestedGroup() == null ? null : ProspectGroupCode.fromValue(e.suggestedGroup()));
    out.setRankInGroup(e.rankInGroup());
    out.setReason(e.reason());
    out.setTrainingPlanRef(e.trainingPlanRef());
    out.setDecidedAt(e.decidedAt() == null ? null : e.decidedAt().atOffset(ZoneOffset.UTC));
    return out;
  }

  static EntryInput input(ProspectGroupBoardInputEntriesInner in) {
    return new EntryInput(
        in.getPersonId(),
        in.getGroupCode() == null ? null : in.getGroupCode().getValue(),
        in.getRankInGroup(),
        in.getReason(),
        in.getTrainingPlanRef());
  }

  private static <T> T found(Supplier<T> call) {
    try {
      return call.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private UUID actor() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    try {
      return UUID.fromString(subject.userId());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }
}
