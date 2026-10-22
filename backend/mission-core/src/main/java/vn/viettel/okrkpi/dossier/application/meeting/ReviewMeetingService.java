package vn.viettel.okrkpi.dossier.application.meeting;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingPreparation;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.Filter;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.domain.meeting.DraftDeadline;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hội nghị kiểm điểm của MOD-06 (CORE-06-02, QT-11 bước 1, 4, 5; SCR-1101): lập kế hoạch, sửa kế
 * hoạch trước khi họp, gửi dự thảo và kiểm tra thời hạn 03 ngày làm việc (BR-11-01).
 *
 * <p>Phạm vi (máy chủ là nguồn sự thật, OPA chỉ kiểm vai trò của thao tác): cơ quan tổ chức lập hội
 * nghị cho chính đơn vị của phân công đang chọn; người xem là thành phần của hội nghị hoặc thuộc
 * đơn vị tổ chức; chỉ chủ trì ghi lý do giữ ngày; chỉ người được kiểm điểm gửi dự thảo (hội nghị
 * tập thể thì chủ trì gửi, suy luận).
 */
public class ReviewMeetingService {

  static final String TX = "dossierTransactionManager";
  static final String MODULE = "DOSSIER";
  static final int MAX_PARTICIPANTS = 200;
  static final int MAX_LOCATION = 500;
  static final int MIN_REASON = 10;
  static final int MAX_REASON = 2000;

  /** Lịch làm việc theo giờ Việt Nam (suy luận: tenant chưa có múi giờ riêng). */
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final Logger LOG = LoggerFactory.getLogger(ReviewMeetingService.class);
  private static final Set<String> TYPES = Set.of("KIEM_DIEM_QUY", "KIEM_DIEM_NAM", "CHUYEN_DE");

  private final ReviewMeetingRepository meetings;
  private final PeriodApi periods;
  private final Supplier<WorkingCalendarApi> calendar;
  private final Supplier<OrgDirectoryApi> directory;
  private final MeetingNotices notices;
  private final Supplier<List<MeetingPreparation>> preparations;
  private final Clock clock;

  /** Lệnh tạo hội nghị ({@code DossierMeetingCreate}). */
  public record NewMeeting(
      UUID periodId,
      UUID orgUnitId,
      String meetingType,
      Instant scheduledAt,
      String location,
      UUID chairPersonId,
      UUID secretaryPersonId,
      UUID subjectPersonId,
      List<UUID> participantPersonIds) {}

  /**
   * Lệnh sửa kế hoạch ({@code DossierMeetingUpdate}, JSON Merge Patch): trường {@code null} là
   * không gửi; {@code location} rỗng xoá địa điểm khi {@code locationSet}.
   */
  public record Patch(
      Instant scheduledAt,
      boolean locationSet,
      String location,
      List<UUID> participantPersonIds,
      String deadlineOverrideReason) {}

  /** Hội nghị kèm kết quả kiểm tra thời hạn tính theo lịch làm việc hiện hành. */
  public record MeetingView(MeetingRow row, DraftDeadline.Result deadline) {}

