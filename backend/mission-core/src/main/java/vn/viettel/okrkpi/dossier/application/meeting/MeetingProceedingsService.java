package vn.viettel.okrkpi.dossier.application.meeting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository.CommentRow;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository.Position;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.application.meeting.port.MinutesRendering;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository.ItemRow;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.dossier.domain.meeting.Proceedings;
import vn.viettel.okrkpi.dossier.domain.meeting.Vote;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Diễn biến sau khi họp của hội nghị kiểm điểm (CORE-06-04, QT-11 bước 7–9, SCR-1101): thư ký ghi
 * điểm danh và biểu quyết rồi yêu cầu SVC-11 sinh biên bản (202), EVT-17 chuyển {@code
 * DA_LAP_BIEN_BAN}, chủ trì kết thúc hội nghị (khoá góp ý, tạo hạn chế cần khắc phục từ góp ý loại
 * hạn chế, mời chủ trì và thư ký ký biên bản trong 2 ngày làm việc), EVT-18 của đủ hai người chuyển
 * {@code DA_KY}.
 *
 * <p>Biểu quyết chỉ nằm ở {@code vote_json} để in vào biên bản và Mẫu 3, không bao giờ ghi vào mức
 * đề xuất hay mức quyết định của MOD-04 (BR-11-07; ArchUnit trong kiểm thử của MOD-06 chặn lời gọi
 * ghi sang MOD-04).
 */
public class MeetingProceedingsService {

  static final String TX = ReviewMeetingService.TX;
  static final String MODULE = ReviewMeetingService.MODULE;
  static final String CO_MAT = "CO_MAT";
  static final String VANG_CO_LY_DO = "VANG_CO_LY_DO";
  static final String HAN_CHE = "HAN_CHE";
  static final String PURPOSE = "BIEN_BAN";

  /** Hạn ký biên bản (QT-11 bước 8). */
  static final int SIGN_WORKING_DAYS = 2;

  private static final Logger LOG = LoggerFactory.getLogger(MeetingProceedingsService.class);
  private static final int COMMENT_PAGE = 200;

  private final ReviewMeetingRepository meetings;
  private final ReviewCommentRepository comments;
  private final RemediationItemRepository remediation;
  private final CommentSealer sealer;
  private final PeriodApi periods;
  private final Supplier<WorkingCalendarApi> calendar;
  private final MeetingNotices notices;
  private final MinutesRendering rendering;
  private final ObjectMapper json;
  private final Clock clock;

  /** Một dòng điểm danh ({@code DossierAttendance}). */
  public record Attendance(UUID personId, String attendance) {}

  /** Lệnh ghi biểu quyết ({@code DossierMinutesRequest}); {@code meetingAt} rỗng là lúc gửi. */
  public record Minutes(
      Instant meetingAt, List<Attendance> attendance, Map<String, Integer> vote) {}

  /** Đã nhận yêu cầu sinh biên bản: mã tác vụ theo dõi qua {@code getJob}. */
  public record Started(UUID jobId, MeetingRow row) {}

  /** EVT-17 của biên bản. */
  public record Generated(UUID renderJobId, UUID meetingId, String objectKey, byte[] sha256) {}

  /** EVT-18 mục đích {@code BIEN_BAN}. */
  public record Signed(
      UUID signRequestId, UUID meetingId, UUID signer, List<byte[]> hashes, Instant signedAt) {}

  /** Tài liệu cần ký trả cho SVC-10: biên bản và người còn phải ký. */
  public record SignSource(boolean ready, Set<UUID> signers, byte[] minutesSha256) {}

  /** Trạng thái tác vụ sinh biên bản. */
  public record MinutesJob(UUID meetingId, UUID initiator, boolean done, Instant meetingAt) {}

