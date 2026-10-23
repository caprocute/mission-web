package vn.viettel.okrkpi.oversight.application.complaint;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.NotFoundException;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.View;
import vn.viettel.okrkpi.oversight.application.port.ComplaintEvents;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlerDirectory;
import vn.viettel.okrkpi.oversight.application.port.ComplaintPartyDirectory;
import vn.viettel.okrkpi.oversight.application.port.ComplaintProcessRepository;
import vn.viettel.okrkpi.oversight.application.port.ComplaintProcessRepository.ResponseAfter;
import vn.viettel.okrkpi.oversight.application.port.ComplaintReopener;
import vn.viettel.okrkpi.oversight.application.port.ComplaintRepository;
import vn.viettel.okrkpi.oversight.application.port.ComplaintResponseSigner;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintAdjustment;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintConclusion;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintHandlerPolicy;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRecordKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResolution;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResponse;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRules;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget;
import vn.viettel.okrkpi.oversight.domain.complaint.ExplanationParty;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Giải trình, kết luận và văn bản trả lời khiếu nại (CORE-07-04, QT-12 bước 5 đến 10, SCR-1202).
 * Người xử lý đã tiếp nhận yêu cầu các bên giải trình có hạn, ghi kết luận (kiểm lại tách vai trò
 * BR-12-04); kết luận có điều chỉnh trên kỳ đã khoá tạo yêu cầu mở lại kỳ (BR-12-07) thay vì sửa dữ
 * liệu. Người có thẩm quyền ký văn bản trả lời ở SVC-10 (202); EVT-18 chuyển {@code DA_TRA_LOI},
 * rồi {@code DANG_THI_HANH} khi có điều chỉnh, giữ nguyên thì hồ sơ đánh giá về {@code DA_KY_SO}.
 * Tính thử (BR-12-08) thuộc SVC-07 và timer nhắc, chuyển cấp thuộc SVC-06 (wiki trang 14).
 */
public class ComplaintResolutionService {

  static final String TX = ComplaintService.TX;
  static final String ROLE_APPRAISER = "appraiser";
  static final String ROLE_DECIDER = "decider";
  static final String ROLE_MANAGER = "manager";
  static final String COLUMN_RESPONSE = "oversight.complaint_response.response_enc";
  static final String COLUMN_CONCLUSION = "oversight.complaint.conclusion_enc";
  static final String ACTION_REQUEST = "YEU_CAU_GIAI_TRINH_KHIEU_NAI";
  static final String ACTION_EXPLAIN = "GIAI_TRINH_KHIEU_NAI";
  static final String ACTION_CONCLUDE = "KET_LUAN_KHIEU_NAI";
  static final String ACTION_RESPOND = "KY_TRA_LOI_KHIEU_NAI";
  static final String ACTION_VIEW = "XEM_TRA_LOI_KHIEU_NAI";
  static final String PURPOSE_HANDLE = "XU_LY_KHIEU_NAI";
  static final String TEMPLATE_REQUESTED = "KHIEU_NAI_YEU_CAU_GIAI_TRINH";
  static final String TEMPLATE_WAITING = "KHIEU_NAI_CHO_KET_LUAN";
  static final String TEMPLATE_RESPONDED = "KHIEU_NAI_DA_TRA_LOI";
  static final String SEPARATION_OF_DUTIES = "OVERSIGHT-403-SEPARATION-OF-DUTIES";
  static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";
  static final String CONCLUSION_MISSING = "OVERSIGHT-422-CONCLUSION-MISSING";
  static final int MAX_PARTIES = 20;
  static final int CONTENT_MAX = 8000;
  static final int RESPONSE_TEXT_MAX = 16000;
  static final int DOC_REF_MAX = 200;
  static final int ADJUSTMENT_REASON_MAX = 1000;
  static final int MAX_ATTACHMENTS = 20;

  private final ComplaintRepository complaints;
  private final ComplaintProcessRepository process;
  private final ComplaintService filing;
  private final ComplaintProfilesApi profiles;
  private final ComplaintHandlerDirectory directory;
  private final ComplaintPartyDirectory parties;
  private final WorkingDays days;
  private final ComplaintSealer sealer;
  private final ComplaintResponseSigner signer;
  private final ComplaintReopener reopener;
  private final ComplaintEvents events;
  private final OversightAuditLog audit;
  private final Clock clock;

