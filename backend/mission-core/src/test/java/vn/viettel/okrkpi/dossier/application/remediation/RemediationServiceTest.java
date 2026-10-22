package vn.viettel.okrkpi.dossier.application.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsServiceTest;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.application.remediation.RemediationService.ItemView;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository.ItemRow;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository.Origin;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị theo dõi khắc phục hạn chế (CORE-06-04, BR-11-08). */
class RemediationServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID CHAIR = UUID.randomUUID();
  static final UUID SECRETARY = UUID.randomUUID();
  static final UUID SUBJECT = UUID.randomUUID();
  static final UUID OTHER = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2027-01-14T03:00:00Z");

  private final ReviewMeetingRepository meetings = mock(ReviewMeetingRepository.class);
  private final RemediationItemRepository items = mock(RemediationItemRepository.class);
  private final CommentSealer sealer = MeetingProceedingsServiceTest.SEALER;
  private final RemediationService service =
      new RemediationService(meetings, items, sealer, Clock.fixed(NOW, ZoneOffset.UTC));

  static MeetingRow meeting(UUID subject) {
    List<Participant> people =
        subject == null
            ? List.of(
                new Participant(CHAIR, Participant.CHU_TRI, null),
                new Participant(SECRETARY, Participant.THU_KY, null),
                new Participant(SUBJECT, Participant.THANH_VIEN, null))
            : List.of(
                new Participant(CHAIR, Participant.CHU_TRI, null),
                new Participant(SECRETARY, Participant.THU_KY, null),
                new Participant(subject, Participant.NGUOI_DUOC_KIEM_DIEM, null));
    return new MeetingRow(
        UUID.randomUUID(),
        PERIOD,
        20270,
        UNIT,
        "KIEM_DIEM_NAM",
        NOW,
        null,
        NOW,
        CHAIR,
        SECRETARY,
        MeetingStatus.DA_GUI_DU_THAO,
        null,
        subject,
        people,
        null,
        null,
        null,
        null,
        2);
  }

  private ItemRow item(UUID meetingId, String status, String text) {
    UUID id = UUID.randomUUID();
    return new ItemRow(
        id,
        UUID.randomUUID(),
        20260,
        SUBJECT,
        meetingId,
        UUID.randomUUID(),
        sealer.seal(TENANT, CommentSealer.REMEDIATION_COLUMN, id, text).cipher(),
        UUID.randomUUID(),
        null,
        status,
        null,
        null,
        1);
  }

  @Test
  void hanCheKyTruocHienChoThanhPhanVaDonViTheoNguoiDuocKiemDiem() {
    MeetingRow m = meeting(SUBJECT);
    when(meetings.find(TENANT, m.id())).thenReturn(Optional.of(m));
    ItemRow old = item(UUID.randomUUID(), "DANG_KHAC_PHUC", "Nâng cao chất lượng thẩm định");
    when(items.listForMeeting(TENANT, m.id(), PERIOD, List.of(SUBJECT), Origin.KY_TRUOC, null, 21))
        .thenReturn(List.of(old));

    List<ItemView> list = service.list(TENANT, CHAIR, null, m.id(), Origin.KY_TRUOC, null, 21);
    assertThat(list).extracting(ItemView::content).containsExactly("Nâng cao chất lượng thẩm định");
    assertThat(service.list(TENANT, OTHER, UNIT, m.id(), Origin.KY_TRUOC, null, 21)).hasSize(1);
    assertThatThrownBy(() -> service.list(TENANT, OTHER, UUID.randomUUID(), m.id(), null, null, 21))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));

    MeetingRow collective = meeting(null);
    when(meetings.find(TENANT, collective.id())).thenReturn(Optional.of(collective));
    service.list(TENANT, CHAIR, null, collective.id(), null, null, 5);
    verify(items)
        .listForMeeting(
            TENANT, collective.id(), PERIOD, List.of(CHAIR, SECRETARY, SUBJECT), null, null, 5);

    UUID missing = UUID.randomUUID();
    when(meetings.find(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.list(TENANT, CHAIR, null, missing, null, null, 5))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void thuKyCapNhatTrangThaiGhiNguoiXacNhan() {
    UUID source = UUID.randomUUID();
    ItemRow current = item(source, "MO", "Hạn chế A");
    when(items.lock(TENANT, current.id())).thenReturn(Optional.of(current));
    when(meetings.find(TENANT, source)).thenReturn(Optional.empty());
    when(meetings.isOpenSecretaryFor(TENANT, SECRETARY, SUBJECT)).thenReturn(true);
    when(items.update(eq(TENANT), any(), eq(1L), eq(SECRETARY))).thenReturn(2L);

    ItemView view =
        service.update(TENANT, SECRETARY, current.id(), 1, "DA_KHAC_PHUC", "Đã có kết quả");

    assertThat(view.row().status()).isEqualTo("DA_KHAC_PHUC");
    assertThat(view.row().verifiedBy()).isEqualTo(SECRETARY);
    assertThat(view.row().verifiedAt()).isEqualTo(NOW);
    assertThat(view.row().rowVersion()).isEqualTo(2);
    assertThat(view.content()).isEqualTo("Hạn chế A");
  }

  @Test
  void thuKyHoiNghiNguonChuyenDangKhacPhucKhongGhiNguoiXacNhan() {
    MeetingRow source = meeting(SUBJECT);
    ItemRow current = item(source.id(), "CHUA_KHAC_PHUC", "Hạn chế B");
    when(items.lock(TENANT, current.id())).thenReturn(Optional.of(current));
    when(meetings.find(TENANT, source.id())).thenReturn(Optional.of(source));
    when(items.update(eq(TENANT), any(), eq(1L), eq(SECRETARY))).thenReturn(2L);

    ItemView view = service.update(TENANT, SECRETARY, current.id(), 1, "DANG_KHAC_PHUC", null);
    ArgumentCaptor<ItemRow> saved = ArgumentCaptor.forClass(ItemRow.class);
    verify(items).update(eq(TENANT), saved.capture(), eq(1L), eq(SECRETARY));
    assertThat(saved.getValue().verifiedBy()).isNull();
    assertThat(view.row().status()).isEqualTo("DANG_KHAC_PHUC");
  }

  @Test
  void capNhatSaiNguoiSaiPhienBanSaiBuocBiChan() {
    MeetingRow source = meeting(SUBJECT);
    ItemRow open = item(source.id(), "MO", "x");
    ItemRow done = item(source.id(), "DA_KHAC_PHUC", "y");
    ItemRow working = item(source.id(), "DANG_KHAC_PHUC", "z");
    when(items.lock(TENANT, open.id())).thenReturn(Optional.of(open));
    when(items.lock(TENANT, done.id())).thenReturn(Optional.of(done));
    when(items.lock(TENANT, working.id())).thenReturn(Optional.of(working));
    when(meetings.find(TENANT, source.id())).thenReturn(Optional.of(source));

    assertThatThrownBy(() -> service.update(TENANT, CHAIR, open.id(), 1, "DA_KHAC_PHUC", null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-403-NOT-SECRETARY"));
    assertThatThrownBy(() -> service.update(TENANT, SECRETARY, open.id(), 0, "DA_KHAC_PHUC", null))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () -> service.update(TENANT, SECRETARY, open.id(), 1, "XONG", "a".repeat(2001)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(f -> f.field())
                    .containsExactly("status", "note"));
    assertThatThrownBy(() -> service.update(TENANT, SECRETARY, open.id(), 1, null, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.update(TENANT, SECRETARY, done.id(), 1, "DANG_KHAC_PHUC", null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-INVALID-STATE"));
    assertThatThrownBy(() -> service.update(TENANT, SECRETARY, working.id(), 1, "MO", null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("DOSSIER-409-INVALID-STATE"));
    when(items.update(any(), any(), anyLong(), any())).thenReturn(0L);
    assertThatThrownBy(
            () -> service.update(TENANT, SECRETARY, open.id(), 1, "DANG_KHAC_PHUC", null))
        .isInstanceOf(VersionConflictException.class);
    UUID missing = UUID.randomUUID();
    when(items.lock(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(TENANT, SECRETARY, missing, 1, "DA_KHAC_PHUC", null))
        .isInstanceOf(NoSuchElementException.class);
    verify(meetings, never()).isOpenSecretaryFor(TENANT, SECRETARY, SUBJECT);
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }
}