  public MeetingProceedingsService(
      ReviewMeetingRepository meetings,
      ReviewCommentRepository comments,
      RemediationItemRepository remediation,
      CommentSealer sealer,
      PeriodApi periods,
      Supplier<WorkingCalendarApi> calendar,
      MeetingNotices notices,
      MinutesRendering rendering,
      ObjectMapper json,
      Clock clock) {
    this.meetings = Objects.requireNonNull(meetings, "meetings");
    this.comments = Objects.requireNonNull(comments, "comments");
    this.remediation = Objects.requireNonNull(remediation, "remediation");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.rendering = Objects.requireNonNull(rendering, "rendering");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * QT-11 bước 7–8: thư ký ghi điểm danh, biểu quyết, thời điểm họp; hội nghị chuyển {@code DA_HOP}
   * và SVC-11 được yêu cầu sinh biên bản. Tổng phiếu vượt số người có mặt trả 422.
   */
  @Transactional(TX)
  public Started minutes(TenantId tenant, UUID actor, UUID id, long expectedVersion, Minutes cmd) {
    Objects.requireNonNull(cmd, "cmd");
    MeetingRow current = meetings.lock(tenant, id).orElseThrow(NoSuchElementException::new);
    if (!current.secretaryPersonId().equals(actor)) {
      throw new BusinessException(
          "DOSSIER-403-NOT-SECRETARY", "Chỉ thư ký hội nghị ghi biểu quyết và lập biên bản.");
    }
    checkVersion(current, expectedVersion);
    if (current.status() != MeetingStatus.DA_GUI_DU_THAO) {
      throw new BusinessException(
          "DOSSIER-409-INVALID-STATE",
          current.status() == MeetingStatus.DU_KIEN
              ? "Dự thảo bản kiểm điểm chưa được gửi nên chưa lập được biên bản."
              : "Hội nghị đã ghi biểu quyết và lập biên bản trước đó.");
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    Map<UUID, String> attendance = attendance(current, cmd.attendance(), errors);
    Vote vote = vote(cmd.vote(), errors);
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "DOSSIER-422-VALIDATION", "Điểm danh hoặc biểu quyết chưa hợp lệ.", errors);
    }
    long present = attendance.values().stream().filter(CO_MAT::equals).count();
    if (vote.total() > present) {
      throw new BusinessException(
          "DOSSIER-422-VOTE-EXCEEDS-ATTENDEES",
          "Tổng số phiếu biểu quyết (" + vote.total() + ") vượt số người có mặt (" + present + ").",
          List.of(new ProblemFieldError("vote", "Tổng số phiếu vượt số người có mặt.", null)));
    }
    assertWritable(tenant, current.periodId());
    List<Participant> participants = new ArrayList<>();
    for (Participant p : current.participants()) {
      String marked = attendance.get(p.personId());
      participants.add(marked == null ? p : new Participant(p.personId(), p.role(), marked));
    }
    Instant now = now();
    Instant meetingAt =
        cmd.meetingAt() == null ? now : cmd.meetingAt().truncatedTo(ChronoUnit.MICROS);
    UUID jobId = UUID.randomUUID();
    MeetingRow next =
        current.after(
            MeetingStatus.DA_HOP,
            meetingAt,
            participants,
            voteJson(vote),
            null,
            null,
            current.proceedings().withMinutesJob(jobId));
    MeetingRow saved = save(tenant, next, expectedVersion, actor);
    rendering.request(
        tenant,
        new MinutesRendering.MinutesRequest(
            jobId,
            saved.id(),
            saved.periodId(),
            saved.orgUnitId(),
            meetingAt,
            saved.deadlineOverrideReason(),
            actor));
    return new Started(jobId, saved);
  }

