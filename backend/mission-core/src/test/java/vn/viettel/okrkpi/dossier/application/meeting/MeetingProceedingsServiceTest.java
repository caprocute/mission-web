package vn.viettel.okrkpi.dossier.application.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository.CommentRow;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService.Attendance;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService.Generated;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService.Minutes;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService.Signed;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService.Started;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.application.meeting.port.MinutesRendering;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository.ItemRow;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.dossier.domain.meeting.Proceedings;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị biểu quyết, biên bản, kết thúc hội nghị và chữ ký (CORE-06-04). */
public class MeetingProceedingsServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID CHAIR = UUID.randomUUID();
  static final UUID SECRETARY = UUID.randomUUID();
  static final UUID SUBJECT = UUID.randomUUID();
  static final List<UUID> MEMBERS = IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
  static final UUID OTHER = UUID.randomUUID();

  /** Thứ Năm 14/01/2027: hạn ký 2 ngày làm việc là thứ Hai 18/01. */
  static final Instant NOW = OffsetDateTime.parse("2027-01-14T10:00:00+07:00").toInstant();

  private final ReviewMeetingRepository repo = mock(ReviewMeetingRepository.class);
  private final ReviewCommentRepository comments = mock(ReviewCommentRepository.class);
  private final RemediationItemRepository items = mock(RemediationItemRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final MeetingNotices notices = mock(MeetingNotices.class);
  private final MinutesRendering rendering = mock(MinutesRendering.class);
  private WorkingCalendarApi calendar;
  private final MeetingProceedingsService service =
      new MeetingProceedingsService(
          repo,
          comments,
          items,
          SEALER,
          periods,
          () -> calendar,
          notices,
          rendering,
          new ObjectMapper(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  /** Bản mã giả ghép cột vào trước nội dung để thấy được AAD đã dùng. */
  public static final CommentSealer SEALER =
      new CommentSealer() {
        @Override
        public Sealed seal(TenantId tenant, String column, UUID rowId, String plain) {
          return new Sealed(
              (column + "|" + plain).getBytes(), UUID.nameUUIDFromBytes(column.getBytes()));
        }

        @Override
        public String open(TenantId tenant, String column, UUID rowId, byte[] cipher) {
          String s = new String(cipher);
          assertThat(s).startsWith(column + "|");
          return s.substring(column.length() + 1);
        }
      };

  static MeetingRow row(MeetingStatus status, String vote, byte[] sha, Proceedings p) {
    List<Participant> people = new ArrayList<>();
    people.add(new Participant(CHAIR, Participant.CHU_TRI, null));
    people.add(new Participant(SECRETARY, Participant.THU_KY, null));
    people.add(new Participant(SUBJECT, Participant.NGUOI_DUOC_KIEM_DIEM, null));
    MEMBERS.forEach(m -> people.add(new Participant(m, Participant.THANH_VIEN, "DA_DOC")));
    return new MeetingRow(
        UUID.randomUUID(),
        PERIOD,
        20260,
        UNIT,
        "KIEM_DIEM_NAM",
        NOW,
        null,
        NOW.minusSeconds(864000),
        CHAIR,
        SECRETARY,
        status,
        status == MeetingStatus.DA_GUI_DU_THAO ? null : NOW,
        SUBJECT,
        people,
        "Giữ ngày vì lịch công tác của cấp uỷ",
        vote,
        sha,
        null,
        p,
        4);
  }

  static Map<String, Integer> vote(int htxs, int htt, int ht, int kht) {
    Map<String, Integer> v = new LinkedHashMap<>();
    v.put("HTXSNV", htxs);
    v.put("HTTNV", htt);
    v.put("HTNV", ht);
    v.put("KHTNV", kht);
    return v;
  }

  /** Sáu người có mặt (chủ trì, thư ký, người được kiểm điểm, ba thành viên), hai người vắng. */
  static List<Attendance> sixPresent() {
    List<Attendance> list = new ArrayList<>();
    list.add(new Attendance(CHAIR, "CO_MAT"));
    list.add(new Attendance(SECRETARY, "CO_MAT"));
    list.add(new Attendance(SUBJECT, "CO_MAT"));
    for (int i = 0; i < MEMBERS.size(); i++) {
      list.add(new Attendance(MEMBERS.get(i), i < 3 ? "CO_MAT" : "VANG_CO_LY_DO"));
    }
    return list;
  }

  @Test
  void bayPhieuVoiSauNguoiCoMatBi422() {
    MeetingRow m = row(MeetingStatus.DA_GUI_DU_THAO, null, null, null);
    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(m));
    assertThatThrownBy(
            () ->
                service.minutes(
                    TENANT,
                    SECRETARY,
                    m.id(),
                    4,
                    new Minutes(null, sixPresent(), vote(1, 5, 1, 0))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("DOSSIER-422-VOTE-EXCEEDS-ATTENDEES");
              assertThat(e.detail()).contains("(7)").contains("(6)");
            });
    verify(repo, never()).update(any(), any(), anyLong(), any());
    verify(rendering, never()).request(any(), any());
  }

  @Test
  void thuKyGhiBieuQuyetChuyenDaHopVaYeuCauSinhBienBan() {
    MeetingRow m = row(MeetingStatus.DA_GUI_DU_THAO, null, null, null);
    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(m));
    when(repo.update(eq(TENANT), any(), eq(4L), eq(SECRETARY))).thenReturn(5L);
    Instant at = OffsetDateTime.parse("2027-01-14T08:05:00+07:00").toInstant();

    Started started =
        service.minutes(
            TENANT, SECRETARY, m.id(), 4, new Minutes(at, sixPresent(), vote(1, 5, 0, 0)));

    assertThat(started.row().status()).isEqualTo(MeetingStatus.DA_HOP);
    assertThat(started.row().rowVersion()).isEqualTo(5);
    assertThat(started.row().meetingAt()).isEqualTo(at);
    assertThat(started.row().voteJson())
        .isEqualTo("{\"HTXSNV\":1,\"HTTNV\":5,\"HTNV\":0,\"KHTNV\":0}");
    assertThat(started.row().proceedings().minutesJobId()).isEqualTo(started.jobId());
    assertThat(started.row().participants())
        .filteredOn(p -> p.personId().equals(MEMBERS.get(4)))
        .extracting(Participant::status)
        .containsExactly("VANG_CO_LY_DO");
    ArgumentCaptor<MinutesRendering.MinutesRequest> req =
        ArgumentCaptor.forClass(MinutesRendering.MinutesRequest.class);
    verify(rendering).request(eq(TENANT), req.capture());
    assertThat(req.getValue().jobId()).isEqualTo(started.jobId());
    assertThat(req.getValue().meetingId()).isEqualTo(m.id());
    assertThat(req.getValue().deadlineOverrideReason()).isEqualTo(m.deadlineOverrideReason());
    assertThat(req.getValue().requestedBy()).isEqualTo(SECRETARY);
  }

  @Test
  void bieuQuyetChiThuKyDungTrangThaiVaDuLieuHopLe() {
    MeetingRow m = row(MeetingStatus.DA_GUI_DU_THAO, null, null, null);
    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(m));
    Minutes ok = new Minutes(null, sixPresent(), vote(0, 6, 0, 0));
    assertThatThrownBy(() -> service.minutes(TENANT, CHAIR, m.id(), 4, ok))
        .satisfies(
            t ->
                assertThat(ReviewMeetingServiceTest.code(t))
                    .isEqualTo("DOSSIER-403-NOT-SECRETARY"));
    assertThatThrownBy(() -> service.minutes(TENANT, SECRETARY, m.id(), 3, ok))
        .isInstanceOf(VersionConflictException.class);

    List<Attendance> bad = new ArrayList<>(sixPresent());
    bad.add(new Attendance(OTHER, "CO_MAT"));
    bad.add(new Attendance(CHAIR, "CO_MAT"));
    bad.add(new Attendance(SUBJECT, "DI_MUON"));
    bad.add(null);
    assertThatThrownBy(
            () ->
                service.minutes(
                    TENANT,
                    SECRETARY,
                    m.id(),
                    4,
                    new Minutes(null, bad, Map.of("XUAT_SAC", 1, "HTNV", -1))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("DOSSIER-422-VALIDATION");
              assertThat(e.errors())
                  .extracting(f -> f.field())
                  .contains(
                      "attendance[8]",
                      "attendance[9]",
                      "attendance[10]",
                      "attendance[11]",
                      "vote.XUAT_SAC",
                      "vote.HTNV");
            });
    assertThatThrownBy(
            () -> service.minutes(TENANT, SECRETARY, m.id(), 4, new Minutes(null, List.of(), null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors()).extracting(f -> f.field()).contains("attendance", "vote"));

    MeetingRow planned = row(MeetingStatus.DU_KIEN, null, null, null);
    when(repo.lock(TENANT, planned.id())).thenReturn(Optional.of(planned));
    assertThatThrownBy(() -> service.minutes(TENANT, SECRETARY, planned.id(), 4, ok))
        .satisfies(
            t ->
                assertThat(ReviewMeetingServiceTest.code(t))
                    .isEqualTo("DOSSIER-409-INVALID-STATE"));
    MeetingRow held = row(MeetingStatus.DA_HOP, "{}", null, null);
    when(repo.lock(TENANT, held.id())).thenReturn(Optional.of(held));
    assertThatThrownBy(() -> service.minutes(TENANT, SECRETARY, held.id(), 4, ok))
        .satisfies(
            t ->
                assertThat(ReviewMeetingServiceTest.code(t))
                    .isEqualTo("DOSSIER-409-INVALID-STATE"));

    UUID missing = UUID.randomUUID();
    when(repo.lock(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.minutes(TENANT, SECRETARY, missing, 4, ok))
        .isInstanceOf(NoSuchElementException.class);

    doThrow(new PeriodLockedException(vn.viettel.okrkpi.shared.kernel.PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(any(), any());
    assertThatThrownBy(() -> service.minutes(TENANT, SECRETARY, m.id(), 4, ok))
        .satisfies(
            t ->
                assertThat(ReviewMeetingServiceTest.code(t))
                    .isEqualTo("DOSSIER-409-PERIOD-LOCKED"));
  }

  @Test
  void ketThucChiChuTriVaCanBieuQuyet() {
    MeetingRow held = row(MeetingStatus.DA_HOP, "{\"HTTNV\":6}", null, null);
    when(repo.lock(TENANT, held.id())).thenReturn(Optional.of(held));
    assertThatThrownBy(() -> service.close(TENANT, SECRETARY, held.id(), 4))
        .satisfies(
            t -> assertThat(ReviewMeetingServiceTest.code(t)).isEqualTo("DOSSIER-403-NOT-CHAIR"));
    assertThatThrownBy(() -> service.close(TENANT, CHAIR, held.id(), 2))
        .isInstanceOf(VersionConflictException.class);

    MeetingRow noVote = row(MeetingStatus.DA_GUI_DU_THAO, null, null, null);
    when(repo.lock(TENANT, noVote.id())).thenReturn(Optional.of(noVote));
    assertThatThrownBy(() -> service.close(TENANT, CHAIR, noVote.id(), 4))
        .satisfies(
            t -> assertThat(ReviewMeetingServiceTest.code(t)).isEqualTo("DOSSIER-409-NO-VOTE"));

    MeetingRow closed =
        row(MeetingStatus.DA_HOP, "{}", null, Proceedings.NONE.close(NOW, CHAIR, null));
    when(repo.lock(TENANT, closed.id())).thenReturn(Optional.of(closed));
    assertThatThrownBy(() -> service.close(TENANT, CHAIR, closed.id(), 4))
        .satisfies(
            t ->
                assertThat(ReviewMeetingServiceTest.code(t))
                    .isEqualTo("DOSSIER-409-INVALID-STATE"));

    UUID missing = UUID.randomUUID();
    when(repo.lock(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.close(TENANT, CHAIR, missing, 4))
        .isInstanceOf(NoSuchElementException.class);
    verify(repo, never()).update(any(), any(), anyLong(), any());
  }

  @Test
  void ketThucTaoHanCheTuGopYHanCheVaMoiKyKhiBienBanDaSinh() {
    byte[] sha = new byte[32];
    sha[0] = 7;
    MeetingRow m = row(MeetingStatus.DA_LAP_BIEN_BAN, "{\"HTTNV\":6}", sha, null);
    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(m));
    when(repo.update(eq(TENANT), any(), eq(4L), eq(CHAIR))).thenReturn(5L);
    List<CommentRow> page = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      page.add(comment(m, "Hạn chế " + i));
    }
    CommentRow last = comment(m, "Hạn chế cuối");
    when(comments.list(
            eq(TENANT), eq(20260), eq(m.id()), isNull(), eq("HAN_CHE"), isNull(), eq(200)))
        .thenReturn(page);
    when(comments.list(
            eq(TENANT),
            eq(20260),
            eq(m.id()),
            isNull(),
            eq("HAN_CHE"),
            any(ReviewCommentRepository.Position.class),
            eq(200)))
        .thenReturn(List.of(last));
    when(items.existsForComment(TENANT, 20260, page.get(0).id())).thenReturn(true);

    MeetingRow saved = service.close(TENANT, CHAIR, m.id(), 4);

    assertThat(saved.proceedings().closed()).isTrue();
    assertThat(saved.proceedings().closedBy()).isEqualTo(CHAIR);
    assertThat(saved.proceedings().signDueDate()).isEqualTo(LocalDate.of(2027, 1, 18));
    ArgumentCaptor<ItemRow> created = ArgumentCaptor.forClass(ItemRow.class);
    verify(items, org.mockito.Mockito.times(200)).insert(eq(TENANT), created.capture(), eq(CHAIR));
    ItemRow first = created.getAllValues().get(0);
    assertThat(first.status()).isEqualTo("MO");
    assertThat(first.personId()).isEqualTo(SUBJECT);
    assertThat(first.reviewCommentId()).isEqualTo(page.get(1).id());
    assertThat(new String(first.cipher()))
        .isEqualTo(CommentSealer.REMEDIATION_COLUMN + "|Hạn chế 1");
    assertThat(created.getAllValues().get(199).reviewCommentId()).isEqualTo(last.id());
    verify(notices)
        .minutesToSign(
            eq(TENANT),
            eq(saved),
            eq(List.of(CHAIR, SECRETARY)),
            eq(LocalDate.of(2027, 1, 18)),
            eq(5L),
            eq(CHAIR));
  }

  @Test
  void ketThucTruocKhiCoBienBanThiMoiKyKhiEvt17Den() {
    UUID job = UUID.randomUUID();
    MeetingRow m =
        row(MeetingStatus.DA_HOP, "{\"HTTNV\":6}", null, Proceedings.NONE.withMinutesJob(job));
    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(m));
    when(repo.update(eq(TENANT), any(), anyLong(), any())).thenReturn(5L);
    when(comments.list(any(), anyInt(), any(), any(), any(), any(), anyInt()))
        .thenReturn(List.of());
    calendar = mock(WorkingCalendarApi.class);
    when(calendar.addWorkingDays(TENANT, LocalDate.of(2027, 1, 14), 2))
        .thenReturn(LocalDate.of(2027, 1, 19));

    MeetingRow closed = service.close(TENANT, CHAIR, m.id(), 4);
    assertThat(closed.proceedings().signDueDate()).isEqualTo(LocalDate.of(2027, 1, 19));
    verify(notices, never()).minutesToSign(any(), any(), any(), any(), anyLong(), any());

    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(closed));
    byte[] sha = new byte[32];
    assertThat(
            service.onMinutesGenerated(TENANT, new Generated(job, m.id(), "bien-ban/1.pdf", sha)))
        .isTrue();
    ArgumentCaptor<MeetingRow> next = ArgumentCaptor.forClass(MeetingRow.class);
    verify(repo).update(eq(TENANT), next.capture(), eq(5L), eq(CHAIR));
    assertThat(next.getValue().status()).isEqualTo(MeetingStatus.DA_LAP_BIEN_BAN);
    assertThat(next.getValue().proceedings().minutesObjectKey()).isEqualTo("bien-ban/1.pdf");
    verify(notices)
        .minutesToSign(
            eq(TENANT),
            any(),
            eq(List.of(CHAIR, SECRETARY)),
            eq(LocalDate.of(2027, 1, 19)),
            anyLong(),
            eq(CHAIR));
  }

  @Test
  void evt17KhongKhopBiBoQua() {
    UUID job = UUID.randomUUID();
    MeetingRow m = row(MeetingStatus.DA_HOP, "{}", null, Proceedings.NONE.withMinutesJob(job));
    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(m));
    UUID unknown = UUID.randomUUID();
    when(repo.lock(TENANT, unknown)).thenReturn(Optional.empty());
    assertThat(service.onMinutesGenerated(TENANT, new Generated(job, unknown, "k", new byte[32])))
        .isFalse();
    assertThat(
            service.onMinutesGenerated(
                TENANT, new Generated(UUID.randomUUID(), m.id(), "k", new byte[32])))
        .isFalse();
    assertThat(service.onMinutesGenerated(TENANT, new Generated(job, m.id(), "k", new byte[5])))
        .isFalse();
    assertThat(service.onMinutesGenerated(TENANT, new Generated(job, m.id(), null, new byte[32])))
        .isFalse();
    MeetingRow done =
        row(
            MeetingStatus.DA_LAP_BIEN_BAN,
            "{}",
            new byte[32],
            Proceedings.NONE.withMinutesJob(job));
    when(repo.lock(TENANT, done.id())).thenReturn(Optional.of(done));
    assertThat(service.onMinutesGenerated(TENANT, new Generated(job, done.id(), "k", new byte[32])))
        .isFalse();
    verify(repo, never()).update(any(), any(), anyLong(), any());
  }

  @Test
  void evt18DuHaiChuKyThiDaKyVaTrungKhongDoiGi() {
    byte[] sha = new byte[32];
    sha[3] = 9;
    MeetingRow m =
        row(
            MeetingStatus.DA_LAP_BIEN_BAN,
            "{}",
            sha,
            Proceedings.NONE.close(NOW, CHAIR, LocalDate.of(2027, 1, 18)));
    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(m));
    when(repo.update(eq(TENANT), any(), anyLong(), any())).thenReturn(5L, 6L);
    UUID r1 = UUID.randomUUID();
    assertThat(service.onSigned(TENANT, new Signed(r1, m.id(), CHAIR, List.of(sha.clone()), NOW)))
        .isTrue();
    ArgumentCaptor<MeetingRow> first = ArgumentCaptor.forClass(MeetingRow.class);
    verify(repo).update(eq(TENANT), first.capture(), eq(4L), eq(CHAIR));
    MeetingRow afterChair = first.getValue().withVersion(5);
    assertThat(afterChair.status()).isEqualTo(MeetingStatus.DA_LAP_BIEN_BAN);
    assertThat(afterChair.proceedings().signedBy(CHAIR)).isTrue();

    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(afterChair));
    when(repo.find(TENANT, m.id())).thenReturn(Optional.of(afterChair));
    assertThat(
            service.onSigned(
                TENANT, new Signed(UUID.randomUUID(), m.id(), CHAIR, List.of(sha), NOW)))
        .as("EVT-18 lặp của chủ trì")
        .isFalse();
    assertThat(service.signSource(TENANT, m.id()))
        .hasValueSatisfying(s -> assertThat(s.signers()).containsExactly(SECRETARY));

    UUID r2 = UUID.randomUUID();
    assertThat(service.onSigned(TENANT, new Signed(r2, m.id(), SECRETARY, List.of(), NOW)))
        .isTrue();
    ArgumentCaptor<MeetingRow> second = ArgumentCaptor.forClass(MeetingRow.class);
    verify(repo).update(eq(TENANT), second.capture(), eq(5L), eq(SECRETARY));
    assertThat(second.getValue().status()).isEqualTo(MeetingStatus.DA_KY);
    assertThat(second.getValue().signRequestId()).isEqualTo(r2);
  }

  @Test
  void evt18KhongHopLeBiBoQua() {
    byte[] sha = new byte[32];
    MeetingRow m =
        row(MeetingStatus.DA_LAP_BIEN_BAN, "{}", sha, Proceedings.NONE.close(NOW, CHAIR, null));
    when(repo.lock(TENANT, m.id())).thenReturn(Optional.of(m));
    UUID unknown = UUID.randomUUID();
    when(repo.lock(TENANT, unknown)).thenReturn(Optional.empty());
    byte[] other = new byte[32];
    other[0] = 1;
    assertThat(
            service.onSigned(TENANT, new Signed(UUID.randomUUID(), unknown, CHAIR, List.of(), NOW)))
        .isFalse();
    assertThat(
            service.onSigned(TENANT, new Signed(UUID.randomUUID(), m.id(), OTHER, List.of(), NOW)))
        .isFalse();
    assertThat(
            service.onSigned(TENANT, new Signed(UUID.randomUUID(), m.id(), null, List.of(), NOW)))
        .isFalse();
    assertThat(
            service.onSigned(
                TENANT, new Signed(UUID.randomUUID(), m.id(), CHAIR, List.of(other), NOW)))
        .isFalse();
    MeetingRow open = row(MeetingStatus.DA_LAP_BIEN_BAN, "{}", sha, null);
    when(repo.lock(TENANT, open.id())).thenReturn(Optional.of(open));
    assertThat(
            service.onSigned(
                TENANT, new Signed(UUID.randomUUID(), open.id(), CHAIR, List.of(), NOW)))
        .as("chưa kết thúc hội nghị")
        .isFalse();
    verify(repo, never()).update(any(), any(), anyLong(), any());
  }

  @Test
  void nguonKyVaTacVuSinhBienBan() {
    UUID job = UUID.randomUUID();
    MeetingRow held = row(MeetingStatus.DA_HOP, "{}", null, Proceedings.NONE.withMinutesJob(job));
    when(repo.find(TENANT, held.id())).thenReturn(Optional.of(held));
    when(repo.findByMinutesJob(TENANT, job)).thenReturn(Optional.of(held));
    assertThat(service.signSource(TENANT, held.id()))
        .hasValueSatisfying(
            s -> {
              assertThat(s.ready()).isFalse();
              assertThat(s.signers()).containsExactly(CHAIR, SECRETARY);
            });
    assertThat(service.minutesJob(TENANT, job))
        .hasValueSatisfying(
            j -> {
              assertThat(j.done()).isFalse();
              assertThat(j.initiator()).isEqualTo(SECRETARY);
              assertThat(j.meetingId()).isEqualTo(held.id());
            });
    byte[] sha = new byte[32];
    MeetingRow ready =
        row(
            MeetingStatus.DA_LAP_BIEN_BAN,
            "{}",
            sha,
            Proceedings.NONE.withMinutesJob(job).close(NOW, CHAIR, null));
    when(repo.find(TENANT, ready.id())).thenReturn(Optional.of(ready));
    assertThat(service.signSource(TENANT, ready.id()))
        .hasValueSatisfying(
            s -> assertThat(Arrays.equals(s.minutesSha256(), sha) && s.ready()).isTrue());
    when(repo.findByMinutesJob(TENANT, job)).thenReturn(Optional.of(ready));
    assertThat(service.minutesJob(TENANT, job))
        .hasValueSatisfying(j -> assertThat(j.done()).isTrue());
  }

  @Test
  void hanKyBoQuaCuoiTuanKhiChuaCoLich() {
    assertThat(service.signDue(TENANT, LocalDate.of(2027, 1, 15)))
        .isEqualTo(LocalDate.of(2027, 1, 19));
    assertThat(service.signDue(TENANT, LocalDate.of(2027, 1, 12)))
        .isEqualTo(LocalDate.of(2027, 1, 14));
  }

  private CommentRow comment(MeetingRow m, String text) {
    UUID id = UUID.randomUUID();
    return new CommentRow(
        id,
        m.id(),
        PERIOD,
        20260,
        SUBJECT,
        MEMBERS.get(0),
        "GOP_Y",
        "HAN_CHE",
        "TRUOC_HOI_NGHI",
        SEALER.seal(TENANT, CommentSealer.COMMENT_COLUMN, id, text).cipher(),
        UUID.randomUUID(),
        null,
        NOW);
  }
}
