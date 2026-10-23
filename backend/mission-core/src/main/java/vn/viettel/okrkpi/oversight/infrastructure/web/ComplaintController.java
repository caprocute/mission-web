package vn.viettel.okrkpi.oversight.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.oversight.model.AcceptComplaintRequest;
import vn.viettel.okrkpi.contract.oversight.model.ComplainableProfile;
import vn.viettel.okrkpi.contract.oversight.model.Complaint;
import vn.viettel.okrkpi.contract.oversight.model.ComplaintInput;
import vn.viettel.okrkpi.contract.oversight.model.ComplaintSummary;
import vn.viettel.okrkpi.contract.oversight.model.ComplaintTarget;
import vn.viettel.okrkpi.contract.oversight.model.ComplaintTargetStage;
import vn.viettel.okrkpi.contract.oversight.model.ComplaintTimelineItem;
import vn.viettel.okrkpi.contract.oversight.model.ListComplainableProfiles200Response;
import vn.viettel.okrkpi.contract.oversight.model.ListComplaints200Response;
import vn.viettel.okrkpi.contract.oversight.model.OversightReasonInput;
import vn.viettel.okrkpi.contract.oversight.model.PeriodRef;
import vn.viettel.okrkpi.contract.oversight.model.RatingLevel;
import vn.viettel.okrkpi.contract.oversight.model.Ref;
import vn.viettel.okrkpi.contract.oversight.model.RejectComplaintRequest;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintHandlingService;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.Draft;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.Eligible;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.View;
import vn.viettel.okrkpi.oversight.application.port.ComplaintRepository;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintCloseReason;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStage;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
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
 * Gửi khiếu nại (CORE-07-02, QT-12 bước 1, 2, SCR-1201): {@code listComplainableProfiles}, {@code
 * listComplaints} (khiếu nại của chính người gọi), {@code createComplaint}, {@code getComplaint},
 * {@code updateComplaint}, {@code submitComplaint}. Gửi trả 202 với tác vụ {@code ESIGN}; khiếu nại
 * thành {@code MOI} khi SVC-10 báo ký xong (EVT-18).
 */
@RestController
@RequestMapping(ComplaintController.BASE)
public class ComplaintController {

  static final String BASE = "/api/v1/oversight/complaints";
  private static final String SCOPE = "complaints";
  private static final String SCOPE_HANDLER = "complaints-handler";

  private final ObjectProvider<ComplaintService> service;
  private final ObjectProvider<ComplaintHandlingService> handling;
  private final ObjectProvider<ComplaintGuard> guards;
  private final ObjectProvider<OversightIdempotency> idempotency;
  private final ObjectProvider<AccessDirectory> directory;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OversightCaller> custom;
  private final CursorCodec cursors;

  public ComplaintController(
      ObjectProvider<ComplaintService> service,
      ObjectProvider<ComplaintHandlingService> handling,
      ObjectProvider<ComplaintGuard> guards,
      ObjectProvider<OversightIdempotency> idempotency,
      ObjectProvider<AccessDirectory> directory,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OversightCaller> custom,
      CursorCodec cursors) {
    this.service = Objects.requireNonNull(service, "service");
    this.handling = Objects.requireNonNull(handling, "handling");
    this.guards = Objects.requireNonNull(guards, "guards");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.custom = Objects.requireNonNull(custom, "custom");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  @GetMapping("/eligible-profiles")
  @RequiresPermission(operationId = "listComplainableProfiles", module = "oversight")
  public ListComplainableProfiles200Response listComplainableProfiles() {
    TenantId tenant = TenantContext.require();
    ListComplainableProfiles200Response out = new ListComplainableProfiles200Response();
    out.setItems(new ArrayList<>());
    for (Eligible e : service().eligible(tenant, actor())) {
      out.addItemsItem(toDto(e));
    }
    return out;
  }

  @GetMapping
  @RequiresPermission(operationId = "listComplaints", module = "oversight")
  public ListComplaints200Response listComplaints(
      @RequestParam(value = "periodId", required = false) UUID periodId,
      @RequestParam(value = "status", required = false) List<String> status,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "assignedToMe", required = false) Boolean assignedToMe,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    if (ComplaintHandlingService.handlerRole(actor)) {
      return handlerList(
          tenant,
          actor,
          periodId,
          statuses(status),
          kind(kind),
          Boolean.TRUE.equals(assignedToMe),
          limit,
          cursor);
    }
    PageRequest page = cursors.pageRequest(limit, cursor, SCOPE);
    ComplaintRepository.After after =
        page.afterKey()
            .map(k -> new ComplaintRepository.After(k.getInstant(0), k.getUuid(1)))
            .orElse(null);
    ComplaintService s = service();
    List<vn.viettel.okrkpi.oversight.domain.complaint.Complaint> fetched =
        s.list(tenant, actor, periodId, statuses(status), kind(kind), after, page.fetchSize());
    CursorPage<vn.viettel.okrkpi.oversight.domain.complaint.Complaint> result =
        cursors.page(fetched, page, c -> CursorKey.of(c.createdAt(), c.id()));
    Map<UUID, String> names = names(tenant, result.items().stream().map(c -> c.complainantId()));
    List<ComplaintSummary> items = new ArrayList<>();
    for (var c : result.items()) {
      items.add(summary(c, names, s.responseDaysLeft(tenant, c)));
    }
    return new ListComplaints200Response().items(items).nextCursor(result.nextCursor());
  }

