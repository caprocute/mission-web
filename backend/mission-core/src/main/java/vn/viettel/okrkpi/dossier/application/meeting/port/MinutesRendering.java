package vn.viettel.okrkpi.dossier.application.meeting.port;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu SVC-11 sinh biên bản hội nghị kiểm điểm (QT-11 bước 8). SVC-11 phát EVT-17 với {@code
 * renderJobId} là {@code jobId} và {@code formInstanceId} là mã hội nghị (TL-03 không có mã biểu
 * mẫu biên bản nên biên bản không có {@code form_instance}). Dữ liệu biên bản (thành phần, điểm
 * danh, ý kiến, biểu quyết) SVC-11 đọc lại theo mã hội nghị; lý do giữ ngày họp đi kèm để in vào
 * biên bản (BR-11-01).
 */
public interface MinutesRendering {

  record MinutesRequest(
      UUID jobId,
      UUID meetingId,
      UUID periodId,
      UUID orgUnitId,
      Instant meetingAt,
      String deadlineOverrideReason,
      UUID requestedBy) {}

  void request(TenantId tenant, MinutesRequest request);
}
