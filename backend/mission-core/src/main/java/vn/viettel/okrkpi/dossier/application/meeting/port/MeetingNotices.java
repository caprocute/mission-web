package vn.viettel.okrkpi.dossier.application.meeting.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thông báo của hội nghị kiểm điểm qua EVT-21, ghi outbox cùng giao dịch (CORE-06-02). */
public interface MeetingNotices {

  /** Mời thành viên góp ý dự thảo bản kiểm điểm (MSG-11-02). */
  void draftSent(
      TenantId tenant, MeetingRow meeting, List<UUID> recipients, long version, UUID actor);

  /**
   * Biên bản đã sinh và hội nghị đã kết thúc: mời chủ trì, thư ký ký số biên bản trước {@code
   * dueDate} (2 ngày làm việc, QT-11 bước 8).
   */
  void minutesToSign(
      TenantId tenant,
      MeetingRow meeting,
      List<UUID> recipients,
      LocalDate dueDate,
      long version,
      UUID actor);
}
