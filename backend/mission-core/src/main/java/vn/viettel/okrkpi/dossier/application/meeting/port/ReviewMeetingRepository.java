package vn.viettel.okrkpi.dossier.application.meeting.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.dossier.domain.meeting.Proceedings;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lưu trữ {@code dossier.review_meeting} của hội nghị kiểm điểm (CORE-06-02). */
public interface ReviewMeetingRepository {

  /**
   * Một hội nghị. {@code participants} gồm cả chủ trì, thư ký, người được kiểm điểm và thành viên;
   * {@code deadlineOverrideReason} là lý do giữ ngày họp được cấp uỷ chấp thuận (BR-11-01); {@code
   * proceedings} là diễn biến sau khi họp (CORE-06-04).
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
      Proceedings proceedings,
      long rowVersion) {

    public MeetingRow {
      participants = participants == null ? List.of() : List.copyOf(participants);
      proceedings = proceedings == null ? Proceedings.NONE : proceedings;
    }

    /** Hội nghị chưa họp: chưa có diễn biến sau khi họp. */
    public MeetingRow(
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
      this(
          id,
          periodId,
          periodBucket,
          orgUnitId,
          meetingType,
          scheduledAt,
          location,
          draftSentAt,
          chairPersonId,
          secretaryPersonId,
          status,
          meetingAt,
          subjectPersonId,
          participants,
          deadlineOverrideReason,
          voteJson,
          minutesSha256,
          signRequestId,
          Proceedings.NONE,
          rowVersion);
    }

    /** Bản sau khi họp: trạng thái, thời điểm họp, thành phần, biểu quyết, biên bản, chữ ký. */
    public MeetingRow after(
        MeetingStatus nextStatus,
        Instant nextMeetingAt,
        List<Participant> nextParticipants,
        String nextVoteJson,
        byte[] nextMinutesSha256,
        UUID nextSignRequestId,
        Proceedings nextProceedings) {
      return new MeetingRow(
          id,
          periodId,
          periodBucket,
          orgUnitId,
          meetingType,
          scheduledAt,
          location,
          draftSentAt,
          chairPersonId,
          secretaryPersonId,
          nextStatus,
          nextMeetingAt,
          subjectPersonId,
          nextParticipants,
          deadlineOverrideReason,
          nextVoteJson,
          nextMinutesSha256,
          nextSignRequestId,
          nextProceedings,
          rowVersion);
    }

    public MeetingRow withVersion(long version) {
      return new MeetingRow(
          id,
          periodId,
          periodBucket,
          orgUnitId,
          meetingType,
          scheduledAt,
          location,
          draftSentAt,
          chairPersonId,
          secretaryPersonId,
          status,
          meetingAt,
          subjectPersonId,
          participants,
          deadlineOverrideReason,
          voteJson,
          minutesSha256,
          signRequestId,
          proceedings,
          version);
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

  /**
   * Ghi đè các cột kế hoạch, trạng thái, biểu quyết, biên bản và chữ ký; trả phiên bản mới, 0 khi
   * phiên bản đã đổi.
   */
  long update(TenantId tenant, MeetingRow row, long expectedVersion, UUID actor);

  List<MeetingRow> list(TenantId tenant, Filter filter, UUID after, int limit);

  /** Hội nghị có tác vụ sinh biên bản {@code jobId} (theo dõi 202 qua {@code getJob}). */
  Optional<MeetingRow> findByMinutesJob(TenantId tenant, UUID jobId);

  /**
   * Người gọi là thư ký của một hội nghị chưa ký biên bản có kiểm điểm người {@code person} (hội
   * nghị một người cho người đó hoặc hội nghị tập thể có người đó), tức thư ký được cập nhật hạn
   * chế kỳ trước của người này.
   */
  boolean isOpenSecretaryFor(TenantId tenant, UUID secretary, UUID person);
}