  /**
   * Chủ trì kết thúc hội nghị (QT-11 bước 8–9, BR-11-08): cần đã có biểu quyết; góp ý bị khoá, mỗi
   * góp ý loại hạn chế thành một hạn chế cần khắc phục của người được góp ý, và nếu biên bản đã
   * sinh thì chủ trì, thư ký được mời ký ngay (chưa sinh thì mời khi EVT-17 đến).
   */
  @Transactional(TX)
  public MeetingRow close(TenantId tenant, UUID actor, UUID id, long expectedVersion) {
    MeetingRow current = meetings.lock(tenant, id).orElseThrow(NoSuchElementException::new);
    if (!current.chairPersonId().equals(actor)) {
      throw new BusinessException(
          "DOSSIER-403-NOT-CHAIR", "Chỉ chủ trì hội nghị kết thúc hội nghị.");
    }
    checkVersion(current, expectedVersion);
    if (current.voteJson() == null) {
      throw new BusinessException(
          "DOSSIER-409-NO-VOTE", "Hội nghị chưa ghi kết quả biểu quyết nên chưa kết thúc được.");
    }
    if (current.proceedings().closed() || current.status() == MeetingStatus.DA_KY) {
      throw new BusinessException("DOSSIER-409-INVALID-STATE", "Hội nghị đã kết thúc trước đó.");
    }
    assertWritable(tenant, current.periodId());
    Instant now = now();
    LocalDate due = signDue(tenant, LocalDate.ofInstant(now, ReviewMeetingService.ZONE));
    int created = createRemediationItems(tenant, current, actor);
    MeetingRow next =
        current.after(
            current.status(),
            current.meetingAt(),
            current.participants(),
            current.voteJson(),
            current.minutesSha256(),
            current.signRequestId(),
            current.proceedings().close(now, actor, due));
    MeetingRow saved = save(tenant, next, expectedVersion, actor);
    LOG.info("Đã kết thúc hội nghị kiểm điểm meetingId={} remediationItems={}", id, created);
    if (saved.status() == MeetingStatus.DA_LAP_BIEN_BAN) {
      inviteSigners(tenant, saved, actor);
    }
    return saved;
  }

  /**
   * EVT-17 của biên bản: lưu khoá đối tượng và băm, chuyển {@code DA_LAP_BIEN_BAN}. Sự kiện không
   * thuộc hội nghị (biểu mẫu của gói hồ sơ), không khớp tác vụ đang chờ hoặc đến muộn sau khi đã
   * lập biên bản đều bị bỏ qua.
   */
  @Transactional(TX)
  public boolean onMinutesGenerated(TenantId tenant, Generated event) {
    Optional<MeetingRow> found = meetings.lock(tenant, event.meetingId());
    if (found.isEmpty()) {
      return false;
    }
    MeetingRow current = found.get();
    if (!event.renderJobId().equals(current.proceedings().minutesJobId())
        || current.status() != MeetingStatus.DA_HOP) {
      LOG.info(
          "Bỏ qua EVT-17 không khớp tác vụ biên bản đang chờ meetingId={} status={}",
          current.id(),
          current.status());
      return false;
    }
    if (event.sha256() == null || event.sha256().length != 32 || event.objectKey() == null) {
      LOG.warn("EVT-17 biên bản thiếu tệp hoặc băm sai độ dài meetingId={}", current.id());
      return false;
    }
    MeetingRow next =
        current.after(
            MeetingStatus.DA_LAP_BIEN_BAN,
            current.meetingAt(),
            current.participants(),
            current.voteJson(),
            event.sha256().clone(),
            current.signRequestId(),
            current.proceedings().withMinutesObject(event.objectKey()));
    UUID system =
        current.proceedings().closedBy() != null
            ? current.proceedings().closedBy()
            : current.secretaryPersonId();
    MeetingRow saved = save(tenant, next, current.rowVersion(), system);
    if (saved.proceedings().closed()) {
      inviteSigners(tenant, saved, system);
    }
    return true;
  }

