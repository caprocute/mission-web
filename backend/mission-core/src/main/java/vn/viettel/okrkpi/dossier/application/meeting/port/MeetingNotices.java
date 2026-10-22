package vn.viettel.okrkpi.dossier.application.meeting.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thông báo của hội nghị kiểm điểm qua EVT-21, ghi outbox cùng giao dịch (CORE-06-02). */
public interface MeetingNotices {

  /** Mời thành viên góp ý dự thảo bản kiểm điểm (MSG-11-02). */
  void draftSent(
      TenantId tenant, MeetingRow meeting, List<UUID> recipients, long version, UUID actor);
}
