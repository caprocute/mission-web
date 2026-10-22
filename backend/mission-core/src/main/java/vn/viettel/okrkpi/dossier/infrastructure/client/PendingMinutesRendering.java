package vn.viettel.okrkpi.dossier.infrastructure.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.dossier.application.meeting.port.MinutesRendering;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng sinh biên bản khi SVC-11 chưa có mẫu biên bản: {@code createDossierRenderJob} của hợp đồng
 * chỉ nhận gói hồ sơ và mã biểu mẫu 1A…bảng chấm điểm, mẫu biên bản thuộc DV-11-xx. Yêu cầu được
 * ghi nhật ký (không có dữ liệu C2) và tác vụ ở {@code DANG_CHAY} tới khi có EVT-17; khi SVC-11
 * công bố cổng sinh biên bản thì thay bean này (wiki trang 14).
 */
public class PendingMinutesRendering implements MinutesRendering {

  private static final Logger LOG = LoggerFactory.getLogger(PendingMinutesRendering.class);

  @Override
  public void request(TenantId tenant, MinutesRequest request) {
    LOG.info(
        "Chờ SVC-11 sinh biên bản hội nghị kiểm điểm meetingId={} jobId={}",
        request.meetingId(),
        request.jobId());
  }
}