  public ComplaintResolutionService(
      ComplaintRepository complaints,
      ComplaintProcessRepository process,
      ComplaintService filing,
      ComplaintProfilesApi profiles,
      ComplaintHandlerDirectory directory,
      ComplaintPartyDirectory parties,
      WorkingCalendarApi calendar,
      ComplaintSealer sealer,
      ComplaintResponseSigner signer,
      ComplaintReopener reopener,
      ComplaintEvents events,
      OversightAuditLog audit,
      Clock clock) {
    this.complaints = Objects.requireNonNull(complaints, "complaints");
    this.process = Objects.requireNonNull(process, "process");
    this.filing = Objects.requireNonNull(filing, "filing");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.parties = Objects.requireNonNull(parties, "parties");
    this.days = new WorkingDays(calendar);
    this.sealer = sealer;
    this.signer = signer;
    this.reopener = reopener;
    this.events = Objects.requireNonNull(events, "events");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Khiếu nại kèm kết luận (có thể {@code null}). */
  public record Resolved(View view, ComplaintConclusion conclusion) {

    /** Kỳ đã khoá: điều chỉnh phải qua mở lại kỳ (MSG-12-07). */
    public boolean periodLocked() {
      return view.period() != null && view.period().status() == PeriodStatus.DA_KHOA;
    }
  }

  /** Bản ghi trả lời kèm nội dung đã mở cho người có quyền ({@code null} khi không được đọc). */
  public record Record(ComplaintResponse response, String content) {}

  /** Tài liệu cần ký: người ký, băm SHA-256 của văn bản trả lời, mã hiển thị. */
  public record ResponseSource(UUID complaintId, UUID signer, byte[] sha256, String ref) {}

  /** Tác vụ ký văn bản trả lời. */
  public record ResponseJob(
      UUID complaintId, UUID responderId, boolean responded, Instant respondedAt) {}

  /** Nội dung điều chỉnh đề nghị khi ghi kết luận. */
  public record ConclusionInput(
      ComplaintResolution resolution,
      List<ComplaintAdjustment> adjustments,
      String responseText,
      String responseDocRef,
      String noExplanationNote) {
    public ConclusionInput {
      adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
    }
  }

  // ---------------------------------------------------------------- giải trình

  /**
   * {@code requestComplaintExplanations}: mỗi bên một bản ghi {@code YEU_CAU}, hạn mặc định 3 ngày
   * làm việc; khiếu nại sang (hoặc ở lại) {@code DANG_GIAI_TRINH}, EVT-21 cho từng bên.
   */
  @Transactional(TX)
  public Resolved requestExplanations(
      TenantId tenant,
      Actor actor,
      UUID id,
      String ifMatch,
      List<ExplanationParty> requested,
      String content,
      LocalDate dueDate) {
    Complaint c = lockFiled(tenant, id);
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    if (c.status() != ComplaintStatus.DA_TIEP_NHAN
        && c.status() != ComplaintStatus.DANG_GIAI_TRINH) {
      throw invalidState("Chỉ yêu cầu giải trình sau khi đã tiếp nhận và trước khi kết luận.");
    }
    requireHandler(tenant, actor, c);
    List<ProblemFieldError> errors = new ArrayList<>();
    Set<ExplanationParty> unique = new LinkedHashSet<>(requested == null ? List.of() : requested);
    unique.remove(null);
    if (unique.isEmpty() || unique.size() > MAX_PARTIES) {
      errors.add(field("parties", "Chọn từ 1 đến 20 bên được yêu cầu giải trình."));
    }
    if (unique.stream().anyMatch(p -> p.id().equals(c.complainantId()))) {
      errors.add(field("parties", "Không yêu cầu chính người khiếu nại giải trình."));
    }
    String text = strip(content);
    if (ComplaintRules.length(text) < 1 || ComplaintRules.length(text) > CONTENT_MAX) {
      errors.add(field("content", "Nội dung yêu cầu cần từ 1 đến 8.000 ký tự."));
    }
    LocalDate today = today();
    LocalDate due =
        dueDate == null
            ? days.add(tenant, today, ComplaintHandlerRulesDefaults.EXPLANATION_WORKING_DAYS)
            : dueDate;
    if (!due.isAfter(today)) {
      errors.add(field("dueDate", "Hạn giải trình phải sau ngày hôm nay."));
    }
    if (c.responseDeadline() != null && due.isAfter(c.responseDeadline())) {
      errors.add(field("dueDate", "Hạn giải trình không được sau hạn trả lời khiếu nại."));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Yêu cầu giải trình chưa hợp lệ.", List.copyOf(errors));
    }
    Instant now = clock.instant();
    Complaint next =
        c.status() == ComplaintStatus.DANG_GIAI_TRINH
            ? bump(c)
            : c.moved(ComplaintStatus.DANG_GIAI_TRINH);
    save(tenant, next, c.rowVersion(), actor.userId());
    for (ExplanationParty p : unique) {
      UUID rid = Ids.newUuidV7();
      ComplaintSealer.Sealed sealed = seal(tenant, COLUMN_RESPONSE, rid, text);
      process.insertResponse(
          tenant,
          new ComplaintResponse(
              rid,
              c.id(),
              c.periodId(),
              c.periodBucket(),
              ComplaintRecordKind.YEU_CAU,
              actor.userId(),
              p,
              null,
              due,
              sealed.cipher(),
              sealed.dekId(),
              List.of(),
              null,
              null,
              null,
              List.of(),
              now),
          actor.userId());
      events.stateChanged(
          tenant, next, TEMPLATE_REQUESTED, recipients(tenant, p), Map.of("han", due.toString()));
    }
    audit.record(
        tenant,
        actor.userId(),
        ACTION_REQUEST,
        ComplaintService.OBJECT_TYPE,
        id.toString(),
        null,
        ROLE_APPRAISER);
    return resolved(tenant, next, false);
  }

  /**
   * {@code submitComplaintExplanation}: bên được yêu cầu trả lời trước hạn; khi mọi yêu cầu đã trả
   * lời hoặc quá hạn thì khiếu nại sang {@code CHO_KET_LUAN} và báo người xử lý.
   */
  @Transactional(TX)
  public Record submitExplanation(
      TenantId tenant,
      Actor actor,
      UUID id,
      String ifMatch,
      UUID requestId,
      String content,
      List<UUID> attachments) {
    Complaint c = lockFiled(tenant, id);
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    ComplaintResponse request =
        requestId == null
            ? null
            : process
                .response(tenant, c, requestId)
                .filter(r -> r.kind() == ComplaintRecordKind.YEU_CAU)
                .orElse(null);
    if (request == null || !isParty(tenant, actor, request.party())) {
      throw new NotFoundException();
    }
    if (c.status() != ComplaintStatus.DANG_GIAI_TRINH) {
      throw invalidState("Khiếu nại không còn ở bước giải trình.");
    }
    LocalDate today = today();
    if (today.isAfter(request.dueDate())) {
      throw invalidState("Đã quá hạn giải trình.");
    }
    List<ComplaintResponse> answers =
        process.responses(tenant, c, ComplaintRecordKind.GIAI_TRINH, null, MAX_PARTIES * 10);
    if (answers.stream().anyMatch(a -> requestId.equals(a.requestId()))) {
      throw invalidState("Yêu cầu này đã được giải trình.");
    }
    String text = strip(content);
    List<UUID> files =
        attachments == null ? List.of() : List.copyOf(new LinkedHashSet<>(attachments));
    if (ComplaintRules.length(text) < 1 || ComplaintRules.length(text) > CONTENT_MAX) {
      throw invalid("content", "Nội dung giải trình cần từ 1 đến 8.000 ký tự.");
    }
    if (files.size() > MAX_ATTACHMENTS) {
      throw invalid("attachmentObjectIds", "Đính kèm tối đa 20 tệp.");
    }
    UUID rid = Ids.newUuidV7();
    ComplaintSealer.Sealed sealed = seal(tenant, COLUMN_RESPONSE, rid, text);
    ComplaintResponse answer =
        new ComplaintResponse(
            rid,
            c.id(),
            c.periodId(),
            c.periodBucket(),
            ComplaintRecordKind.GIAI_TRINH,
            actor.userId(),
            request.party(),
            requestId,
            null,
            sealed.cipher(),
            sealed.dekId(),
            files,
            null,
            null,
            null,
            List.of(),
            clock.instant());
    process.insertResponse(tenant, answer, actor.userId());
    audit.record(
        tenant,
        actor.userId(),
        ACTION_EXPLAIN,
        ComplaintService.OBJECT_TYPE,
        id.toString(),
        null,
        request.party().kind() == ExplanationParty.Kind.DON_VI ? ROLE_MANAGER : ROLE_APPRAISER);
    List<ComplaintResponse> settledAnswers = new ArrayList<>(answers);
    settledAnswers.add(answer);
    if (settled(tenant, c, settledAnswers, today)) {
      toConclusion(tenant, c, actor.userId());
    } else {
      // Ghi giải trình không đổi trạng thái nhưng vẫn tăng phiên bản để If-Match của bên sau mới.
      save(tenant, bump(c), c.rowVersion(), actor.userId());
    }
    return new Record(answer, text);
  }

  /**
   * Tác vụ hẹn giờ: khiếu nại {@code DANG_GIAI_TRINH} mà mọi yêu cầu đã trả lời hoặc quá hạn thì
   * sang {@code CHO_KET_LUAN} (QT-12 "Ngoại lệ": bên giải trình quá hạn vẫn chuyển).
   *
   * @return số khiếu nại đã chuyển
   */
  @Transactional(TX)
  public int settleExplanations(TenantId tenant, int limit) {
    int moved = 0;
    for (UUID id : process.explanationsSettled(tenant, today(), limit)) {
      Optional<Complaint> c = complaints.lock(tenant, id);
      if (c.isPresent() && c.get().status() == ComplaintStatus.DANG_GIAI_TRINH) {
        toConclusion(tenant, c.get(), c.get().handling().handlerId());
        moved++;
      }
    }
    return moved;
  }

  /**
   * {@code listComplaintResponses}: người khiếu nại thấy kết luận và bảo lưu; bên được yêu cầu thấy
   * yêu cầu và giải trình của mình; người xử lý, người ký trong phạm vi thấy tất cả. Nội dung C2
   * chỉ mở cho người không xung đột với hồ sơ, mỗi lần mở của cán bộ ghi nhật ký (BR-12-10).
   */
  @Transactional(TX)
  public List<Record> responses(
      TenantId tenant,
      Actor actor,
      UUID id,
      ComplaintRecordKind kind,
      ResponseAfter after,
      int limit) {
    Complaint c = complaints.find(tenant, id).orElseThrow(NotFoundException::new);
    if (c.draft()) {
      throw new NotFoundException();
    }
    boolean owner = c.complainantId().equals(actor.userId());
    boolean staff = staffInScope(tenant, actor, c);
    boolean party = !owner && !staff && partyOf(tenant, actor, c);
    if (!owner && !staff && !party) {
      throw new NotFoundException();
    }
    boolean conflicted =
        staff
            && !ComplaintHandlerPolicy.conflicts(actor.userId(), directory.participants(tenant, c))
                .isEmpty();
    List<Record> out = new ArrayList<>();
    boolean opened = false;
    for (ComplaintResponse r : process.responses(tenant, c, kind, after, limit)) {
      boolean visible =
          staff
              || (owner
                  && (r.kind() == ComplaintRecordKind.KET_LUAN
                      || r.kind() == ComplaintRecordKind.BAO_LUU))
              || (party && r.party() != null && isParty(tenant, actor, r.party()));
      if (!visible) {
        continue;
      }
      boolean read = !conflicted;
      out.add(new Record(r, read ? open(tenant, COLUMN_RESPONSE, r.id(), r.contentEnc()) : null));
      opened |= read && !owner;
    }
    if (opened) {
      audit.record(
          tenant,
          actor.userId(),
          ACTION_VIEW,
          ComplaintService.OBJECT_TYPE,
          id.toString(),
          PURPOSE_HANDLE,
          primaryRole(actor));
    }
    return out;
  }

  // ---------------------------------------------------------------- kết luận

  /** Khiếu nại kèm kết luận, cho {@code getComplaint} và các lệnh ghi. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<ComplaintConclusion> conclusionOf(TenantId tenant, Complaint c) {
    return c.draft() ? Optional.empty() : process.conclusion(tenant, c);
  }

  /**
   * {@code saveComplaintConclusion}: người xử lý đã tiếp nhận ghi kết quả, điều chỉnh và văn bản
   * trả lời (C2). Từ {@code DA_TIEP_NHAN} bắt buộc ghi chú không qua giải trình và chuyển {@code
   * CHO_KET_LUAN}. Có điều chỉnh trên kỳ {@code DA_KHOA} thì tạo yêu cầu mở lại kỳ (một lần).
   */
  @Transactional(TX)
  public Resolved saveConclusion(
      TenantId tenant, Actor actor, UUID id, String ifMatch, ConclusionInput in) {
    Complaint c = lockFiled(tenant, id);
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    if (c.status() != ComplaintStatus.DA_TIEP_NHAN && c.status() != ComplaintStatus.CHO_KET_LUAN) {
      throw invalidState("Chỉ ghi kết luận khi khiếu nại đã tiếp nhận hoặc đang chờ kết luận.");
    }
    requireHandler(tenant, actor, c);
    Optional<ComplaintConclusion> before = process.conclusion(tenant, c);
    if (before.isPresent() && before.get().signing()) {
      throw invalidState("Văn bản trả lời đang chờ ký, không sửa kết luận được nữa.");
    }
    boolean skipped = c.status() == ComplaintStatus.DA_TIEP_NHAN;
    validate(c, in, skipped);
    Complaint next = skipped ? c.moved(ComplaintStatus.CHO_KET_LUAN) : bump(c);
    save(tenant, next, c.rowVersion(), actor.userId());
    ComplaintSealer.Sealed sealed =
        seal(tenant, COLUMN_CONCLUSION, c.id(), strip(in.responseText()));
    ComplaintConclusion k =
        new ComplaintConclusion(
            in.resolution(),
            in.resolution().adjusts() ? in.adjustments() : List.of(),
            sealed.cipher(),
            sealed.dekId(),
            blankToNull(in.responseDocRef()),
            skipped ? strip(in.noExplanationNote()) : null,
            actor.userId(),
            clock.instant(),
            before.map(ComplaintConclusion::reopenRequestId).orElse(null),
            null,
            null);
    View view = filing.view(tenant, next, false);
    Resolved r = new Resolved(view, k);
    if (in.resolution().adjusts() && r.periodLocked() && k.reopenRequestId() == null) {
      if (reopener == null) {
        throw new ComplaintService.SigningUnavailableException();
      }
      k = k.withReopenRequest(reopener.open(tenant, actor.userId(), next, k));
    }
    process.saveConclusion(tenant, next, k);
    audit.record(
        tenant,
        actor.userId(),
        ACTION_CONCLUDE,
        ComplaintService.OBJECT_TYPE,
        id.toString(),
        null,
        ROLE_APPRAISER);
    return new Resolved(view, k);
  }

  /**
   * {@code respondComplaint}: người có thẩm quyền ({@code decider}, bước lên AAL2, không tham gia
   * hồ sơ) mở yêu cầu ký văn bản trả lời ở SVC-10 và ghi bản ghi {@code KET_LUAN}.
   *
   * @return mã yêu cầu ký
   */
  @Transactional(TX)
  public UUID respond(
      TenantId tenant,
      Actor actor,
      UUID id,
      String ifMatch,
      String idempotencyKey,
      String authorization,
      String comment) {
    requireStepUp(actor);
    Complaint c = lockFiled(tenant, id);
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    if (!actor.roles().contains(ROLE_DECIDER) || !staffInScope(tenant, actor, c)) {
      throw outOfScope();
    }
    requireNoConflict(tenant, actor, c);
    if (actor.userId().equals(c.handling().handlerId())) {
      throw new BusinessException(
          SEPARATION_OF_DUTIES,
          "Người xử lý khiếu nại không đồng thời ký văn bản trả lời (PL 4 V.1).");
    }
    if (c.status() != ComplaintStatus.CHO_KET_LUAN) {
      throw invalidState("Khiếu nại chưa ở bước chờ kết luận.");
    }
    if (comment != null && comment.length() > ComplaintRules.NOTE_MAX) {
      throw invalid("comment", "Ý kiến người ký tối đa 2.000 ký tự.");
    }
    ComplaintConclusion k =
        process
            .conclusion(tenant, c)
            .orElseThrow(
                () ->
                    new BusinessException(
                        CONCLUSION_MISSING, "Chưa có kết luận hoặc văn bản trả lời để ký."));
    if (k.signing()) {
      return k.signRequestId();
    }
    if (signer == null) {
      throw new ComplaintService.SigningUnavailableException();
    }
    Complaint next = bump(c);
    save(tenant, next, c.rowVersion(), actor.userId());
    UUID sign = signer.request(tenant, c.id(), idempotencyKey, authorization);
    String text = open(tenant, COLUMN_CONCLUSION, c.id(), k.textEnc());
    UUID rid = Ids.newUuidV7();
    ComplaintSealer.Sealed sealed = seal(tenant, COLUMN_RESPONSE, rid, text);
    process.insertResponse(
        tenant,
        new ComplaintResponse(
            rid,
            c.id(),
            c.periodId(),
            c.periodBucket(),
            ComplaintRecordKind.KET_LUAN,
            actor.userId(),
            null,
            null,
            null,
            sealed.cipher(),
            sealed.dekId(),
            List.of(),
            k.docRef(),
            sign,
            k.resolution(),
            k.adjustments(),
            clock.instant()),
        actor.userId());
    process.saveConclusion(tenant, next, k.withSignRequest(sign));
    audit.record(
        tenant,
        actor.userId(),
        ACTION_RESPOND,
        ComplaintService.OBJECT_TYPE,
        id.toString(),
        null,
        ROLE_DECIDER);
    return sign;
  }

  /**
   * EVT-18 của văn bản trả lời: {@code CHO_KET_LUAN} → {@code DA_TRA_LOI}, báo người khiếu nại
   * (MSG-12-04); có điều chỉnh thì {@code DANG_THI_HANH}, giữ nguyên thì hồ sơ về {@code DA_KY_SO}.
   * Lặp lại hoặc khác yêu cầu ký thì bỏ qua.
   *
   * @return {@code true} khi vừa chuyển
   */
  @Transactional(TX)
  public boolean onResponseSigned(TenantId tenant, UUID signRequestId, Instant signedAt) {
    Optional<UUID> id = process.byResponseSignRequest(tenant, signRequestId);
    if (id.isEmpty()) {
      return false;
    }
    Complaint c = complaints.lock(tenant, id.get()).orElse(null);
    if (c == null || c.status() != ComplaintStatus.CHO_KET_LUAN) {
      return false;
    }
    ComplaintConclusion k = process.conclusion(tenant, c).orElse(null);
    if (k == null || !signRequestId.equals(k.signRequestId()) || k.respondedAt() != null) {
      return false;
    }
    Instant at = signedAt == null ? clock.instant() : signedAt;
    UUID handler = c.handling().handlerId();
    if (!k.resolution().adjusts()
        && c.kind().reopensProfile()
        && !complaints.otherOpenReopening(tenant, c.profileId(), c.id())) {
      profiles.returnToSigned(tenant, c.profileId(), handler, "Giữ nguyên kết quả theo kết luận");
    }
    Complaint responded = c.moved(ComplaintStatus.DA_TRA_LOI);
    events.stateChanged(tenant, responded, TEMPLATE_RESPONDED, List.of(c.complainantId()));
    Complaint next =
        k.resolution().adjusts() ? responded.moved(ComplaintStatus.DANG_THI_HANH) : responded;
    save(tenant, next, c.rowVersion(), handler);
    process.saveConclusion(tenant, next, k.respondedAt(at));
    return true;
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<ResponseJob> responseJob(TenantId tenant, UUID signRequestId) {
    Optional<Complaint> found =
        process
            .byResponseSignRequest(tenant, signRequestId)
            .flatMap(id -> complaints.find(tenant, id));
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Complaint c = found.get();
    Optional<ComplaintResponse> signed = signedRecord(tenant, c, signRequestId);
    if (signed.isEmpty()) {
      return Optional.empty();
    }
    Instant at = process.conclusion(tenant, c).map(ComplaintConclusion::respondedAt).orElse(null);
    return Optional.of(new ResponseJob(c.id(), signed.get().responderId(), at != null, at));
  }

  /** Tài liệu cho SVC-10: băm mã khiếu nại, kết quả, điều chỉnh, số văn bản và bản mã nội dung. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<ResponseSource> responseSource(TenantId tenant, UUID id) {
    return complaints
        .find(tenant, id)
        .filter(c -> c.status() == ComplaintStatus.CHO_KET_LUAN)
        .flatMap(
            c ->
                process
                    .conclusion(tenant, c)
                    .filter(k -> k.signing() && k.respondedAt() == null)
                    .flatMap(
                        k ->
                            signedRecord(tenant, c, k.signRequestId())
                                .map(r -> source(c, r.responderId(), k))));
  }

  // ---------------------------------------------------------------- kiểm tra

  private void validate(Complaint c, ConclusionInput in, boolean skipped) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (in == null || in.resolution() == null) {
      throw invalid("outcome", "Chọn kết quả xử lý.");
    }
    String text = strip(in.responseText());
    if (ComplaintRules.length(text) < 1 || ComplaintRules.length(text) > RESPONSE_TEXT_MAX) {
      errors.add(field("responseText", "Nội dung văn bản trả lời cần từ 1 đến 16.000 ký tự."));
    }
    if (in.responseDocRef() != null && in.responseDocRef().length() > DOC_REF_MAX) {
      errors.add(field("responseDocRef", "Số văn bản tối đa 200 ký tự."));
    }
    String note = strip(in.noExplanationNote());
    if (skipped && (ComplaintRules.length(note) < 1)) {
      errors.add(field("noExplanationNote", "Ghi rõ lý do kết luận không qua giải trình."));
    }
    if (note != null && note.length() > ComplaintRules.NOTE_MAX) {
      errors.add(field("noExplanationNote", "Ghi chú tối đa 2.000 ký tự."));
    }
    if (in.resolution().adjusts()) {
      if (in.adjustments().isEmpty()) {
        errors.add(field("adjustments", "Nêu ít nhất một điều chỉnh đề nghị."));
      }
      Set<String> keys = new LinkedHashSet<>();
      for (ComplaintTarget t : c.targets()) {
        keys.add(t.key());
      }
      Set<String> seen = new LinkedHashSet<>();
      for (int i = 0; i < in.adjustments().size(); i++) {
        ComplaintAdjustment a = in.adjustments().get(i);
        String at = "adjustments[" + i + "]";
        if (!keys.isEmpty()
            && !keys.contains(a.targetKey())
            && !keys.contains(prefix(a.targetKey()))) {
          errors.add(field(at + ".targetKey", "Điều chỉnh phải thuộc cấu phần bị khiếu nại."));
        }
        if (!seen.add(a.targetKey())) {
          errors.add(field(at + ".targetKey", "Mỗi cấu phần chỉ điều chỉnh một lần."));
        }
        if (a.newValue() == null
            || (a.newValue() instanceof String s && s.isBlank())
            || (a.newValue() instanceof BigDecimal d && d.signum() < 0)) {
          errors.add(field(at + ".newValue", "Giá trị đề nghị không hợp lệ."));
        }
        if (a.newValue() != null && a.newValue().equals(a.oldValue())) {
          errors.add(field(at + ".newValue", "Giá trị đề nghị phải khác giá trị hiện tại."));
        }
        if (ComplaintRules.length(strip(a.reason())) < 1
            || a.reason().length() > ADJUSTMENT_REASON_MAX) {
          errors.add(field(at + ".reason", "Nêu căn cứ điều chỉnh (tối đa 1.000 ký tự)."));
        }
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Kết luận chưa hợp lệ.", List.copyOf(errors));
    }
  }

  /** Khoá cấu phần dạng {@code NV-THEN-CHOT-3.C}: phần trước dấu chấm cuối là cấu phần cha. */
  private static String prefix(String key) {
    int i = key == null ? -1 : key.lastIndexOf('.');
    return i <= 0 ? key : key.substring(0, i);
  }

  /** Người đã tiếp nhận, không tham gia hồ sơ (kiểm lại BR-12-04 vì người tham gia có thể đổi). */
  private void requireHandler(TenantId tenant, Actor actor, Complaint c) {
    if (!actor.roles().contains(ROLE_APPRAISER)
        || !actor.userId().equals(c.handling().handlerId())) {
      throw outOfScope();
    }
    requireNoConflict(tenant, actor, c);
  }

  private void requireNoConflict(TenantId tenant, Actor actor, Complaint c) {
    if (actor.userId().equals(c.complainantId())
        || !ComplaintHandlerPolicy.conflicts(actor.userId(), directory.participants(tenant, c))
            .isEmpty()) {
      throw new BusinessException(
          SEPARATION_OF_DUTIES,
          "Đồng chí không thể xử lý khiếu nại này vì đã tham gia đánh giá hồ sơ liên quan (PL 4 V.1).");
    }
  }

  private void requireStepUp(Actor actor) {
    boolean aal2 =
        StepUpRequiredException.REQUIRED_ACR.equals(AuthzSubjectResolver.normalizeAcr(actor.acr()));
    boolean fresh =
        actor.authTime() == null
            || clock.instant().getEpochSecond() - actor.authTime()
                <= ComplaintService.STEP_UP_MAX_AGE_SECONDS;
    if (!aal2 || !fresh) {
      throw new StepUpRequiredException(
          "respondComplaint",
          ComplaintService.STEP_UP_MAX_AGE_SECONDS,
          "Ký số văn bản trả lời cần xác thực nhiều lớp.");
    }
  }

  private boolean staffInScope(TenantId tenant, Actor actor, Complaint c) {
    if (actor.userId().equals(c.handling().handlerId())) {
      return true;
    }
    if (!ComplaintHandlingService.handlerRole(actor) || actor.unitId() == null) {
      return false;
    }
    if (actor.unitId().equals(c.handlerUnitId())) {
      return true;
    }
    return c.handlerUnitId() != null
        && directory.subunitsOf(tenant, actor.unitId()).contains(c.handlerUnitId());
  }

  private boolean partyOf(TenantId tenant, Actor actor, Complaint c) {
    return process
        .responses(tenant, c, ComplaintRecordKind.YEU_CAU, null, MAX_PARTIES * 10)
        .stream()
        .anyMatch(r -> isParty(tenant, actor, r.party()));
  }

  /** Bên người: đúng người đó; bên đơn vị: người giữ vai trò {@code manager} của đơn vị. */
  private boolean isParty(TenantId tenant, Actor actor, ExplanationParty p) {
    if (p == null) {
      return false;
    }
    if (p.kind() == ExplanationParty.Kind.NGUOI) {
      return p.id().equals(actor.userId());
    }
    return actor.roles().contains(ROLE_MANAGER)
        && parties.holders(tenant, ROLE_MANAGER, p.id(), clock.instant()).contains(actor.userId());
  }

  private List<UUID> recipients(TenantId tenant, ExplanationParty p) {
    return p.kind() == ExplanationParty.Kind.NGUOI
        ? List.of(p.id())
        : parties.holders(tenant, ROLE_MANAGER, p.id(), clock.instant());
  }

  /** Mọi yêu cầu đã có trả lời hoặc đã quá hạn. */
  private boolean settled(
      TenantId tenant, Complaint c, List<ComplaintResponse> answers, LocalDate today) {
    Set<UUID> answered = new LinkedHashSet<>();
    answers.forEach(a -> answered.add(a.requestId()));
    for (ComplaintResponse q :
        process.responses(tenant, c, ComplaintRecordKind.YEU_CAU, null, MAX_PARTIES * 10)) {
      if (!answered.contains(q.id()) && !today.isAfter(q.dueDate())) {
        return false;
      }
    }
    return true;
  }

  private void toConclusion(TenantId tenant, Complaint c, UUID actorId) {
    Complaint next = c.moved(ComplaintStatus.CHO_KET_LUAN);
    save(tenant, next, c.rowVersion(), actorId);
    UUID handler = c.handling().handlerId();
    events.stateChanged(
        tenant, next, TEMPLATE_WAITING, handler == null ? List.of() : List.of(handler));
  }

  private Resolved resolved(TenantId tenant, Complaint c, boolean withContent) {
    return new Resolved(
        filing.view(tenant, c, withContent), process.conclusion(tenant, c).orElse(null));
  }

  /** Bản ghi {@code KET_LUAN} của lần ký, mang người ký. */
  private Optional<ComplaintResponse> signedRecord(
      TenantId tenant, Complaint c, UUID signRequestId) {
    return process.responses(tenant, c, ComplaintRecordKind.KET_LUAN, null, MAX_PARTIES).stream()
        .filter(r -> signRequestId.equals(r.signRequestId()))
        .findFirst();
  }

  private ResponseSource source(Complaint c, UUID signer, ComplaintConclusion k) {
    StringBuilder b =
        new StringBuilder(c.id().toString())
            .append('|')
            .append(k.resolution().name())
            .append('|')
            .append(k.docRef() == null ? "" : k.docRef());
    for (ComplaintAdjustment a : k.adjustments()) {
      b.append('|').append(a.targetKey()).append('=').append(a.newValue());
    }
    byte[] head = b.append('|').toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      md.update(head);
      md.update(k.textEnc());
      return new ResponseSource(c.id(), signer, md.digest(), c.code());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Không đổi trạng thái, chỉ tăng {@code row_version}. */
  private static Complaint bump(Complaint c) {
    return c.moved(c.status());
  }

  private Complaint lockFiled(TenantId tenant, UUID id) {
    Complaint c = complaints.lock(tenant, id).orElseThrow(NotFoundException::new);
    if (c.draft()) {
      throw new NotFoundException();
    }
    return c;
  }

  private void save(TenantId tenant, Complaint changed, int expected, UUID actor) {
    if (!complaints.update(tenant, changed, expected, actor)) {
      throw new BusinessException(
          ComplaintService.VERSION_CONFLICT, "Khiếu nại đã được cập nhật ở nơi khác.");
    }
  }

  private ComplaintSealer.Sealed seal(TenantId tenant, String column, UUID rowId, String plain) {
    if (sealer == null) {
      throw new ComplaintService.SealingUnavailableException();
    }
    return sealer.seal(tenant, column, rowId, plain);
  }

  private String open(TenantId tenant, String column, UUID rowId, byte[] cipher) {
    if (cipher == null) {
      return null;
    }
    if (sealer == null) {
      throw new ComplaintService.SealingUnavailableException();
    }
    return sealer.open(tenant, column, rowId, cipher);
  }

  private LocalDate today() {
    return clock.instant().atZone(ComplaintService.ZONE).toLocalDate();
  }

  private static String primaryRole(Actor actor) {
    for (String r : List.of(ROLE_APPRAISER, ROLE_DECIDER, ROLE_MANAGER)) {
      if (actor.roles().contains(r)) {
        return r;
      }
    }
    return ComplaintService.ROLE_MEMBER;
  }

  private static String strip(String s) {
    return s == null ? null : s.strip();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static BusinessException invalidState(String message) {
    return new BusinessException(ComplaintService.INVALID_STATE, message);
  }

  private static BusinessException outOfScope() {
    return new BusinessException(OUT_OF_SCOPE, "Khiếu nại này không được giao cho đồng chí.");
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }

  private static BusinessException invalid(String name, String message) {
    return new BusinessException("COMMON-422-VALIDATION", message, List.of(field(name, message)));
  }

  /** Mặc định của QT-12 chưa có ở {@link ComplaintRules}. */
  static final class ComplaintHandlerRulesDefaults {
    static final int EXPLANATION_WORKING_DAYS = 3;

    private ComplaintHandlerRulesDefaults() {}
  }
}
