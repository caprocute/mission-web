package vn.viettel.okrkpi.dossier.application.comment;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository.CommentRow;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository.Position;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Góp ý kiểm điểm của MOD-06 (CORE-06-03, QT-11 bước 6–7, SCR-1101 vùng 2, BR-11-06). Thành viên
 * góp ý trước hội nghị khi dự thảo đã gửi; chỉ thư ký ghi ý kiến tại hội nghị; góp ý khoá khi chủ
 * trì kết thúc hội nghị. Nội dung là C2, mã hoá trước khi ghi và giải mã cho người xem có quyền;
 * khi tenant bật ẩn danh ({@code dossier.comment.anonymous}), người được kiểm điểm đọc nội dung
 * nhưng không thấy người góp ý. Không có thao tác sửa hay xoá.
 */
public class ReviewCommentService {

  static final String TX = "dossierTransactionManager";
  static final int MAX_CONTENT = 4000;

  /** Khoá cấu hình tenant bật chế độ ẩn danh người góp ý (BR-11-06; tên khoá là suy luận). */
  public static final String ANONYMOUS_SETTING = "dossier.comment.anonymous";

  public static final String TRUOC_HOI_NGHI = "TRUOC_HOI_NGHI";
  public static final String HOI_NGHI = "HOI_NGHI";

  /** Nguồn góp ý của thành viên và thư ký ({@code comment_kind}). */
  static final String GOP_Y = "GOP_Y";

  private static final Set<String> KINDS = Set.of("UU_DIEM", "HAN_CHE", "KIEN_NGHI");
  private static final Set<String> PHASES = Set.of(TRUOC_HOI_NGHI, HOI_NGHI);

  private final ReviewMeetingRepository meetings;
  private final ReviewCommentRepository comments;
  private final CommentSealer sealer;
  private final PeriodApi periods;
  private final Supplier<TenantSettingApi> settings;
  private final Clock clock;

  /** Lệnh gửi góp ý ({@code DossierCommentCreate}). */
  public record Draft(UUID subjectPersonId, String kind, String phase, String content) {}

  /** Góp ý đã giải mã; {@code commenterHidden} khi người xem không được thấy người góp ý. */
  public record CommentView(CommentRow row, String content, boolean commenterHidden) {}

  /** Một trang góp ý và cờ ẩn danh của tenant. */
  public record CommentPage(boolean anonymous, List<CommentView> items) {}

