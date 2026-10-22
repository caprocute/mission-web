package vn.viettel.okrkpi.dossier.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.dossiers.model.DossierDeadlineCheck;
import vn.viettel.okrkpi.contract.dossiers.model.DossierMeeting;
import vn.viettel.okrkpi.contract.dossiers.model.DossierMeetingCreate;
import vn.viettel.okrkpi.contract.dossiers.model.DossierMeetingPage;
import vn.viettel.okrkpi.contract.dossiers.model.DossierMeetingParticipant;
import vn.viettel.okrkpi.contract.dossiers.model.DossierMeetingRole;
import vn.viettel.okrkpi.contract.dossiers.model.DossierMeetingStatus;
import vn.viettel.okrkpi.contract.dossiers.model.DossierMeetingSummary;
import vn.viettel.okrkpi.contract.dossiers.model.DossierMeetingType;
import vn.viettel.okrkpi.contract.dossiers.model.DossierParticipantStatus;
import vn.viettel.okrkpi.contract.dossiers.model.PeriodRef;
import vn.viettel.okrkpi.contract.dossiers.model.Ref;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService.MeetingView;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService.NewMeeting;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService.Patch;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.domain.meeting.DraftDeadline;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Hội nghị kiểm điểm của {@code dossiers.yaml} (CORE-06-02, QT-11, SCR-1101): {@code
 * listDossierMeetings}, {@code createDossierMeeting}, {@code getDossierMeeting}, {@code
 * updateDossierMeeting}, {@code sendDossierMeetingDraft}. Tên người, đơn vị chưa có bản sao trong
 * MOD-06 nên trả rỗng; dự thảo biểu mẫu (DV-11-02), mức đề xuất và biểu quyết (CORE-06-04) chưa có
 * dữ liệu.
 */
