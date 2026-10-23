package vn.viettel.okrkpi.oversight.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.oversight.model.ListReopenRequests200Response;
import vn.viettel.okrkpi.contract.oversight.model.OversightReasonInput;
import vn.viettel.okrkpi.contract.oversight.model.PeriodRef;
import vn.viettel.okrkpi.contract.oversight.model.PeriodStatus;
import vn.viettel.okrkpi.contract.oversight.model.Ref;
import vn.viettel.okrkpi.contract.oversight.model.ReopenApproval;
import vn.viettel.okrkpi.contract.oversight.model.ReopenApproverSlot;
import vn.viettel.okrkpi.contract.oversight.model.ReopenAuditKind;
import vn.viettel.okrkpi.contract.oversight.model.ReopenImpact;
import vn.viettel.okrkpi.contract.oversight.model.ReopenImpactRowsInner;
import vn.viettel.okrkpi.contract.oversight.model.ReopenRequest;
import vn.viettel.okrkpi.contract.oversight.model.ReopenRequestInput;
import vn.viettel.okrkpi.contract.oversight.model.ReopenRequestScopeDetail;
import vn.viettel.okrkpi.contract.oversight.model.ReopenRequestStatus;
import vn.viettel.okrkpi.contract.oversight.model.ReopenScope;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository.Adjustment;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenApprovalService;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.View;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScopeItem;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenWorkflow;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Yêu cầu mở lại kỳ (CORE-07-07, QT-13, SCR-1301): {@code listReopenRequests}, {@code
 * createReopenRequest}, {@code getReopenRequest}, {@code simulateReopenRequest}, {@code
 * cancelReopenRequest}; {@code approveReopenRequest}, {@code rejectReopenRequest} (CORE-07-08, bốn
 * mắt, bước lên và ký số). Duyệt, từ chối trả 200 với yêu cầu đang chờ chữ ký; trạng thái chuyển
 * khi SVC-10 báo ký xong (EVT-18).
 */
@RestController
@RequestMapping(ReopenRequestController.BASE)
public class ReopenRequestController {

  static final String BASE = "/api/v1/oversight/reopen-requests";
  private static final String SCOPE = "reopen-requests";

  private final ObjectProvider<ReopenRequestService> service;
  private final ObjectProvider<ReopenApprovalService> approvals;
  private final ObjectProvider<OversightIdempotency> idempotency;
  private final ObjectProvider<AccessDirectory> directory;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OversightCaller> custom;
  private final CursorCodec cursors;

  public ReopenRequestController(
      ObjectProvider<ReopenRequestService> service,
      ObjectProvider<ReopenApprovalService> approvals,
      ObjectProvider<OversightIdempotency> idempotency,
      ObjectProvider<AccessDirectory> directory,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OversightCaller> custom,
      CursorCodec cursors) {
    this.service = Objects.requireNonNull(service, "service");
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.custom = Objects.requireNonNull(custom, "custom");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  @GetMapping
  @RequiresPermission(operationId = "listReopenRequests", module = "oversight")
  public ListReopenRequests200Response listReopenRequests(
      @RequestParam(value = "period", required = false) UUID periodId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "awaitingMyApproval", required = false, defaultValue = "false")
          boolean awaitingMyApproval,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    PageRequest page = cursors.pageRequest(limit, cursor, SCOPE);
    ReopenRequestRepository.After after =
        page.afterKey()
            .map(k -> new ReopenRequestRepository.After(k.getInstant(0), k.getUuid(1)))
            .orElse(null);
    List<View> fetched =
        service()
            .list(
                tenant,
                actor,
                periodId,
                statuses(status),
                awaitingMyApproval,
                after,
                page.fetchSize());
    CursorPage<View> result =
        cursors.page(fetched, page, v -> CursorKey.of(v.request().createdAt(), v.request().id()));
    List<ReopenRequest> items = new ArrayList<>();
    for (View v : result.items()) {
      items.add(toDto(tenant, v));
    }
    return new ListReopenRequests200Response().items(items).nextCursor(result.nextCursor());
  }