  /** SCR-1202 trường 1: khiếu nại trong phạm vi người xử lý, sắp theo hạn trả lời. */
  private ListComplaints200Response handlerList(
      TenantId tenant,
      Actor actor,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      ComplaintKind kind,
      boolean assignedToMe,
      Integer limit,
      String cursor) {
    PageRequest page = cursors.pageRequest(limit, cursor, SCOPE_HANDLER);
    ComplaintRepository.HandlerAfter after =
        page.afterKey()
            .map(k -> new ComplaintRepository.HandlerAfter(k.getDate(0), k.getUuid(1)))
            .orElse(null);
    List<vn.viettel.okrkpi.oversight.domain.complaint.Complaint> fetched =
        handling()
            .list(tenant, actor, periodId, statuses, kind, assignedToMe, after, page.fetchSize());
    CursorPage<vn.viettel.okrkpi.oversight.domain.complaint.Complaint> result =
        cursors.page(fetched, page, c -> CursorKey.of(c.responseDeadline(), c.id()));
    Map<UUID, String> names = names(tenant, result.items().stream().map(c -> c.complainantId()));
    ComplaintService s = service();
    List<ComplaintSummary> items = new ArrayList<>();
    for (var c : result.items()) {
      items.add(summary(c, names, s.responseDaysLeft(tenant, c)));
    }
    return new ListComplaints200Response().items(items).nextCursor(result.nextCursor());
  }