@RestController
@RequestMapping("/api/v1/dossiers/meetings")
public class DossierMeetingController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;

  private static final Map<String, String> TYPE_TITLES =
      Map.of(
          "KIEM_DIEM_QUY", "Hội nghị kiểm điểm",
          "KIEM_DIEM_NAM", "Hội nghị kiểm điểm cuối",
          "CHUYEN_DE", "Hội nghị kiểm điểm chuyên đề");

  private final ReviewMeetingService service;
  private final DossierCallers callers;
  private final ObjectProvider<PeriodApi> periods;

  public DossierMeetingController(
      ReviewMeetingService service,
      ObjectProvider<DossierCaller> callers,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodApi> periods) {
    this.service = Objects.requireNonNull(service, "service");
    this.callers = new DossierCallers(callers, subjects);
    this.periods = Objects.requireNonNull(periods, "periods");
  }

  @GetMapping
  @RequiresPermission(
      operationId = "listDossierMeetings",
      module = "dossiers",
      resourceType = "review_meeting")
  public ResponseEntity<DossierMeetingPage> listDossierMeetings(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    MeetingStatus filter = null;
    if (status != null) {
      try {
        filter = MeetingStatus.valueOf(status);
      } catch (IllegalArgumentException e) {
        throw invalid("status", "Trạng thái hội nghị không hợp lệ.");
      }
    }
    UUID actor = callers.actor();
    List<MeetingRow> rows =
        service.list(
            tenant,
            actor,
            callers.unit().orElse(null),
            period,
            unit,
            filter,
            decodeCursor(cursor),
            size + 1);
    boolean more = rows.size() > size;
    List<MeetingRow> page = more ? rows.subList(0, size) : rows;
    Map<UUID, PeriodRef> refs = new LinkedHashMap<>();
    List<DossierMeetingSummary> items = new ArrayList<>();
    for (MeetingRow r : page) {
      PeriodRef ref = refs.computeIfAbsent(r.periodId(), id -> periodRef(tenant, id));
      DossierMeetingSummary s =
          new DossierMeetingSummary(
              r.id(),
              DossierMeetingType.fromValue(r.meetingType()),
              new Ref(r.orgUnitId(), ""),
              ref,
              time(r.scheduledAt()),
              DossierMeetingStatus.fromValue(r.status().name()));
      s.setTitle(title(r.meetingType(), ref));
      s.setMyRole(role(r, actor));
      items.add(s);
    }
    String next = more ? encodeCursor(page.get(page.size() - 1).id()) : null;
    return ResponseEntity.ok(new DossierMeetingPage(items, next));
  }

  @PostMapping
  @Idempotent
  @RequiresPermission(
      operationId = "createDossierMeeting",
      module = "dossiers",
      resourceType = "review_meeting")
  public ResponseEntity<DossierMeeting> createDossierMeeting(
      @RequestBody DossierMeetingCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.actor();
    MeetingView view =
        service.create(
            tenant,
            actor,
            callers.unit().orElse(null),
            new NewMeeting(
                body.getPeriodId(),
                body.getOrgUnitId(),
                body.getMeetingType() == null ? null : body.getMeetingType().getValue(),
                body.getScheduledAt() == null ? null : body.getScheduledAt().toInstant(),
                body.getLocation(),
                body.getChairPersonId(),
                body.getSecretaryPersonId(),
                body.getSubjectPersonId(),
                body.getParticipantPersonIds()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/dossiers/meetings/" + view.row().id()))
        .eTag(ETags.of(view.row().rowVersion()))
        .body(meeting(tenant, view, actor));
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getDossierMeeting",
      module = "dossiers",
      resourceType = "review_meeting")
  public ResponseEntity<DossierMeeting> getDossierMeeting(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.actor();
    MeetingView view =
        service
            .get(tenant, actor, callers.unit().orElse(null), id)
            .orElseThrow(DossierMeetingController::notFound);
    return ETags.ok(meeting(tenant, view, actor), view.row().rowVersion());
  }

  @PatchMapping("/{id}")
  @Idempotent
  @RequiresPermission(
      operationId = "updateDossierMeeting",
      module = "dossiers",
      resourceType = "review_meeting")
  public ResponseEntity<DossierMeeting> updateDossierMeeting(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "DOSSIER");
    UUID actor = callers.actor();
    Patch patch = patch(body);
    MeetingView view;
    try {
      view = service.update(tenant, actor, callers.unit().orElse(null), id, expected, patch);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return ETags.ok(meeting(tenant, view, actor), view.row().rowVersion());
  }

  @PostMapping("/{id}/send-draft")
  @Idempotent
  @RequiresPermission(
      operationId = "sendDossierMeetingDraft",
      module = "dossiers",
      resourceType = "review_meeting")
  public ResponseEntity<DossierMeeting> sendDossierMeetingDraft(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "DOSSIER");
    UUID actor = callers.actor();
    MeetingView view;
    try {
      view = service.sendDraft(tenant, actor, id, expected);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return ETags.ok(meeting(tenant, view, actor), view.row().rowVersion());
  }

  /** JSON Merge Patch của {@code DossierMeetingUpdate}: trường không gửi giữ nguyên. */
  static Patch patch(JsonNode body) {
    if (body == null || !body.isObject() || body.isEmpty()) {
      throw invalid("body", "Chưa có thông tin cần sửa.");
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    Instant scheduledAt = null;
    JsonNode at = body.get("scheduledAt");
    if (at != null) {
      try {
        if (!at.isTextual()) {
          throw new DateTimeParseException("không phải chuỗi", at.toString(), 0);
        }
        scheduledAt = OffsetDateTime.parse(at.asText()).toInstant();
      } catch (DateTimeParseException e) {
        errors.add(new ProblemFieldError("scheduledAt", "Ngày giờ hội nghị không hợp lệ.", null));
      }
    }
    JsonNode location = body.get("location");
    if (location != null && !location.isTextual() && !location.isNull()) {
      errors.add(new ProblemFieldError("location", "Địa điểm không hợp lệ.", null));
    }
    List<UUID> participants = null;
    JsonNode people = body.get("participantPersonIds");
    if (people != null) {
      participants = new ArrayList<>();
      if (!people.isArray()) {
        errors.add(new ProblemFieldError("participantPersonIds", "Thành phần không hợp lệ.", null));
      } else {
        for (JsonNode p : people) {
          try {
            participants.add(UUID.fromString(p.asText()));
          } catch (IllegalArgumentException e) {
            errors.add(
                new ProblemFieldError("participantPersonIds", "Thành phần không hợp lệ.", null));
            break;
          }
        }
      }
    }
    JsonNode reason = body.get("deadlineOverrideReason");
    if (reason != null && !reason.isTextual()) {
      errors.add(
          new ProblemFieldError("deadlineOverrideReason", "Lý do giữ ngày không hợp lệ.", null));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "DOSSIER-422-VALIDATION", "Thông tin hội nghị kiểm điểm chưa hợp lệ.", errors);
    }
    if (scheduledAt == null && location == null && participants == null && reason == null) {
      throw invalid("body", "Chưa có thông tin cần sửa.");
    }
    return new Patch(
        scheduledAt,
        location != null,
        location == null || location.isNull() ? null : location.asText(),
        participants,
        reason == null ? null : reason.asText());
  }

  private DossierMeeting meeting(TenantId tenant, MeetingView view, UUID actor) {
    MeetingRow r = view.row();
    DraftDeadline.Result d = view.deadline();
    DossierDeadlineCheck check = new DossierDeadlineCheck(d.requiredWorkingDays(), d.passed());
    check.setWorkingDays(d.workingDays());
    check.setLegalRef(DraftDeadline.LEGAL_REF);
    check.setMsg(d.msg());
    check.setOverrideReason(r.deadlineOverrideReason());
    PeriodRef period = periodRef(tenant, r.periodId());
    DossierMeeting m =
        new DossierMeeting(
            r.id(),
            DossierMeetingType.fromValue(r.meetingType()),
            new Ref(r.orgUnitId(), ""),
            period,
            time(r.scheduledAt()),
            DossierMeetingStatus.fromValue(r.status().name()),
            new Ref(r.chairPersonId(), ""),
            new Ref(r.secretaryPersonId(), ""),
            check,
            (int) r.rowVersion());
    m.setTitle(title(r.meetingType(), period));
    m.setMyRole(role(r, actor));
    m.setLocation(r.location());
    m.setMeetingAt(time(r.meetingAt()));
    m.setSubjectPerson(r.subjectPersonId() == null ? null : new Ref(r.subjectPersonId(), ""));
    m.setDraftSentAt(time(r.draftSentAt()));
    List<DossierMeetingParticipant> participants = new ArrayList<>();
    for (Participant p : r.participants()) {
      DossierMeetingParticipant dp =
          new DossierMeetingParticipant(
              new Ref(p.personId(), ""), DossierMeetingRole.fromValue(p.role()));
      if (p.status() != null) {
        dp.setStatus(DossierParticipantStatus.fromValue(p.status()));
      }
      participants.add(dp);
    }
    m.setParticipants(participants);
    m.setDraftForms(new ArrayList<>());
    m.setVote(null);
    m.setSignRequestId(r.signRequestId());
    m.setMinutesSha256(
        r.minutesSha256() == null ? null : java.util.HexFormat.of().formatHex(r.minutesSha256()));
    m.setCommentsLocked(r.status().commentsLocked());
    return m;
  }

  /** Vai trò của người gọi; người chỉ xem theo quyền đơn vị thì rỗng. */
  static DossierMeetingRole role(MeetingRow r, UUID actor) {
    return r.participants().stream()
        .filter(p -> p.personId().equals(actor))
        .findFirst()
        .map(p -> DossierMeetingRole.fromValue(p.role()))
        .orElse(null);
  }

  /**
   * Tên hiển thị ghép từ loại hội nghị và tên kỳ (suy luận), ví dụ "Hội nghị kiểm điểm năm 2026".
   */
  static String title(String type, PeriodRef period) {
    String head = TYPE_TITLES.getOrDefault(type, "Hội nghị kiểm điểm");
    String label = period == null ? "" : period.getLabel();
    if (label == null || label.isBlank()) {
      return head;
    }
    return head + " " + Character.toLowerCase(label.charAt(0)) + label.substring(1);
  }

  private PeriodRef periodRef(TenantId tenant, UUID periodId) {
    PeriodApi api = periods.getIfAvailable();
    Optional<PeriodSnapshot> snapshot =
        api == null ? Optional.empty() : api.findById(tenant, PeriodId.of(periodId));
    return snapshot
        .map(s -> new PeriodRef(periodId, s.name(), s.startDate(), s.endDate()))
        .orElseGet(() -> new PeriodRef(periodId, "", null, null));
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atZone(ReviewMeetingService.ZONE).toOffsetDateTime();
  }

  static String encodeCursor(UUID last) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(last.toString().getBytes(StandardCharsets.US_ASCII));
  }

  static UUID decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException e) {
      throw invalid("cursor", "Con trỏ trang không hợp lệ.");
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