  /**
   * EVT-18 ký biên bản: ghi chữ ký của chủ trì hoặc thư ký khi băm khớp biên bản; đủ hai chữ ký thì
   * {@code DA_KY}. EVT-18 lặp của cùng người ký không đổi gì.
   */
  @Transactional(TX)
  public boolean onSigned(TenantId tenant, Signed event) {
    Optional<MeetingRow> found = meetings.lock(tenant, event.meetingId());
    if (found.isEmpty()) {
      LOG.warn("EVT-18 {} trỏ hội nghị không có", event.signRequestId());
      return false;
    }
    MeetingRow current = found.get();
    UUID signer = event.signer();
    if (signer == null
        || (!signer.equals(current.chairPersonId())
            && !signer.equals(current.secretaryPersonId()))) {
      LOG.warn("EVT-18 {} của người không phải chủ trì, thư ký", event.signRequestId());
      return false;
    }
    if (current.status() != MeetingStatus.DA_LAP_BIEN_BAN
        || !current.proceedings().closed()
        || current.proceedings().signedBy(signer)) {
      return false;
    }
    boolean hashMatches =
        event.hashes().isEmpty()
            || event.hashes().stream().anyMatch(h -> Arrays.equals(h, current.minutesSha256()));
    if (!hashMatches) {
      LOG.warn("EVT-18 {} có băm lệch biên bản meetingId={}", event.signRequestId(), current.id());
      return false;
    }
    Proceedings proceedings =
        current
            .proceedings()
            .sign(new Proceedings.Signature(signer, event.signRequestId(), event.signedAt()));
    boolean complete =
        proceedings.signedBy(current.chairPersonId())
            && proceedings.signedBy(current.secretaryPersonId());
    MeetingRow next =
        current.after(
            complete ? MeetingStatus.DA_KY : current.status(),
            current.meetingAt(),
            current.participants(),
            current.voteJson(),
            current.minutesSha256(),
            event.signRequestId(),
            proceedings);
    save(tenant, next, current.rowVersion(), signer);
    return true;
  }

