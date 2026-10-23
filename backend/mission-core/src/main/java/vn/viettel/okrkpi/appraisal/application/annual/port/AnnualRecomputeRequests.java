package vn.viettel.okrkpi.appraisal.application.annual.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu SVC-07 tính lại ảnh chụp năm khi ảnh chụp một quý đổi sau hậu kiểm (QT-10 ngoại lệ 3).
 * Kênh gửi sang SVC-07 chưa có trong hợp đồng sự kiện; cài đặt hiện tại chỉ ghi nhật ký, việc nối
 * kênh thuộc DV-07-04, DV-07-06 (wiki trang 14, ghi chú CORE-04-10).
 */
public interface AnnualRecomputeRequests {

  void request(TenantId tenant, UUID annualProfileId, UUID quarterProfileId);
}
