package vn.viettel.okrkpi.policy.application.port;

import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bước 3 của saga phát hành: đồng bộ gói từ vùng điều phối toàn cục xuống các cell và chờ
 * scoring-service nạp được gói (TL-01 "Phát hành, ký số và vòng đời"). Việc đồng bộ và nạp thuộc
 * dịch vụ khác (DV-07, nhóm đồng bộ cell); MOD-05 chỉ phát lệnh và nhận kết quả.
 */
public interface PolicyPackDistribution {

  /**
   * @throws DistributionFailedException khi một cell hoặc scoring-service báo không nạp được
   */
  void distribute(TenantId tenant, PolicyPack pack, PolicyPackVersion version);

  /** Cell hoặc scoring-service báo lỗi nạp gói. */
  final class DistributionFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DistributionFailedException(String message) {
      super(message);
    }
  }
}