  @PostMapping
  @RequiresPermission(
      operationId = "createComplaint",
      module = "oversight",
      resourceType = "complaint")
  public ResponseEntity<Complaint> createComplaint(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ComplaintInput body) {
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
            Complaint.class,
            Complaint::getRowVersion,
            () -> {
              View v = guarded(() -> service().create(tenant, actor, draft(body)));
              return ResponseEntity.created(URI.create(BASE + "/" + v.complaint().id()))
                  .eTag(ETags.of(v.complaint().rowVersion()))
                  .body(toDto(tenant, v));
            });
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getComplaint",
      module = "oversight",
      resourceType = "complaint")
  public ResponseEntity<Complaint> getComplaint(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    View v =
        guarded(
            () ->
                ComplaintHandlingService.handlerRole(actor)
                    ? handling().get(tenant, actor, id)
                    : service().get(tenant, actor, id));
    return ETags.ok(toDto(tenant, v), v.complaint().rowVersion());
  }

  @PutMapping("/{id}")
  @RequiresPermission(
      operationId = "updateComplaint",
      module = "oversight",
      resourceType = "complaint")
  public ResponseEntity<Complaint> updateComplaint(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody ComplaintInput body) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "PUT",
            BASE + "/" + id,
            body,
            Complaint.class,
            Complaint::getRowVersion,
            () -> {
              View v = guarded(() -> service().update(tenant, actor, id, ifMatch, draft(body)));
              return ETags.ok(toDto(tenant, v), v.complaint().rowVersion());
            });
  }

  @PostMapping("/{id}/submit")
  @RequiresPermission(
      operationId = "submitComplaint",
      module = "oversight",
      resourceType = "complaint")
  public ResponseEntity<Job> submitComplaint(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE + "/" + id + "/submit",
            null,
            Job.class,
            null,
            () -> {
              UUID sign =
                  guarded(
                      () ->
                          service()
                              .submit(tenant, actor, id, ifMatch, idempotencyKey, authorization));
              return Jobs.accepted(ComplaintSignJobs.pending(sign));
            });
  }

  @PostMapping("/{id}/accept")
  @RequiresPermission(
      operationId = "acceptComplaint",
      module = "oversight",
      resourceType = "complaint")
  public ResponseEntity<Complaint> acceptComplaint(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) AcceptComplaintRequest body) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE + "/" + id + "/accept",
            body,
            Complaint.class,
            Complaint::getRowVersion,
            () -> {
              String note = body == null ? null : body.getNote();
              View v =
                  guarded(
                      () -> {
                        checkHandler(tenant, id);
                        return handling().accept(tenant, actor, id, ifMatch, note);
                      });
              return ETags.ok(toDto(tenant, v), v.complaint().rowVersion());
            });
  }

  @PostMapping("/{id}/reject")
  @RequiresPermission(
      operationId = "rejectComplaint",
      module = "oversight",
      resourceType = "complaint")
  public ResponseEntity<Complaint> rejectComplaint(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) RejectComplaintRequest body) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE + "/" + id + "/reject",
            body,
            Complaint.class,
            Complaint::getRowVersion,
            () -> {
              ComplaintCloseReason code =
                  body == null || body.getReasonCode() == null
                      ? null
                      : ComplaintCloseReason.valueOf(body.getReasonCode().getValue());
              String reason = body == null ? null : body.getReason();
              View v =
                  guarded(
                      () -> {
                        checkHandler(tenant, id);
                        return handling().reject(tenant, actor, id, ifMatch, code, reason);
                      });
              return ETags.ok(toDto(tenant, v), v.complaint().rowVersion());
            });
  }

  @PostMapping("/{id}/withdraw")
  @RequiresPermission(
      operationId = "withdrawComplaint",
      module = "oversight",
      resourceType = "complaint")
  public ResponseEntity<Complaint> withdrawComplaint(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) OversightReasonInput body) {
    TenantId tenant = TenantContext.require();
    Actor actor = actor();
    return idempotency()
        .run(
            tenant,
            actor.userId(),
            idempotencyKey,
            "POST",
            BASE + "/" + id + "/withdraw",
            body,
            Complaint.class,
            Complaint::getRowVersion,
            () -> {
              String reason = body == null ? null : body.getReason();
              View v = guarded(() -> handling().withdraw(tenant, actor, id, ifMatch, reason));
              return ETags.ok(toDto(tenant, v), v.complaint().rowVersion());
            });
  }

  /** OPA với người đã tham gia hồ sơ (ràng buộc 21.1); không có OPA thì chỉ kiểm ở dịch vụ. */
  private void checkHandler(TenantId tenant, UUID id) {
    ComplaintGuard guard = guards.getIfAvailable();
    if (guard != null) {
      guard.handle(handling().authz(tenant, id));
    }
  }

  // ---------------------------------------------------------------- ánh xạ

  private static Draft draft(ComplaintInput in) {
    if (in == null) {
      throw invalid("profileId", "Thiếu nội dung khiếu nại.");
    }
    List<vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget> targets = new ArrayList<>();
    if (in.getTargets() != null) {
      for (ComplaintTarget t : in.getTargets()) {
        if (t == null || t.getKey() == null || t.getStage() == null) {
          throw invalid("targets", "Cấu phần bị khiếu nại không hợp lệ.");
        }
        targets.add(
            new vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget(
                t.getKey(), ComplaintStage.valueOf(t.getStage().getValue()), t.getLabel()));
      }
    }
    return new Draft(
        in.getProfileId(),
        in.getKind() == null ? null : ComplaintKind.valueOf(in.getKind().getValue()),
        in.getTargetStage() == null ? null : ComplaintStage.valueOf(in.getTargetStage().getValue()),
        targets,
        in.getSubjectLine(),
        in.getContent(),
        in.getRequestText(),
        in.getAttachmentObjectIds(),
        Boolean.TRUE.equals(in.getReserveOpinion()));
  }

  private static ComplainableProfile toDto(Eligible e) {
    ComplaintProfilesApi.Profile p = e.profile();
    ComplainableProfile dto =
        new ComplainableProfile()
            .profileId(p.id())
            .period(period(p.periodId(), e.period()))
            .complaintDeadline(e.deadline())
            .workingDaysLeft(e.workingDaysLeft());
    if (p.ratingCode() != null) {
      dto.rating(RatingLevel.fromValue(p.ratingCode()));
    }
    dto.setScore(p.score());
    dto.setPublishedAt(odt(p.publishedAt()));
    dto.setAllowedKinds(
        e.allowedKinds().stream()
            .map(k -> vn.viettel.okrkpi.contract.oversight.model.ComplaintKind.fromValue(k.name()))
            .toList());
    List<ComplaintTarget> targets = new ArrayList<>();
    for (ComplaintProfilesApi.Target t : p.targets()) {
      targets.add(
          new ComplaintTarget()
              .key(t.key())
              .stage(ComplaintTargetStage.fromValue(t.stage()))
              .label(t.label()));
    }
    dto.setTargets(targets);
    return dto;
  }

  private Complaint toDto(TenantId tenant, View v) {
    var c = v.complaint();
    Set<UUID> people = new HashSet<>(Set.of(c.complainantId()));
    if (c.handling().handlerId() != null) {
      people.add(c.handling().handlerId());
    }
    Map<UUID, String> names = names(tenant, people.stream());
    Complaint dto =
        new Complaint()
            .id(c.id())
            .code(c.code())
            .complainant(ref(c.complainantId(), names))
            .subjectLine(c.subjectLine())
            .kind(
                vn.viettel.okrkpi.contract.oversight.model.ComplaintKind.fromValue(c.kind().name()))
            .targetStage(ComplaintTargetStage.fromValue(c.targetStage().name()))
            .profileId(c.profileId())
            .period(period(c.periodId(), v.period()))
            .reserveOpinion(c.reserveOpinion())
            .rowVersion(c.rowVersion());
    dto.setStatus(status(c.status()));
    dto.setFiledAt(odt(c.filedAt()));
    dto.setResponseDeadline(c.responseDeadline());
    dto.setWorkingDaysLeft(v.workingDaysLeft());
    dto.setTargets(targets(c));
    dto.setContent(v.content());
    dto.setRequestText(v.requestText());
    dto.setAttachmentObjectIds(new ArrayList<>(c.attachmentIds()));
    if (c.handlerUnitId() != null) {
      AccessDirectory dir = directory.getIfAvailable();
      Map<UUID, String> units =
          dir == null ? Map.of() : dir.unitNames(tenant, List.of(c.handlerUnitId()));
      dto.setHandlerUnit(ref(c.handlerUnitId(), units));
    }
    if (c.handling().handlerId() != null) {
      dto.setHandler(ref(c.handling().handlerId(), names));
    }
    dto.setTimeline(timeline(c, names));
    return dto;
  }

  /**
   * Dòng thời gian từ các mốc đã lưu trên khiếu nại (gửi, tiếp nhận hoặc không thụ lý, rút, đóng);
   * mốc giải trình, kết luận đọc thêm từ sổ cái ở CORE-07-04 (suy luận).
   */
  private static List<ComplaintTimelineItem> timeline(
      vn.viettel.okrkpi.oversight.domain.complaint.Complaint c, Map<UUID, String> names) {
    List<ComplaintTimelineItem> out = new ArrayList<>();
    if (c.filedAt() != null) {
      out.add(
          new ComplaintTimelineItem()
              .at(odt(c.filedAt()))
              .action("Gửi khiếu nại")
              .actor(ref(c.complainantId(), names)));
    }
    var h = c.handling();
    ComplaintCloseReason code = h.closeReasonCode();
    if (h.acceptedAt() != null) {
      boolean rejected = code != null && code.rejection();
      out.add(
          new ComplaintTimelineItem()
              .at(odt(h.acceptedAt()))
              .action(rejected ? "Không thụ lý: " + h.closeReason() : "Tiếp nhận")
              .actor(ref(h.handlerId(), names)));
    }
    if (code == ComplaintCloseReason.RUT && h.closedAt() != null) {
      out.add(
          new ComplaintTimelineItem()
              .at(odt(h.closedAt()))
              .action("Rút khiếu nại: " + h.closeReason())
              .actor(ref(c.complainantId(), names)));
    }
    if (c.status() == ComplaintStatus.DONG && h.closedAt() != null) {
      out.add(new ComplaintTimelineItem().at(odt(h.closedAt())).action("Đóng khiếu nại"));
    }
    return out;
  }

  private static ComplaintSummary summary(
      vn.viettel.okrkpi.oversight.domain.complaint.Complaint c,
      Map<UUID, String> names,
      Integer daysLeft) {
    ComplaintSummary s =
        new ComplaintSummary()
            .id(c.id())
            .code(c.code())
            .complainant(ref(c.complainantId(), names))
            .subjectLine(c.subjectLine())
            .kind(
                vn.viettel.okrkpi.contract.oversight.model.ComplaintKind.fromValue(c.kind().name()))
            .targetStage(ComplaintTargetStage.fromValue(c.targetStage().name()));
    s.setStatus(status(c.status()));
    s.setFiledAt(odt(c.filedAt()));
    s.setResponseDeadline(c.responseDeadline());
    s.setWorkingDaysLeft(daysLeft);
    return s;
  }

  private static List<ComplaintTarget> targets(
      vn.viettel.okrkpi.oversight.domain.complaint.Complaint c) {
    List<ComplaintTarget> out = new ArrayList<>();
    for (var t : c.targets()) {
      out.add(
          new ComplaintTarget()
              .key(t.key())
              .stage(ComplaintTargetStage.fromValue(t.stage().name()))
              .label(t.label()));
    }
    return out;
  }

  private static vn.viettel.okrkpi.contract.oversight.model.ComplaintStatus status(
      ComplaintStatus s) {
    return s == null
        ? null
        : vn.viettel.okrkpi.contract.oversight.model.ComplaintStatus.fromValue(s.name());
  }

  private static Set<ComplaintStatus> statuses(List<String> raw) {
    Set<ComplaintStatus> out = new HashSet<>();
    if (raw == null) {
      return out;
    }
    for (String part : raw) {
      for (String s : part.split(",")) {
        if (s.isBlank()) {
          continue;
        }
        try {
          out.add(ComplaintStatus.valueOf(s.strip()));
        } catch (IllegalArgumentException e) {
          throw invalid("status", "Trạng thái lọc không hợp lệ.");
        }
      }
    }
    return out;
  }

  private static ComplaintKind kind(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return ComplaintKind.valueOf(raw.strip());
    } catch (IllegalArgumentException e) {
      throw invalid("kind", "Hình thức lọc không hợp lệ.");
    }
  }

  private Map<UUID, String> names(TenantId tenant, java.util.stream.Stream<UUID> ids) {
    AccessDirectory dir = directory.getIfAvailable();
    Set<UUID> people = new HashSet<>();
    ids.forEach(people::add);
    return dir == null || people.isEmpty() ? Map.of() : dir.personNames(tenant, people);
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
          caller.currentUserId().orElseThrow(ComplaintController::unauthorized),
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

  private ComplaintHandlingService handling() {
    ComplaintHandlingService h = handling.getIfAvailable();
    if (h == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return h;
  }

  private ComplaintService service() {
    ComplaintService s = service.getIfAvailable();
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

  private static <T> T guarded(Supplier<T> action) {
    try {
      return action.get();
    } catch (ComplaintService.NotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (ComplaintService.SigningUnavailableException
        | ComplaintService.SealingUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
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
