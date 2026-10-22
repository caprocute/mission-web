package vn.viettel.okrkpi.dossier.application.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService.MeetingView;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService.NewMeeting;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService.Patch;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingPreparation;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.Filter;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị hội nghị kiểm điểm (CORE-06-02). */
class ReviewMeetingServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID OFFICER = UUID.randomUUID();
  static final UUID CHAIR = UUID.randomUUID();
  static final UUID SECRETARY = UUID.randomUUID();
  static final UUID SUBJECT = UUID.randomUUID();
  static final UUID MEMBER = UUID.randomUUID();
  static final UUID OTHER = UUID.randomUUID();
  static final Instant MEETING = OffsetDateTime.parse("2027-01-14T08:00:00+07:00").toInstant();
  static final Instant SENT = OffsetDateTime.parse("2027-01-08T16:20:00+07:00").toInstant();
  static final PeriodSnapshot YEAR =
      new PeriodSnapshot(
          PeriodId.of(PERIOD),
          TENANT,
          "N2026",
          "Năm 2026",
          LocalDate.of(2026, 1, 1),
          LocalDate.of(2026, 12, 31),
          PeriodStatus.MO);

  private final ReviewMeetingRepository repo = mock(ReviewMeetingRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  private final MeetingNotices notices = mock(MeetingNotices.class);
  private final List<MeetingPreparation> preparations = new ArrayList<>();
  private final ReviewMeetingService service =
      new ReviewMeetingService(
          repo,
          periods,
          () -> null,
          () -> org,
          notices,
          () -> preparations,
          Clock.fixed(SENT, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.of(YEAR));
    when(org.assignmentsOf(eq(TENANT), any(), any())).thenReturn(List.of());
  }

  static NewMeeting create(UUID subject, List<UUID> members) {
    return new NewMeeting(
        PERIOD,
        UNIT,
        "KIEM_DIEM_NAM",
        MEETING,
        " Phòng họp tầng 3 ",
        CHAIR,
        SECRETARY,
        subject,
        members);
  }

  static MeetingRow row(MeetingStatus status, Instant sent, UUID subject, String reason) {
    List<Participant> people = new ArrayList<>();
    people.add(new Participant(CHAIR, Participant.CHU_TRI, null));
    people.add(new Participant(SECRETARY, Participant.THU_KY, null));
    if (subject != null) {
      people.add(new Participant(subject, Participant.NGUOI_DUOC_KIEM_DIEM, null));
    }
    people.add(new Participant(MEMBER, Participant.THANH_VIEN, "DA_DOC"));
    return new MeetingRow(
        UUID.randomUUID(),
        PERIOD,
        20260,
        UNIT,
        "KIEM_DIEM_NAM",
        MEETING,
        "Phòng họp",
        sent,
        CHAIR,
        SECRETARY,
        status,
        null,
        subject,
        people,
        reason,
        null,
        null,
        null,
        3);
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @Test
  void taoHoiNghiDuKienVoiThanhPhanTheoVaiTro() {
    MeetingPreparation broken =
        (t, m) -> {
          throw new IllegalStateException("x");
        };
    MeetingPreparation ok = mock(MeetingPreparation.class);
    preparations.add(broken);
    preparations.add(ok);
    MeetingView v =
        service.create(TENANT, OFFICER, UNIT, create(SUBJECT, List.of(MEMBER, MEMBER, CHAIR)));
    ArgumentCaptor<MeetingRow> saved = ArgumentCaptor.forClass(MeetingRow.class);
    verify(repo).insert(eq(TENANT), saved.capture(), eq(OFFICER));
    MeetingRow r = saved.getValue();
    assertThat(r.status()).isEqualTo(MeetingStatus.DU_KIEN);
    assertThat(r.periodBucket()).isEqualTo(20260);
    assertThat(r.location()).isEqualTo("Phòng họp tầng 3");
    assertThat(r.participants())
        .extracting(Participant::role)
        .containsExactly(
            Participant.CHU_TRI,
            Participant.THU_KY,
            Participant.NGUOI_DUOC_KIEM_DIEM,
            Participant.THANH_VIEN);
    assertThat(r.participants().get(3).status()).isEqualTo(Participant.CHUA_MO);
    assertThat(v.deadline().workingDays()).isNull();
    verify(ok).meetingCreated(TENANT, r);
    verify(repo).lockAndExists(TENANT, 20260, PERIOD, UNIT, "KIEM_DIEM_NAM", SUBJECT);
  }

  @Test
  void taoNgoaiDonViHoacDuLieuSaiBiChan() {
    assertThatThrownBy(() -> service.create(TENANT, OFFICER, OTHER, create(null, null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service.create(TENANT, OFFICER, null, create(null, null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));

    NewMeeting bad =
        new NewMeeting(
            UUID.randomUUID(), UNIT, "KHAC", null, "x".repeat(501), CHAIR, CHAIR, CHAIR, null);
    assertThatThrownBy(() -> service.create(TENANT, OFFICER, UNIT, bad))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("DOSSIER-422-VALIDATION");
              assertThat(e.errors())
                  .extracting(ProblemFieldError::field)
                  .containsExactly(
                      "meetingType",
                      "scheduledAt",
                      "location",
                      "secretaryPersonId",
                      "subjectPersonId",
                      "periodId");
            });
    NewMeeting none =
        new NewMeeting(null, UNIT, "CHUYEN_DE", MEETING, null, null, null, null, null);
    assertThatThrownBy(() -> service.create(TENANT, OFFICER, UNIT, none))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly("chairPersonId", "secretaryPersonId", "periodId"));
    List<UUID> many = new ArrayList<>();
    for (int i = 0; i < 201; i++) {
      many.add(UUID.randomUUID());
    }
    assertThatThrownBy(() -> service.create(TENANT, OFFICER, UNIT, create(null, many)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly("participantPersonIds"));
    verify(repo, never()).insert(any(), any(), any());
  }

  @Test
  void nguoiNgoaiDonViTheoBanSaoToChuc() {
    AssignmentView elsewhere =
        new AssignmentView(
            UUID.randomUUID(),
            MEMBER,
            OTHER,
            null,
            null,
            "CHINH",
            "CHINH_QUYEN",
            false,
            LocalDate.of(2020, 1, 1),
            null);
    when(org.assignmentsOf(eq(TENANT), eq(MEMBER), any())).thenReturn(List.of(elsewhere));
    when(org.assignmentsOf(eq(TENANT), eq(SUBJECT), any())).thenReturn(List.of(elsewhere));
    assertThatThrownBy(
            () -> service.create(TENANT, OFFICER, UNIT, create(SUBJECT, List.of(OTHER, MEMBER))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly("subjectPersonId", "participantPersonIds[1]"));
    // Không có bản sao cây tổ chức thì không kiểm.
    ReviewMeetingService noOrg =
        new ReviewMeetingService(
            repo, periods, () -> null, () -> null, notices, List::of, Clock.systemUTC());
    noOrg.create(TENANT, OFFICER, UNIT, create(SUBJECT, List.of(MEMBER)));
    verify(repo).insert(eq(TENANT), any(), eq(OFFICER));
  }

  @Test
  void kyKhoaHoacDaCoHoiNghiCungLoai() {
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(() -> service.create(TENANT, OFFICER, UNIT, create(null, null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-PERIOD-LOCKED"));

    PeriodApi p = mock(PeriodApi.class);
    when(p.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.of(YEAR));
    ReviewMeetingService open =
        new ReviewMeetingService(
            repo, p, () -> null, () -> null, notices, List::of, Clock.systemUTC());
    when(repo.lockAndExists(any(), anyInt(), any(), any(), anyString(), any())).thenReturn(true);
    assertThatThrownBy(() -> open.create(TENANT, OFFICER, UNIT, create(null, null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-MEETING-EXISTS"))
        .satisfies(t -> assertThat(((BusinessException) t).detail()).contains("Tập thể"));
    assertThatThrownBy(() -> open.create(TENANT, OFFICER, UNIT, create(SUBJECT, null)))
        .satisfies(
            t -> assertThat(((BusinessException) t).detail()).contains("Đồng chí được kiểm điểm"));
  }

  @Test
  void guiDuThaoGhiThoiDiemPhatThongBaoVaDemNgay() {
    MeetingRow r = row(MeetingStatus.DU_KIEN, null, SUBJECT, null);
    when(repo.lock(TENANT, r.id())).thenReturn(Optional.of(r));
    when(repo.update(eq(TENANT), any(), eq(3L), eq(SUBJECT))).thenReturn(4L);
    MeetingView v = service.sendDraft(TENANT, SUBJECT, r.id(), 3);
    assertThat(v.row().status()).isEqualTo(MeetingStatus.DA_GUI_DU_THAO);
    assertThat(v.row().draftSentAt()).isEqualTo(SENT);
    assertThat(v.row().rowVersion()).isEqualTo(4);
    assertThat(v.deadline().workingDays()).isEqualTo(3);
    assertThat(v.deadline().passed()).isTrue();
    verify(notices).draftSent(TENANT, v.row(), List.of(CHAIR, SECRETARY, MEMBER), 4, SUBJECT);
  }

  @Test
  void guiDuThaoChiNguoiDuocKiemDiemVaKhiDuKien() {
    MeetingRow r = row(MeetingStatus.DU_KIEN, null, SUBJECT, null);
    when(repo.lock(TENANT, r.id())).thenReturn(Optional.of(r));
    assertThatThrownBy(() -> service.sendDraft(TENANT, MEMBER, r.id(), 3))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-403-NOT-SUBJECT"));
    assertThatThrownBy(() -> service.sendDraft(TENANT, SUBJECT, r.id(), 2))
        .isInstanceOf(VersionConflictException.class);
    when(repo.update(any(), any(), anyLong(), any())).thenReturn(0L);
    assertThatThrownBy(() -> service.sendDraft(TENANT, SUBJECT, r.id(), 3))
        .isInstanceOf(VersionConflictException.class);

    MeetingRow sent = row(MeetingStatus.DA_GUI_DU_THAO, SENT, SUBJECT, null);
    when(repo.lock(TENANT, sent.id())).thenReturn(Optional.of(sent));
    assertThatThrownBy(() -> service.sendDraft(TENANT, SUBJECT, sent.id(), 3))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-INVALID-STATE"));
    UUID missing = UUID.randomUUID();
    when(repo.lock(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.sendDraft(TENANT, SUBJECT, missing, 3))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void hoiNghiTapTheDoChuTriGuiVaKhongAiNhanThiKhongPhat() {
    MeetingRow collective =
        new MeetingRow(
            UUID.randomUUID(),
            PERIOD,
            20260,
            UNIT,
            "KIEM_DIEM_NAM",
            MEETING,
            null,
            null,
            CHAIR,
            SECRETARY,
            MeetingStatus.DU_KIEN,
            null,
            null,
            List.of(new Participant(CHAIR, Participant.CHU_TRI, null)),
            null,
            null,
            null,
            null,
            0);
    when(repo.lock(TENANT, collective.id())).thenReturn(Optional.of(collective));
    when(repo.update(any(), any(), eq(0L), any())).thenReturn(1L);
    service.sendDraft(TENANT, CHAIR, collective.id(), 0);
    verify(notices, never()).draftSent(any(), any(), any(), anyLong(), any());
  }

  @Test
  void suaKeHoachDoiNgayGiuTrangThaiThanhVienVaXoaLyDoCu() {
    MeetingRow r = row(MeetingStatus.DA_GUI_DU_THAO, SENT, SUBJECT, "Lý do giữ ngày đã ghi trước");
    when(repo.lock(TENANT, r.id())).thenReturn(Optional.of(r));
    when(repo.update(eq(TENANT), any(), eq(3L), eq(OFFICER))).thenReturn(4L);
    Instant later = MEETING.plusSeconds(86_400);
    MeetingView v =
        service.update(
            TENANT,
            OFFICER,
            UNIT,
            r.id(),
            3,
            new Patch(later, true, "  ", List.of(MEMBER, OTHER), null));
    assertThat(v.row().scheduledAt()).isEqualTo(later);
    assertThat(v.row().location()).isNull();
    assertThat(v.row().deadlineOverrideReason()).isNull();
    assertThat(v.row().participants())
        .extracting(Participant::personId, Participant::status)
        .containsExactly(
            tuple(CHAIR, null),
            tuple(SECRETARY, null),
            tuple(SUBJECT, null),
            tuple(MEMBER, "DA_DOC"),
            tuple(OTHER, Participant.CHUA_MO));
    assertThat(v.deadline().workingDays()).isEqualTo(4);
  }

  @Test
  void lyDoGiuNgayChiChuTriVaKhiChuaDuThoiHan() {
    // Gửi 16:20 thứ Hai 11/01, họp thứ Năm 14/01: chỉ có 12, 13/01.
    Instant late = OffsetDateTime.parse("2027-01-11T16:20:00+07:00").toInstant();
    MeetingRow r = row(MeetingStatus.DA_GUI_DU_THAO, late, SUBJECT, null);
    when(repo.lock(TENANT, r.id())).thenReturn(Optional.of(r));
    Patch reason = new Patch(null, false, null, null, "Cấp uỷ thống nhất giữ ngày họp.");
    assertThatThrownBy(() -> service.update(TENANT, OFFICER, UNIT, r.id(), 3, reason))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-403-NOT-CHAIR"));
    when(repo.update(eq(TENANT), any(), eq(3L), eq(CHAIR))).thenReturn(4L);
    MeetingView v = service.update(TENANT, CHAIR, OTHER, r.id(), 3, reason);
    assertThat(v.row().deadlineOverrideReason()).isEqualTo("Cấp uỷ thống nhất giữ ngày họp.");
    assertThat(v.deadline().passed()).isFalse();
    assertThat(v.deadline().msg()).isEqualTo("MSG-11-01");

    Patch shortReason = new Patch(null, false, null, null, "ngắn");
    assertThatThrownBy(() -> service.update(TENANT, CHAIR, null, r.id(), 3, shortReason))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-422-VALIDATION"));
    MeetingRow ok = row(MeetingStatus.DA_GUI_DU_THAO, SENT, SUBJECT, null);
    when(repo.lock(TENANT, ok.id())).thenReturn(Optional.of(ok));
    assertThatThrownBy(() -> service.update(TENANT, CHAIR, null, ok.id(), 3, reason))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-422-VALIDATION"));
    MeetingRow unsent = row(MeetingStatus.DU_KIEN, null, SUBJECT, null);
    when(repo.lock(TENANT, unsent.id())).thenReturn(Optional.of(unsent));
    assertThatThrownBy(() -> service.update(TENANT, CHAIR, null, unsent.id(), 3, reason))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-422-VALIDATION"));
  }

  @Test
  void suaKeHoachBiChanKhiDaHopNgoaiPhamViHoacDuLieuSai() {
    MeetingRow held = row(MeetingStatus.DA_HOP, SENT, SUBJECT, null);
    when(repo.lock(TENANT, held.id())).thenReturn(Optional.of(held));
    Patch move = new Patch(MEETING.plusSeconds(3600), false, null, null, null);
    assertThatThrownBy(() -> service.update(TENANT, OFFICER, UNIT, held.id(), 3, move))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-MEETING-HELD"));
    assertThatThrownBy(() -> service.update(TENANT, MEMBER, OTHER, held.id(), 3, move))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service.update(TENANT, CHAIR, null, held.id(), 1, move))
        .isInstanceOf(VersionConflictException.class);

    MeetingRow plan = row(MeetingStatus.DU_KIEN, null, SUBJECT, null);
    when(repo.lock(TENANT, plan.id())).thenReturn(Optional.of(plan));
    Patch bad = new Patch(null, true, "x".repeat(501), List.of(), null);
    assertThatThrownBy(() -> service.update(TENANT, CHAIR, null, plan.id(), 3, bad))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-422-VALIDATION"));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(() -> service.update(TENANT, CHAIR, null, plan.id(), 3, move))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-PERIOD-LOCKED"));
    UUID missing = UUID.randomUUID();
    when(repo.lock(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(TENANT, CHAIR, null, missing, 3, move))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void xemChiThanhPhanHoacDonViToChuc() {
    MeetingRow r = row(MeetingStatus.DU_KIEN, null, SUBJECT, null);
    when(repo.find(TENANT, r.id())).thenReturn(Optional.of(r));
    assertThat(service.get(TENANT, MEMBER, OTHER, r.id())).isPresent();
    assertThat(service.get(TENANT, OFFICER, UNIT, r.id())).isPresent();
    assertThatThrownBy(() -> service.get(TENANT, OTHER, OTHER, r.id()))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThat(service.get(TENANT, OTHER, OTHER, UUID.randomUUID())).isEmpty();

    service.list(TENANT, MEMBER, OTHER, PERIOD, null, MeetingStatus.DU_KIEN, null, 21);
    verify(repo)
        .list(TENANT, new Filter(PERIOD, null, MeetingStatus.DU_KIEN, MEMBER, OTHER), null, 21);
  }

  @Test
  void lichLamViecCuaTenantVaMacDinhThuHaiDenThuSau() {
    WorkingCalendarApi cal = mock(WorkingCalendarApi.class);
    when(cal.countWorkingDays(TENANT, LocalDate.of(2027, 1, 8), LocalDate.of(2027, 1, 13)))
        .thenReturn(2L);
    ReviewMeetingService withCalendar =
        new ReviewMeetingService(
            repo, periods, () -> cal, () -> null, notices, List::of, Clock.systemUTC());
    assertThat(withCalendar.deadline(TENANT, SENT, MEETING).workingDays()).isEqualTo(2);
    assertThat(
            ReviewMeetingService.weekdaysBetween(
                LocalDate.of(2027, 1, 8), LocalDate.of(2027, 1, 18)))
        .isEqualTo(6);
    assertThat(
            ReviewMeetingService.bucket(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    TENANT,
                    "Q1",
                    "Quý I năm 2027",
                    LocalDate.of(2027, 1, 1),
                    LocalDate.of(2027, 3, 31),
                    PeriodStatus.MO)))
        .isEqualTo(20271);
    assertThat(row(MeetingStatus.DU_KIEN, null, null, null).involves(null)).isFalse();
  }
}