  public ReviewCommentService(
      ReviewMeetingRepository meetings,
      ReviewCommentRepository comments,
      CommentSealer sealer,
      PeriodApi periods,
      Supplier<TenantSettingApi> settings,
      Clock clock) {
    this.meetings = Objects.requireNonNull(meetings, "meetings");
    this.comments = Objects.requireNonNull(comments, "comments");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Bước 6–7 của QT-11: gửi góp ý hoặc ghi ý kiến tại hội nghị. */
  @Transactional(TX)
  public CommentView create(TenantId tenant, UUID actor, UUID meetingId, Draft draft) {
    Objects.requireNonNull(draft, "draft");
    MeetingRow meeting = meetings.find(tenant, meetingId).orElseThrow(NoSuchElementException::new);
    if (!meeting.involves(actor)) {
      throw new BusinessException(
          "DOSSIER-403-NOT-PARTICIPANT", "Đồng chí không phải thành phần của hội nghị này.");
    }
    if (HOI_NGHI.equals(draft.phase()) && !meeting.secretaryPersonId().equals(actor)) {
      throw new BusinessException(
          "DOSSIER-403-NOT-SECRETARY", "Chỉ thư ký hội nghị ghi ý kiến tại hội nghị.");
    }
    if (meeting.status().commentsLocked()) {
      throw new BusinessException(
          "DOSSIER-409-COMMENTS-LOCKED", "Hội nghị đã kết thúc nên không nhận thêm góp ý.");
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    if (draft.kind() == null || !KINDS.contains(draft.kind())) {
      errors.add(field("kind", "Loại nội dung góp ý không hợp lệ."));
    }
    if (draft.phase() == null || !PHASES.contains(draft.phase())) {
      errors.add(field("phase", "Giai đoạn góp ý không hợp lệ."));
    }
    String content = draft.content() == null ? "" : draft.content().strip();
    if (content.isEmpty() || content.length() > MAX_CONTENT) {
      errors.add(field("content", "Nội dung góp ý từ 1 đến 4.000 ký tự."));
    }
    UUID subject = draft.subjectPersonId();
    if (subject == null || !subjectOf(meeting, subject)) {
      errors.add(field("subjectPersonId", "Người được góp ý không thuộc hội nghị này."));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException("DOSSIER-422-VALIDATION", "Thông tin góp ý chưa hợp lệ.", errors);
    }
    if (meeting.status() == MeetingStatus.DU_KIEN) {
      throw new BusinessException(
          "DOSSIER-409-INVALID-STATE", "Dự thảo bản kiểm điểm chưa được gửi để góp ý.");
    }
    try {
      periods.assertWritable(tenant, PeriodId.of(meeting.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(
          "DOSSIER-409-PERIOD-LOCKED", "Kỳ đánh giá đã khoá, không ghi được góp ý.");
    }
    UUID id = UUID.randomUUID();
    CommentSealer.Sealed sealed = sealer.seal(tenant, id, content);
    CommentRow row =
        new CommentRow(
            id,
            meeting.id(),
            meeting.periodId(),
            meeting.periodBucket(),
            subject,
            actor,
            GOP_Y,
            draft.kind(),
            draft.phase(),
            sealed.cipher(),
            sealed.dekId(),
            null,
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    comments.insert(tenant, row, actor);
    return new CommentView(row, content, false);
  }

  /**
   * Góp ý của hội nghị cho thành phần hội nghị hoặc người thuộc đơn vị tổ chức (phạm vi Gn); ẩn
   * người góp ý với chính người được góp ý khi tenant bật ẩn danh.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public CommentPage list(
      TenantId tenant,
      UUID actor,
      UUID actorUnit,
      UUID meetingId,
      String phase,
      String kind,
      Position after,
      int limit) {
    MeetingRow meeting = meetings.find(tenant, meetingId).orElseThrow(NoSuchElementException::new);
    if (!meeting.involves(actor) && !meeting.orgUnitId().equals(actorUnit)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xem góp ý của hội nghị này.");
    }
    boolean anonymous = anonymous(tenant, meeting.orgUnitId());
    List<CommentView> items = new ArrayList<>();
    for (CommentRow r :
        comments.list(tenant, meeting.periodBucket(), meeting.id(), phase, kind, after, limit)) {
      boolean hidden =
          anonymous && r.subjectPersonId().equals(actor) && !r.commenterPersonId().equals(actor);
      items.add(new CommentView(r, sealer.open(tenant, r.id(), r.cipher()), hidden));
    }
    return new CommentPage(anonymous, items);
  }

  /** Cấu hình của đơn vị tổ chức, rồi của tenant; thiếu cấu hình thì không ẩn danh. */
  boolean anonymous(TenantId tenant, UUID orgUnit) {
    TenantSettingApi api = settings.get();
    Optional<JsonNode> value =
        api == null ? Optional.empty() : api.get(tenant, ANONYMOUS_SETTING, orgUnit);
    return value
        .map(v -> v.isBoolean() ? v.asBoolean() : v.path("enabled").asBoolean(false))
        .orElse(false);
  }

  /** Hội nghị một người: đúng người được kiểm điểm; hội nghị tập thể: một thành phần (suy luận). */
  private static boolean subjectOf(MeetingRow meeting, UUID subject) {
    return meeting.subjectPersonId() != null
        ? meeting.subjectPersonId().equals(subject)
        : meeting.involves(subject);
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }
}