  @PostMapping
  @RequiresPermission(
      operationId = "createReopenRequest",
      module = "oversight",
      resourceType = "reopen_request")
  public ResponseEntity<ReopenRequest> createReopenRequest(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ReopenRequestInput body) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE,
            body,
            ReopenRequest.class,
            ReopenRequest::getRowVersion,
            () -> {
              View v = service().create(tenant, actor, draft(body));
              return ResponseEntity.created(URI.create(BASE + "/" + v.request().id()))
                  .eTag(ETags.of(v.request().rowVersion()))
                  .body(toDto(tenant, v));
            });
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getReopenRequest",
      module = "oversight",
      resourceType = "reopen_request")
  public ResponseEntity<ReopenRequest> getReopenRequest(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    View v = notFoundAs404(() -> service().get(tenant, actor(), id));
    return ETags.ok(toDto(tenant, v), v.request().rowVersion());
  }

  @PostMapping("/{id}/simulate")
  @RequiresPermission(
      operationId = "simulateReopenRequest",
      module = "oversight",
      resourceType = "reopen_request")
  public ReopenImpact simulateReopenRequest(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) JsonNode body) {
    // Tính thử không ghi gì (kể cả oversight.idempotency_key): gửi lại cùng khoá cho cùng kết quả.
    TenantId tenant = TenantContext.require();
    List<Adjustment> adjustments = adjustments(body);
    ReopenImpactSimulator.Impact impact;
    try {
      impact =
          notFoundAs404(() -> service().simulate(tenant, actor(), id, adjustments, authorization));
    } catch (ReopenRequestService.SimulationUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
    ReopenImpact out = new ReopenImpact().policyVersion(impact.policyVersion());
    for (ReopenImpactSimulator.Row r : impact.rows()) {
      out.addRowsItem(
          new ReopenImpactRowsInner()
              .profileId(r.profileId())
              .metric(r.metric())
              .before(r.before())
              .after(r.after()));
    }
    out.setFormsToRegenerate(impact.formsToRegenerate());
    return out;
  }

  @PostMapping("/{id}/cancel")
  @RequiresPermission(
      operationId = "cancelReopenRequest",
      module = "oversight",
      resourceType = "reopen_request")
  public ResponseEntity<ReopenRequest> cancelReopenRequest(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody OversightReasonInput body) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE + "/" + id + "/cancel",
            body,
            ReopenRequest.class,
            ReopenRequest::getRowVersion,
            () -> {
              View v =
                  notFoundAs404(
                      () ->
                          service()
                              .cancel(
                                  tenant,
                                  actor,
                                  id,
                                  ifMatch,
                                  body == null ? null : body.getReason()));
              return ETags.ok(toDto(tenant, v), v.request().rowVersion());
            });
  }

  @PostMapping("/{id}/approvals")
  @RequiresPermission(
      operationId = "approveReopenRequest",
      module = "oversight",
      resourceType = "reopen_request")
  public ResponseEntity<ReopenRequest> approveReopenRequest(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) JsonNode body) {
    return decide(id, idempotencyKey, ifMatch, authorization, body, true, "/approvals");
  }

  @PostMapping("/{id}/reject")
  @RequiresPermission(
      operationId = "rejectReopenRequest",
      module = "oversight",
      resourceType = "reopen_request")
  public ResponseEntity<ReopenRequest> rejectReopenRequest(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) JsonNode body) {
    return decide(id, idempotencyKey, ifMatch, authorization, body, false, "/reject");
  }

  private ResponseEntity<ReopenRequest> decide(
      UUID id,
      String idempotencyKey,
      String ifMatch,
      String authorization,
      JsonNode body,
      boolean approve,
      String suffix) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    ReopenApprovalService s = approvals.getIfAvailable();
    if (s == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    String comment = body == null ? null : text(body, "comment");
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE + "/" + id + suffix,
            body,
            ReopenRequest.class,
            ReopenRequest::getRowVersion,
            () -> {
              View v;
              try {
                v =
                    notFoundAs404(
                        () ->
                            s.decide(
                                tenant,
                                actor,
                                id,
                                ifMatch,
                                approve,
                                comment,
                                idempotencyKey,
                                authorization));
              } catch (ReopenApprovalService.SigningUnavailableException e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
              }
              return ETags.ok(toDto(tenant, v), v.request().rowVersion());
            });
  }

  private static ReopenRequestService.Draft draft(ReopenRequestInput in) {
    if (in == null) {
      throw invalid("periodId", "Thiếu nội dung yêu cầu.");
    }
    List<ReopenScopeItem> items = new ArrayList<>();
    if (in.getScopeDetail() != null && in.getScopeDetail().getItems() != null) {
      for (vn.viettel.okrkpi.contract.oversight.model.ReopenScopeItem i :
          in.getScopeDetail().getItems()) {
        items.add(
            new ReopenScopeItem(
                i.getTable(), i.getProfileId(), i.getUnitId(), i.getRecordId(), i.getFields()));
      }
    }
    ReopenBasis basis = null;
    if (in.getBasis() != null && in.getBasis().getKind() != null) {
      basis =
          new ReopenBasis(
              ReopenBasis.Kind.valueOf(in.getBasis().getKind().getValue()),
              in.getBasis().getId(),
              in.getBasis().getCode());
    }
    return new ReopenRequestService.Draft(
        in.getPeriodId(),
        in.getAuditKind() == null ? null : in.getAuditKind().getValue(),
        in.getScope() == null
            ? null
            : vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope.valueOf(
                in.getScope().getValue()),
        in.getScopeIds(),
        items,
        in.getOpenWorkingDays(),
        in.getReason(),
        basis);
  }

  private static List<Adjustment> adjustments(JsonNode body) {
    List<Adjustment> out = new ArrayList<>();
    if (body == null || !body.has("adjustments") || body.get("adjustments").isNull()) {
      return out;
    }
    JsonNode list = body.get("adjustments");
    if (!list.isArray()) {
      throw invalid("adjustments", "Danh sách điều chỉnh không hợp lệ.");
    }
    for (JsonNode a : list) {
      String key = text(a, "targetKey");
      String value = text(a, "newValue");
      String reason = text(a, "reason");
      if (key == null || key.length() > 100 || value == null || reason == null) {
        throw invalid("adjustments", "Mỗi điều chỉnh cần chỉ tiêu, giá trị mới và lý do.");
      }
      out.add(new Adjustment(key, text(a, "oldValue"), value, reason));
    }
    return out;
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static Set<ReopenStatus> statuses(String raw) {
    Set<ReopenStatus> out = new HashSet<>();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    for (String s : Arrays.asList(raw.split(","))) {
      try {
        out.add(ReopenStatus.valueOf(s.strip()));
      } catch (IllegalArgumentException e) {
        throw invalid("status", "Trạng thái lọc không hợp lệ.");
      }
    }
    return out;
  }

  private ReopenRequest toDto(TenantId tenant, View v) {
    vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest r = v.request();
    Set<UUID> people = new HashSet<>();
    people.add(r.requestedBy());
    v.approvals().forEach(a -> people.add(a.approverId()));
    r.workflow().slots().stream()
        .map(ReopenWorkflow.Slot::assignee)
        .filter(Objects::nonNull)
        .forEach(people::add);
    AccessDirectory dir = directory.getIfAvailable();
    Map<UUID, String> names = dir == null ? Map.of() : dir.personNames(tenant, people);
    ReopenRequest dto =
        new ReopenRequest()
            .id(r.id())
            .code(r.code())
            .period(period(r.periodId(), v.period()))
            .auditKind(ReopenAuditKind.fromValue(r.auditKind()))
            .scope(ReopenScope.fromValue(r.scope().name()))
            .scopeIds(new ArrayList<>(r.scopeIds()))
            .scopeDetail(scopeDetail(r))
            .openUntil(odt(r.openUntil()))
            .reason(r.reason())
            .basis(basis(r.basis()))
            .requestedBy(ref(r.requestedBy(), names))
            .approvalsRequired(r.approvalsRequired())
            .status(ReopenRequestStatus.fromValue(r.status().name()))
            .createdAt(odt(r.createdAt()))
            .rowVersion(r.rowVersion());
    dto.setExecutedAt(odt(r.executedAt()));
    dto.setRelockedAt(odt(r.relockedAt()));
    if (v.period() != null) {
      dto.periodStatus(PeriodStatus.fromValue(v.period().status().name()));
    }
    dto.setApprovers(slots(r.workflow(), v.approvals(), names));
    return dto;
  }

  private static List<ReopenApproverSlot> slots(
      ReopenWorkflow workflow,
      List<ReopenRequestRepository.Approval> approvals,
      Map<UUID, String> names) {
    List<ReopenApproverSlot> out = new ArrayList<>();
    String[] roles = {"decider", "second_approver"};
    for (int i = 0; i < roles.length; i++) {
      String role = roles[i];
      ReopenApproverSlot slot =
          new ReopenApproverSlot()
              .round(ReopenApproverSlot.RoundEnum.fromValue(i + 1))
              .role(ReopenApproverSlot.RoleEnum.fromValue(role));
      UUID assignee = workflow.assignee(i + 1);
      if (assignee != null) {
        slot.assignee(ref(assignee, names));
      }
      approvals.stream()
          .filter(a -> role.equals(a.approverRole()))
          .findFirst()
          .ifPresent(
              a -> {
                slot.assignee(ref(a.approverId(), names));
                slot.approval(
                    new ReopenApproval()
                        .id(a.id())
                        .approver(ref(a.approverId(), names))
                        .approverRole(ReopenApproval.ApproverRoleEnum.fromValue(role))
                        .decision(ReopenApproval.DecisionEnum.fromValue(a.decision()))
                        .decidedAt(odt(a.decidedAt()))
                        .mfaAcr(a.mfaAcr())
                        .signRequestId(a.signRequestId())
                        .comment(a.comment()));
              });
      out.add(slot);
    }
    return out;
  }

  private static ReopenRequestScopeDetail scopeDetail(
      vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest r) {
    ReopenRequestScopeDetail d = new ReopenRequestScopeDetail();
    for (ReopenScopeItem i : r.items()) {
      d.addItemsItem(
          new vn.viettel.okrkpi.contract.oversight.model.ReopenScopeItem()
              .table(i.table())
              .profileId(i.profileId())
              .unitId(i.unitId())
              .recordId(i.recordId())
              .fields(new ArrayList<>(i.fields())));
    }
    return d;
  }

  private static vn.viettel.okrkpi.contract.oversight.model.ReopenBasis basis(ReopenBasis b) {
    if (b == null) {
      return null;
    }
    return new vn.viettel.okrkpi.contract.oversight.model.ReopenBasis()
        .kind(
            vn.viettel.okrkpi.contract.oversight.model.ReopenBasis.KindEnum.fromValue(
                b.kind().name()))
        .id(b.id())
        .code(b.code());
  }

  private static PeriodRef period(UUID id, PeriodSnapshot p) {
    PeriodRef ref = new PeriodRef().id(id);
    if (p != null) {
      ref.label(p.name()).startDate(p.startDate()).endDate(p.endDate());
    }
    return ref;
  }

  private static Ref ref(UUID id, Map<UUID, String> names) {
    return new Ref().id(id).name(names.getOrDefault(id, ""));
  }

  private static OffsetDateTime odt(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private Actor actor() {
    OversightCaller caller = custom.getIfAvailable();
    if (caller != null) {
      return new Actor(
          caller.currentUserId().orElseThrow(ReopenRequestController::unauthorized),
          caller.roles(),
          caller.unitId().orElse(null),
          caller.acr().orElse(null),
          caller.authTime().orElse(null));
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw unauthorized();
    }
    try {
      return new Actor(
          UUID.fromString(subject.userId()),
          subject.roles(),
          subject.unitId() == null || subject.unitId().isBlank()
              ? null
              : UUID.fromString(subject.unitId()),
          subject.acr(),
          subject.authTime());
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  private ReopenRequestService service() {
    ReopenRequestService s = service.getIfAvailable();
    if (s == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return s;
  }

  private OversightIdempotency idempotency() {
    OversightIdempotency i = idempotency.getIfAvailable();
    if (i == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return i;
  }

  private static <T> T notFoundAs404(Supplier<T> action) {
    try {
      return action.get();
    } catch (ReopenRequestService.NotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
