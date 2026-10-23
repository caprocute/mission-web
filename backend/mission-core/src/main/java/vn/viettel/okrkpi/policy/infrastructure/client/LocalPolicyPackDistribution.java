package vn.viettel.okrkpi.policy.infrastructure.client;

import vn.viettel.okrkpi.policy.application.port.PolicyPackDistribution;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bước đồng bộ khi chỉ có một cell (môi trường dev, kiểm thử): gói đã nằm ở cell của mission-core
 * và scoring-service nạp gói khi nhận EVT-13 ở bước cuối, nên bước này xác nhận ngay (suy luận,
 * wiki trang 14). Hiện thực đa cell thay bean này khi nhóm đồng bộ cell và DV-07 có cổng xác nhận.
 */
public class LocalPolicyPackDistribution implements PolicyPackDistribution {

  @Override
  public void distribute(TenantId tenant, PolicyPack pack, PolicyPackVersion version) {
    // Một cell: không có gì để đồng bộ.
  }
}
