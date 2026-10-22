package vn.viettel.okrkpi.dossier.application.meeting.port;

import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Việc chạy kèm khi tạo hội nghị (QT-11 bước 2, 3): phát hiện trường hợp đặc thù (CORE-06-05) và
 * yêu cầu SVC-11 sinh dự thảo Mẫu 1A đến 1D (DV-11-02). Mỗi việc là một bean; khi chưa có bean nào
 * thì bỏ qua. Lỗi của một việc không làm hỏng lệnh tạo hội nghị.
 */
@FunctionalInterface
public interface MeetingPreparation {

  void meetingCreated(TenantId tenant, MeetingRow meeting);
}