  public ReviewMeetingService(
      ReviewMeetingRepository meetings,
      PeriodApi periods,
      Supplier<WorkingCalendarApi> calendar,
      Supplier<OrgDirectoryApi> directory,
      MeetingNotices notices,
      Supplier<List<MeetingPreparation>> preparations,
      Clock clock) {
    this.meetings = Objects.requireNonNull(meetings, "meetings");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.preparations = Objects.requireNonNull(preparations, "preparations");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** QT-11 bước 1: cơ quan tổ chức lập kế hoạch kiểm điểm; hội nghị bắt đầu ở {@code DU_KIEN}. */
  @Transactional(TX)
  public MeetingView create(TenantId tenant, UUID actor, UUID actorUnit, NewMeeting cmd) {
    Objects.requireNonNull(cmd, "cmd");
    if (actorUnit == null || !actorUnit.equals(cmd.orgUnitId())) {
      throw outOfScope("Đồng chí chỉ lập hội nghị kiểm điểm cho đơn vị mình phụ trách.");
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    if (cmd.meetingType() == null || !TYPES.contains(cmd.meetingType())) {
      errors.add(field("meetingType", "Loại hội nghị không hợp lệ."));
    }
    if (cmd.scheduledAt() == null) {
      errors.add(field("scheduledAt", "Chưa nhập ngày giờ hội nghị."));
    }
    checkLocation(cmd.location(), errors);
    if (cmd.chairPersonId() == null) {
      errors.add(field("chairPersonId", "Chưa chọn chủ trì."));
    }
    if (cmd.secretaryPersonId() == null) {
      errors.add(field("secretaryPersonId", "Chưa chọn thư ký."));
    } else if (cmd.secretaryPersonId().equals(cmd.chairPersonId())) {
      errors.add(field("secretaryPersonId", "Chủ trì và thư ký phải là hai người khác nhau."));
    }
    UUID subject = cmd.subjectPersonId();
    if (subject != null
        && (subject.equals(cmd.chairPersonId()) || subject.equals(cmd.secretaryPersonId()))) {
      errors.add(
          field("subjectPersonId", "Người được kiểm điểm không đồng thời là chủ trì hoặc thư ký."));
    }
    PeriodSnapshot period =
        cmd.periodId() == null
            ? null
            : periods.findById(tenant, PeriodId.of(cmd.periodId())).orElse(null);
    if (period == null) {
      errors.add(field("periodId", "Kỳ đánh giá không tồn tại."));
    }
    List<UUID> members = members(cmd.participantPersonIds(), "participantPersonIds", errors);
    if (errors.isEmpty()) {
      outsideUnit(tenant, cmd.orgUnitId(), subject, members, errors);
    }
    if (!errors.isEmpty()) {
      throw invalid(errors);
    }
    assertWritable(tenant, period.id());
    int bucket = bucket(period);
    if (meetings.lockAndExists(
        tenant, bucket, cmd.periodId(), cmd.orgUnitId(), cmd.meetingType(), subject)) {
      throw new BusinessException(
          "DOSSIER-409-MEETING-EXISTS",
          subject == null
              ? "Tập thể đã có hội nghị kiểm điểm cùng loại trong kỳ."
              : "Đồng chí được kiểm điểm đã có hội nghị cùng loại trong kỳ.");
    }
    List<Participant> participants =
        roster(
            cmd.chairPersonId(),
            cmd.secretaryPersonId(),
            subject,
            members,
            Map.of(),
            Participant.CHUA_MO);
    MeetingRow row =
        new MeetingRow(
            UUID.randomUUID(),
            cmd.periodId(),
            bucket,
            cmd.orgUnitId(),
            cmd.meetingType(),
            cmd.scheduledAt(),
            blankToNull(cmd.location()),
            null,
            cmd.chairPersonId(),
            cmd.secretaryPersonId(),
            MeetingStatus.DU_KIEN,
            null,
            subject,
            participants,
            null,
            null,
            null,
            null,
            0);
    meetings.insert(tenant, row, actor);
    prepare(tenant, row);
    return view(tenant, row);
  }

  /**
   * Sửa kế hoạch khi chưa họp: dời ngày, địa điểm, thành phần (cơ quan tổ chức hoặc chủ trì) và lý
   * do giữ ngày khi thời hạn không đạt (chỉ chủ trì, lý do in vào biên bản). Dời ngày thì lý do cũ
   * mất hiệu lực vì thời hạn được tính lại (suy luận).
   */
  @Transactional(TX)
  public MeetingView update(
      TenantId tenant, UUID actor, UUID actorUnit, UUID id, long expectedVersion, Patch patch) {
    Objects.requireNonNull(patch, "patch");
    MeetingRow current = meetings.lock(tenant, id).orElseThrow(NoSuchElementException::new);
    boolean chair = current.chairPersonId().equals(actor);
    boolean planFields =
        patch.scheduledAt() != null || patch.locationSet() || patch.participantPersonIds() != null;
    if (patch.deadlineOverrideReason() != null && !chair) {
      throw new BusinessException(
          "DOSSIER-403-NOT-CHAIR", "Chỉ chủ trì hội nghị ghi lý do giữ ngày họp.");
    }
    if (planFields && !chair && !current.orgUnitId().equals(actorUnit)) {
      throw outOfScope("Đồng chí không có quyền sửa kế hoạch hội nghị này.");
    }
    checkVersion(current, expectedVersion);
    if (!current.status().plannable()) {
      throw new BusinessException(
          "DOSSIER-409-MEETING-HELD", "Hội nghị đã họp nên không sửa được kế hoạch.");
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    if (patch.locationSet()) {
      checkLocation(patch.location(), errors);
    }
    List<Participant> participants = current.participants();
    if (patch.participantPersonIds() != null) {
      List<UUID> members = members(patch.participantPersonIds(), "participantPersonIds", errors);
      if (errors.isEmpty()) {
        outsideUnit(tenant, current.orgUnitId(), null, members, errors);
      }
      Map<UUID, String> statuses = new LinkedHashMap<>();
      current.participants().forEach(p -> statuses.put(p.personId(), p.status()));
      participants =
          roster(
              current.chairPersonId(),
              current.secretaryPersonId(),
              current.subjectPersonId(),
              members,
              statuses,
              Participant.CHUA_MO);
    }
    Instant scheduledAt = patch.scheduledAt() != null ? patch.scheduledAt() : current.scheduledAt();
    boolean moved = !scheduledAt.equals(current.scheduledAt());
    String reason = moved ? null : current.deadlineOverrideReason();
    if (patch.deadlineOverrideReason() != null) {
      String r = patch.deadlineOverrideReason().strip();
      if (r.length() < MIN_REASON || r.length() > MAX_REASON) {
        errors.add(field("deadlineOverrideReason", "Lý do giữ ngày họp từ 10 đến 2.000 ký tự."));
      } else if (current.draftSentAt() == null
          || deadline(tenant, current.draftSentAt(), scheduledAt).passed()) {
        errors.add(
            field(
                "deadlineOverrideReason",
                "Chỉ ghi lý do giữ ngày họp khi dự thảo đã gửi mà chưa đủ thời hạn."));
      } else {
        reason = r;
      }
    }
    if (!errors.isEmpty()) {
      throw invalid(errors);
    }
    assertWritable(tenant, PeriodId.of(current.periodId()));
    MeetingRow next =
        new MeetingRow(
            current.id(),
            current.periodId(),
            current.periodBucket(),
            current.orgUnitId(),
            current.meetingType(),
            scheduledAt,
            patch.locationSet() ? blankToNull(patch.location()) : current.location(),
            current.draftSentAt(),
            current.chairPersonId(),
            current.secretaryPersonId(),
            current.status(),
            current.meetingAt(),
            current.subjectPersonId(),
            participants,
            reason,
            current.voteJson(),
            current.minutesSha256(),
            current.signRequestId(),
            current.rowVersion());
    return view(tenant, save(tenant, next, expectedVersion, actor));
  }

  /**
   * QT-11 bước 4, 5: người được kiểm điểm gửi dự thảo; ghi {@code draft_sent_at}, chuyển {@code
   * DA_GUI_DU_THAO}, mời thành viên góp ý (EVT-21, MSG-11-02). Thời hạn không đạt chỉ cảnh báo.
   */
  @Transactional(TX)
  public MeetingView sendDraft(TenantId tenant, UUID actor, UUID id, long expectedVersion) {
    MeetingRow current = meetings.lock(tenant, id).orElseThrow(NoSuchElementException::new);
    UUID sender =
        current.subjectPersonId() != null ? current.subjectPersonId() : current.chairPersonId();
    if (!sender.equals(actor)) {
      throw new BusinessException(
          "DOSSIER-403-NOT-SUBJECT", "Chỉ người được kiểm điểm gửi dự thảo bản kiểm điểm.");
    }
    checkVersion(current, expectedVersion);
    if (current.status() != MeetingStatus.DU_KIEN) {
      throw new BusinessException(
          "DOSSIER-409-INVALID-STATE", "Dự thảo bản kiểm điểm đã được gửi trước đó.");
    }
    assertWritable(tenant, PeriodId.of(current.periodId()));
    MeetingRow next =
        new MeetingRow(
            current.id(),
            current.periodId(),
            current.periodBucket(),
            current.orgUnitId(),
            current.meetingType(),
            current.scheduledAt(),
            current.location(),
            clock.instant().truncatedTo(ChronoUnit.MICROS),
            current.chairPersonId(),
            current.secretaryPersonId(),
            MeetingStatus.DA_GUI_DU_THAO,
            current.meetingAt(),
            current.subjectPersonId(),
            current.participants(),
            current.deadlineOverrideReason(),
            current.voteJson(),
            current.minutesSha256(),
            current.signRequestId(),
            current.rowVersion());
    MeetingRow saved = save(tenant, next, expectedVersion, actor);
    List<UUID> recipients =
        saved.participants().stream()
            .map(Participant::personId)
            .filter(p -> !p.equals(actor))
            .distinct()
            .toList();
    if (!recipients.isEmpty()) {
      notices.draftSent(tenant, saved, recipients, saved.rowVersion(), actor);
    }
    return view(tenant, saved);
  }

  /** Chi tiết cho thành phần hội nghị hoặc người thuộc đơn vị tổ chức; người khác nhận 403. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<MeetingView> get(TenantId tenant, UUID actor, UUID actorUnit, UUID id) {
    Optional<MeetingRow> found = meetings.find(tenant, id);
    if (found.isPresent() && !visible(found.get(), actor, actorUnit)) {
      throw outOfScope("Đồng chí không có quyền xem hội nghị kiểm điểm này.");
    }
    return found.map(r -> view(tenant, r));
  }

  /** Hội nghị trong phạm vi người gọi, phân trang con trỏ theo mã hội nghị. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<MeetingRow> list(
      TenantId tenant,
      UUID actor,
      UUID actorUnit,
      UUID periodId,
      UUID orgUnitId,
      MeetingStatus status,
      UUID after,
      int limit) {
    return meetings.list(
        tenant, new Filter(periodId, orgUnitId, status, actor, actorUnit), after, limit);
  }

  /** Thời hạn gửi dự thảo theo lịch làm việc của tenant ({@code config.holiday_calendar}). */
  public DraftDeadline.Result deadline(TenantId tenant, Instant draftSentAt, Instant meetingAt) {
    WorkingCalendarApi cal = calendar.get();
    DraftDeadline.WorkingDayCounter counter =
        cal == null
            ? ReviewMeetingService::weekdaysBetween
            : (from, to) -> cal.countWorkingDays(tenant, from, to);
    return DraftDeadline.check(
        draftSentAt, meetingAt, ZONE, DraftDeadline.REQUIRED_WORKING_DAYS, counter);
  }

  static boolean visible(MeetingRow row, UUID actor, UUID actorUnit) {
    return row.involves(actor) || row.orgUnitId().equals(actorUnit);
  }

  /** Thứ Hai đến thứ Sáu khi chưa có lịch nghỉ của tenant. */
  static long weekdaysBetween(LocalDate fromExclusive, LocalDate toInclusive) {
    long n = 0;
    for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(toInclusive); d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        n++;
      }
    }
    return n;
  }

  /** Khoá phân vùng theo kỳ: năm × 10, cộng số quý với kỳ quý (như MOD-01, MOD-04). */
  static int bucket(PeriodSnapshot p) {
    LocalDate start = p.startDate();
    boolean yearly = ChronoUnit.DAYS.between(start, p.endDate()) > 120;
    return start.getYear() * 10 + (yearly ? 0 : (start.getMonthValue() - 1) / 3 + 1);
  }

  private MeetingView view(TenantId tenant, MeetingRow row) {
    return new MeetingView(row, deadline(tenant, row.draftSentAt(), row.scheduledAt()));
  }

  private MeetingRow save(TenantId tenant, MeetingRow next, long expectedVersion, UUID actor) {
    long version = meetings.update(tenant, next, expectedVersion, actor);
    if (version == 0) {
      throw VersionConflictException.of(MODULE, expectedVersion, next.rowVersion() + 1);
    }
    return new MeetingRow(
        next.id(),
        next.periodId(),
        next.periodBucket(),
        next.orgUnitId(),
        next.meetingType(),
        next.scheduledAt(),
        next.location(),
        next.draftSentAt(),
        next.chairPersonId(),
        next.secretaryPersonId(),
        next.status(),
        next.meetingAt(),
        next.subjectPersonId(),
        next.participants(),
        next.deadlineOverrideReason(),
        next.voteJson(),
        next.minutesSha256(),
        next.signRequestId(),
        version);
  }

  private void prepare(TenantId tenant, MeetingRow row) {
    for (MeetingPreparation p : preparations.get()) {
      try {
        p.meetingCreated(tenant, row);
      } catch (RuntimeException e) {
        LOG.warn(
            "Việc chuẩn bị hội nghị lỗi meetingId={} step={} err={}",
            row.id(),
            p.getClass().getSimpleName(),
            e.getClass().getName());
      }
    }
  }

  private void assertWritable(TenantId tenant, PeriodId period) {
    try {
      periods.assertWritable(tenant, period);
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

  /** Bỏ trùng, bỏ ô trống; tối đa 200 người. */
  private static List<UUID> members(
      List<UUID> ids, String fieldName, List<ProblemFieldError> errors) {
    if (ids == null) {
      return List.of();
    }
    if (ids.size() > MAX_PARTICIPANTS) {
      errors.add(field(fieldName, "Thành phần dự họp tối đa 200 người."));
      return List.of();
    }
    Set<UUID> set = new LinkedHashSet<>();
    for (UUID id : ids) {
      if (id != null) {
        set.add(id);
      }
    }
    return new ArrayList<>(set);
  }

  /**
   * Người được kiểm điểm và thành viên phải có phân công còn hiệu lực ở đơn vị tổ chức. Người chưa
   * có trong bản sao cây tổ chức (chưa đồng bộ) không bị chặn; chủ trì, thư ký có thể là đại diện
   * cấp trên nên không kiểm (suy luận).
   */
  private void outsideUnit(
      TenantId tenant,
      UUID orgUnit,
      UUID subject,
      List<UUID> members,
      List<ProblemFieldError> errors) {
    OrgDirectoryApi org = directory.get();
    if (org == null) {
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    if (subject != null && outside(org, tenant, orgUnit, subject, today)) {
      errors.add(field("subjectPersonId", "Người được kiểm điểm không thuộc đơn vị tổ chức."));
    }
    for (int i = 0; i < members.size(); i++) {
      if (outside(org, tenant, orgUnit, members.get(i), today)) {
        errors.add(
            field("participantPersonIds[" + i + "]", "Thành viên không thuộc đơn vị tổ chức."));
      }
    }
  }

  private static boolean outside(
      OrgDirectoryApi org, TenantId tenant, UUID unit, UUID person, LocalDate on) {
    List<AssignmentView> assignments = org.assignmentsOf(tenant, person, on);
    return !assignments.isEmpty()
        && assignments.stream().noneMatch(a -> unit.equals(a.orgUnitId()));
  }

  /** Thành phần theo thứ tự chủ trì, thư ký, người được kiểm điểm, thành viên. */
  private static List<Participant> roster(
      UUID chair,
      UUID secretary,
      UUID subject,
      List<UUID> members,
      Map<UUID, String> statuses,
      String initial) {
    Map<UUID, Participant> all = new LinkedHashMap<>();
    all.put(chair, new Participant(chair, Participant.CHU_TRI, statuses.get(chair)));
    all.put(secretary, new Participant(secretary, Participant.THU_KY, statuses.get(secretary)));
    if (subject != null) {
      all.put(
          subject,
          new Participant(subject, Participant.NGUOI_DUOC_KIEM_DIEM, statuses.get(subject)));
    }
    for (UUID m : members) {
      all.putIfAbsent(
          m, new Participant(m, Participant.THANH_VIEN, statuses.getOrDefault(m, initial)));
    }
    return new ArrayList<>(all.values());
  }

  private static void checkLocation(String location, List<ProblemFieldError> errors) {
    if (location != null && location.strip().length() > MAX_LOCATION) {
      errors.add(field("location", "Địa điểm tối đa 500 ký tự."));
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }

  private static BusinessException invalid(List<ProblemFieldError> errors) {
    return new BusinessException(
        "DOSSIER-422-VALIDATION", "Thông tin hội nghị kiểm điểm chưa hợp lệ.", errors);
  }

  private static BusinessException outOfScope(String message) {
    return new BusinessException("COMMON-403-OUT-OF-SCOPE", message);
  }
}
