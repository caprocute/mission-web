package vn.viettel.okrkpi.dossier.application.meeting.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lưu trữ {@code dossier.review_meeting} của hội nghị kiểm điểm (CORE-06-02). */
public interface ReviewMeetingRepository {

  /**
   * Một hội nghị. {@code participants} gồm cả chủ trì, thư ký, người được kiểm điểm và thành viên;
   * {@code deadlineOverrideReason} là lý do giữ ngày họp được cấp uỷ chấp thuận (BR-11-01).
   */
  record MeetingRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID orgUnitId,
      String meetingType,
      Instant scheduledAt,
      String location,
      Instant draftSentAt,
      UUID chairPersonId,
      UUID secretaryPersonId,
      MeetingStatus status,
      Instant meetingAt,
      UUID subjectPersonId,
      List<Participant> participants,
      String deadlineOverrideReason,
      String voteJson,
      byte[] minutesSha256,
      UUID signRequestId,
      long rowVersion) {

    public MeetingRow {
      participants = participants == null ? List.of() : List.copyOf(participants);
    }

    /** Người gọi là một thành phần của hội nghị (bất kỳ vai trò nào). */
    public boolean involves(UUID person) {
      return person != null && participants.stream().anyMatch(p -> person.equals(p.personId()));
    }
  }

  /** Bộ lọc danh sách: hội nghị người gọi là thành phần hoặc thuộc đơn vị của người gọi. */
  record Filter(
      UUID periodId, UUID orgUnitId, MeetingStatus status, UUID caller, UUID callerUnit) {}

  void insert(TenantId tenant, MeetingRow row, UUID actor);

  Optional<MeetingRow> find(TenantId tenant, UUID id);

  /** Đọc và khoá dòng ({@code FOR UPDATE}) trong giao dịch hiện hành. */
  Optional<MeetingRow> lock(TenantId tenant, UUID id);

  /**
   * Khoá tư vấn theo giao dịch cho bộ (kỳ, đơn vị, loại, đối tượng) rồi cho biết đã có hội nghị
   * cùng bộ đó chưa, để hai yêu cầu tạo song song không cùng lọt.
   */
  boolean lockAndExists(
      TenantId tenant,
      int bucket,
      UUID periodId,
      UUID orgUnitId,
      String meetingType,
      UUID subjectPersonId);

  /** Ghi đè các cột kế hoạch và trạng thái; trả phiên bản mới, 0 khi phiên bản đã đổi. */
  long update(TenantId tenant, MeetingRow row, long expectedVersion, UUID actor);

  List<MeetingRow> list(TenantId tenant, Filter filter, UUID after, int limit);
}
