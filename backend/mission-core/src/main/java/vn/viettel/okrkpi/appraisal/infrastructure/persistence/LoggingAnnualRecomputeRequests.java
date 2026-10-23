package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRecomputeRequests;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ghi nhật ký yêu cầu tính lại năm cho tới khi có kênh gửi sang SVC-07 (DV-07-04, DV-07-06); chỉ
 * ghi mã, không ghi dữ liệu cá nhân.
 */
public class LoggingAnnualRecomputeRequests implements AnnualRecomputeRequests {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingAnnualRecomputeRequests.class);

  @Override
  public void request(TenantId tenant, UUID annualProfileId, UUID quarterProfileId) {
    LOG.info(
        "Cần tính lại năm: tenant={} ho_so_nam={} ho_so_quy={}",
        tenant.value(),
        annualProfileId,
        quarterProfileId);
  }
}