  /** Tài liệu cần ký cho SVC-10: sẵn sàng khi biên bản đã sinh và hội nghị đã kết thúc. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<SignSource> signSource(TenantId tenant, UUID meetingId) {
    return meetings
        .find(tenant, meetingId)
        .map(
            m -> {
              boolean ready =
                  m.status() == MeetingStatus.DA_LAP_BIEN_BAN
                      && m.proceedings().closed()
                      && m.minutesSha256() != null;
              Set<UUID> signers = new LinkedHashSet<>();
              for (UUID p : List.of(m.chairPersonId(), m.secretaryPersonId())) {
                if (!m.proceedings().signedBy(p)) {
                  signers.add(p);
                }
              }
              return new SignSource(ready, signers, m.minutesSha256());
            });
  }

  /** Tác vụ sinh biên bản {@code jobId}: xong khi đã có băm biên bản. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<MinutesJob> minutesJob(TenantId tenant, UUID jobId) {
    return meetings
        .findByMinutesJob(tenant, jobId)
        .map(
            m ->
                new MinutesJob(
                    m.id(), m.secretaryPersonId(), m.minutesSha256() != null, m.meetingAt()));
  }

  /** Ngày hết hạn ký: 2 ngày làm việc sau ngày kết thúc theo lịch tenant. */
  LocalDate signDue(TenantId tenant, LocalDate from) {
    WorkingCalendarApi cal = calendar.get();
    if (cal != null) {
      return cal.addWorkingDays(tenant, from, SIGN_WORKING_DAYS);
    }
    LocalDate d = from;
    int n = 0;
    while (n < SIGN_WORKING_DAYS) {
      d = d.plusDays(1);
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        n++;
      }
    }
    return d;
  }

  private int createRemediationItems(TenantId tenant, MeetingRow meeting, UUID actor) {
    int created = 0;
    Position after = null;
    while (true) {
      List<CommentRow> page =
          comments.list(
              tenant, meeting.periodBucket(), meeting.id(), null, HAN_CHE, after, COMMENT_PAGE);
      for (CommentRow c : page) {
        if (remediation.existsForComment(tenant, meeting.periodBucket(), c.id())) {
          continue;
        }
        String content = sealer.open(tenant, CommentSealer.COMMENT_COLUMN, c.id(), c.cipher());
        UUID itemId = UUID.randomUUID();
        CommentSealer.Sealed sealed =
            sealer.seal(tenant, CommentSealer.REMEDIATION_COLUMN, itemId, content);
        remediation.insert(
            tenant,
            new ItemRow(
                itemId,
                meeting.periodId(),
                meeting.periodBucket(),
                c.subjectPersonId(),
                meeting.id(),
                c.id(),
                sealed.cipher(),
                sealed.dekId(),
                null,
                "MO",
                null,
                null,
                0),
            actor);
        created++;
      }
      if (page.size() < COMMENT_PAGE) {
        return created;
      }
      CommentRow last = page.get(page.size() - 1);
      after = new Position(last.createdAt(), last.id());
    }
  }

  private void inviteSigners(TenantId tenant, MeetingRow meeting, UUID actor) {
    List<UUID> recipients = new ArrayList<>();
    for (UUID p : List.of(meeting.chairPersonId(), meeting.secretaryPersonId())) {
      if (!meeting.proceedings().signedBy(p)) {
        recipients.add(p);
      }
    }
    if (!recipients.isEmpty()) {
      notices.minutesToSign(
          tenant,
          meeting,
          recipients,
          meeting.proceedings().signDueDate(),
          meeting.rowVersion(),
          actor);
    }
  }

  /** Điểm danh: người phải thuộc thành phần, giá trị có mặt hoặc vắng có lý do, không trùng. */
  private static Map<UUID, String> attendance(
      MeetingRow meeting, List<Attendance> rows, List<ProblemFieldError> errors) {
    Map<UUID, String> out = new LinkedHashMap<>();
    if (rows == null || rows.isEmpty()) {
      errors.add(new ProblemFieldError("attendance", "Chưa điểm danh thành phần dự họp.", null));
      return out;
    }
    if (rows.size() > ReviewMeetingService.MAX_PARTICIPANTS) {
      errors.add(new ProblemFieldError("attendance", "Điểm danh tối đa 200 người.", null));
      return out;
    }
    for (int i = 0; i < rows.size(); i++) {
      Attendance a = rows.get(i);
      String field = "attendance[" + i + "]";
      if (a == null || a.personId() == null || !meeting.involves(a.personId())) {
        errors.add(
            new ProblemFieldError(field, "Người này không thuộc thành phần hội nghị.", null));
      } else if (!CO_MAT.equals(a.attendance()) && !VANG_CO_LY_DO.equals(a.attendance())) {
        errors.add(new ProblemFieldError(field, "Trạng thái điểm danh không hợp lệ.", null));
      } else if (out.putIfAbsent(a.personId(), a.attendance()) != null) {
        errors.add(new ProblemFieldError(field, "Một người chỉ điểm danh một lần.", null));
      }
    }
    return out;
  }

  private static Vote vote(Map<String, Integer> counts, List<ProblemFieldError> errors) {
    if (counts == null) {
      errors.add(new ProblemFieldError("vote", "Chưa nhập kết quả biểu quyết.", null));
      return new Vote(Map.of());
    }
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      if (!Vote.LEVELS.contains(e.getKey())) {
        errors.add(new ProblemFieldError("vote." + e.getKey(), "Mức xếp loại không hợp lệ.", null));
      } else if (e.getValue() != null && e.getValue() < 0) {
        errors.add(new ProblemFieldError("vote." + e.getKey(), "Số phiếu không được âm.", null));
      }
    }
    return new Vote(counts);
  }

  private String voteJson(Vote vote) {
    try {
      return json.writeValueAsString(vote.ordered());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private MeetingRow save(TenantId tenant, MeetingRow next, long expectedVersion, UUID actor) {
    long version = meetings.update(tenant, next, expectedVersion, actor);
    if (version == 0) {
      throw VersionConflictException.of(MODULE, expectedVersion, next.rowVersion() + 1);
    }
    return next.withVersion(version);
  }

  private void assertWritable(TenantId tenant, UUID period) {
    try {
      periods.assertWritable(tenant, PeriodId.of(period));
    } catch (PeriodLockedException e) {
      throw new BusinessException(
          "DOSSIER-409-PERIOD-LOCKED", "Kỳ đánh giá đã khoá, không ghi được hội nghị kiểm điểm.");
    }
  }

  private static void checkVersion(MeetingRow current, long expected) {
    if (current.rowVersion() != expected) {
      throw VersionConflictException.of(MODULE, expected, current.rowVersion());
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
