package vn.viettel.okrkpi.dossier.application.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.dossier.application.comment.ReviewCommentService.CommentPage;
import vn.viettel.okrkpi.dossier.application.comment.ReviewCommentService.CommentView;
import vn.viettel.okrkpi.dossier.application.comment.ReviewCommentService.Draft;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository.CommentRow;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị góp ý kiểm điểm (CORE-06-03). */
class ReviewCommentServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID CHAIR = UUID.randomUUID();
  static final UUID SECRETARY = UUID.randomUUID();
  static final UUID SUBJECT = UUID.randomUUID();
  static final UUID MEMBER = UUID.randomUUID();
  static final UUID OTHER = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2027-01-10T02:15:00Z");

  /** Bản mã giả: đảo byte để không trùng văn bản rõ. */
  static final CommentSealer SEALER =
      new CommentSealer() {
        @Override
        public Sealed seal(TenantId tenant, UUID commentId, String plain) {
          byte[] b = plain.getBytes(StandardCharsets.UTF_8);
          for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ~b[i];
          }
          return new Sealed(b, UUID.nameUUIDFromBytes(commentId.toString().getBytes()));
        }

        @Override
        public String open(TenantId tenant, UUID commentId, byte[] cipher) {
          byte[] b = cipher.clone();
          for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ~b[i];
          }
          return new String(b, StandardCharsets.UTF_8);
        }
      };

  private final ReviewMeetingRepository meetings = mock(ReviewMeetingRepository.class);
  private final ReviewCommentRepository comments = mock(ReviewCommentRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final TenantSettingApi settings = mock(TenantSettingApi.class);
  private final ReviewCommentService service =
      new ReviewCommentService(
          meetings, comments, SEALER, periods, () -> settings, Clock.fixed(NOW, ZoneOffset.UTC));

  private MeetingRow meeting;

  static MeetingRow meeting(MeetingStatus status, UUID subject) {
    List<Participant> people =
        subject == null
            ? List.of(
                new Participant(CHAIR, Participant.CHU_TRI, null),
                new Participant(SECRETARY, Participant.THU_KY, null),
                new Participant(MEMBER, Participant.THANH_VIEN, "CHUA_MO"))
            : List.of(
                new Participant(CHAIR, Participant.CHU_TRI, null),
                new Participant(SECRETARY, Participant.THU_KY, null),
                new Participant(subject, Participant.NGUOI_DUOC_KIEM_DIEM, null),
                new Participant(MEMBER, Participant.THANH_VIEN, "CHUA_MO"));
    return new MeetingRow(
        UUID.randomUUID(),
        PERIOD,
        20260,
        UNIT,
        "KIEM_DIEM_NAM",
        NOW.plusSeconds(86_400 * 4),
        null,
        status == MeetingStatus.DU_KIEN ? null : NOW.minusSeconds(86_400),
        CHAIR,
        SECRETARY,
        status,
        null,
        subject,
        people,
        null,
        null,
        null,
        null,
        1);
  }

  @BeforeEach
  void setUp() {
    meeting = meeting(MeetingStatus.DA_GUI_DU_THAO, SUBJECT);
    when(meetings.find(TENANT, meeting.id())).thenReturn(Optional.of(meeting));
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @Test
  void thanhVienGopYTruocHoiNghiNoiDungDaMaHoa() {
    CommentView v =
        service.create(
            TENANT,
            MEMBER,
            meeting.id(),
            new Draft(SUBJECT, "HAN_CHE", "TRUOC_HOI_NGHI", "  Tham mưu còn chậm.  "));
    ArgumentCaptor<CommentRow> saved = ArgumentCaptor.forClass(CommentRow.class);
    verify(comments).insert(eq(TENANT), saved.capture(), eq(MEMBER));
    CommentRow r = saved.getValue();
    assertThat(new String(r.cipher(), StandardCharsets.UTF_8)).doesNotContain("Tham mưu");
    assertThat(r.dekId()).isNotNull();
    assertThat(r.source()).isEqualTo("GOP_Y");
    assertThat(r.periodBucket()).isEqualTo(20260);
    assertThat(r.createdAt()).isEqualTo(NOW);
    assertThat(v.content()).isEqualTo("Tham mưu còn chậm.");
    assertThat(v.commenterHidden()).isFalse();
  }

  @Test
  void hoiNghiChiThuKyVaKhoaSauKhiKetThuc() {
    Draft atMeeting = new Draft(SUBJECT, "UU_DIEM", "HOI_NGHI", "Ý kiến tại hội nghị");
    assertThatThrownBy(() -> service.create(TENANT, MEMBER, meeting.id(), atMeeting))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-403-NOT-SECRETARY"));
    service.create(TENANT, SECRETARY, meeting.id(), atMeeting);
    assertThatThrownBy(() -> service.create(TENANT, OTHER, meeting.id(), atMeeting))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-403-NOT-PARTICIPANT"));

    MeetingRow held = meeting(MeetingStatus.DA_HOP, SUBJECT);
    when(meetings.find(TENANT, held.id())).thenReturn(Optional.of(held));
    assertThatThrownBy(() -> service.create(TENANT, SECRETARY, held.id(), atMeeting))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-COMMENTS-LOCKED"));
    MeetingRow planned = meeting(MeetingStatus.DU_KIEN, SUBJECT);
    when(meetings.find(TENANT, planned.id())).thenReturn(Optional.of(planned));
    assertThatThrownBy(() -> service.create(TENANT, MEMBER, planned.id(), draft()))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-INVALID-STATE"));
    UUID missing = UUID.randomUUID();
    when(meetings.find(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(TENANT, MEMBER, missing, draft()))
        .isInstanceOf(NoSuchElementException.class);
  }

  static Draft draft() {
    return new Draft(SUBJECT, "KIEN_NGHI", "TRUOC_HOI_NGHI", "Kiến nghị");
  }

  @Test
  void duLieuSaiVaKyKhoa() {
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT, MEMBER, meeting.id(), new Draft(OTHER, "KHAC", null, "x".repeat(4001))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly("kind", "phase", "content", "subjectPersonId"));
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT, MEMBER, meeting.id(), new Draft(null, "UU_DIEM", "HOI_NGHI", " ")))
        .isInstanceOf(BusinessException.class);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(() -> service.create(TENANT, MEMBER, meeting.id(), draft()))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-PERIOD-LOCKED"));
    verify(comments, never()).insert(any(), any(), any());
  }

  @Test
  void hoiNghiTapTheGopYChoMotThanhPhan() {
    MeetingRow collective = meeting(MeetingStatus.DA_GUI_DU_THAO, null);
    when(meetings.find(TENANT, collective.id())).thenReturn(Optional.of(collective));
    service.create(
        TENANT, MEMBER, collective.id(), new Draft(CHAIR, "UU_DIEM", "TRUOC_HOI_NGHI", "Tốt"));
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT,
                    MEMBER,
                    collective.id(),
                    new Draft(OTHER, "UU_DIEM", "TRUOC_HOI_NGHI", "Tốt")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-422-VALIDATION"));
  }

  @Test
  void anDanhAnNguoiGopYVoiNguoiDuocKiemDiem() {
    CommentRow fromMember = row(MEMBER, SUBJECT, "Còn chậm");
    CommentRow own = row(SUBJECT, SUBJECT, "Tự nhận");
    when(comments.list(eq(TENANT), anyInt(), eq(meeting.id()), any(), any(), any(), anyInt()))
        .thenReturn(List.of(fromMember, own));
    when(settings.get(TENANT, ReviewCommentService.ANONYMOUS_SETTING, UNIT))
        .thenReturn(Optional.of(BooleanNode.TRUE));
    CommentPage subjectView =
        service.list(TENANT, SUBJECT, null, meeting.id(), null, null, null, 21);
    assertThat(subjectView.anonymous()).isTrue();
    assertThat(subjectView.items())
        .extracting(CommentView::commenterHidden)
        .containsExactly(true, false);
    assertThat(subjectView.items())
        .extracting(CommentView::content)
        .containsExactly("Còn chậm", "Tự nhận");
    CommentPage chairView = service.list(TENANT, CHAIR, null, meeting.id(), null, null, null, 21);
    assertThat(chairView.items()).extracting(CommentView::commenterHidden).containsOnly(false);
    CommentPage unitView =
        service.list(TENANT, OTHER, UNIT, meeting.id(), "HOI_NGHI", "UU_DIEM", null, 5);
    assertThat(unitView.items()).hasSize(2);
    ReviewCommentRepository.Position after = new ReviewCommentRepository.Position(NOW, own.id());
    service.list(TENANT, CHAIR, null, meeting.id(), null, null, after, 5);
    verify(comments).list(TENANT, 20260, meeting.id(), null, null, after, 5);

    assertThatThrownBy(() -> service.list(TENANT, OTHER, OTHER, meeting.id(), null, null, null, 5))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    UUID missing = UUID.randomUUID();
    when(meetings.find(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.list(TENANT, OTHER, OTHER, missing, null, null, null, 5))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void cauHinhAnDanhDangDoiTuongHoacThieu() {
    when(settings.get(TENANT, ReviewCommentService.ANONYMOUS_SETTING, UNIT))
        .thenReturn(Optional.of(JsonNodeFactory.instance.objectNode().put("enabled", true)));
    assertThat(service.anonymous(TENANT, UNIT)).isTrue();
    when(settings.get(TENANT, ReviewCommentService.ANONYMOUS_SETTING, UNIT))
        .thenReturn(Optional.empty());
    assertThat(service.anonymous(TENANT, UNIT)).isFalse();
    ReviewCommentService noSettings =
        new ReviewCommentService(
            meetings, comments, SEALER, periods, () -> null, Clock.systemUTC());
    assertThat(noSettings.anonymous(TENANT, UNIT)).isFalse();
  }

  private CommentRow row(UUID commenter, UUID subject, String text) {
    UUID id = UUID.randomUUID();
    CommentSealer.Sealed s = SEALER.seal(TENANT, id, text);
    return new CommentRow(
        id,
        meeting.id(),
        PERIOD,
        20260,
        subject,
        commenter,
        "GOP_Y",
        "HAN_CHE",
        "TRUOC_HOI_NGHI",
        s.cipher(),
        s.dekId(),
        null,
        NOW);
  }
}
