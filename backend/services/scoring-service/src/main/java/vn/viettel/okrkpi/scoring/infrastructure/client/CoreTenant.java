package vn.viettel.okrkpi.scoring.infrastructure.client;

import java.util.UUID;
import org.springframework.http.HttpHeaders;

/**
 * Tenant đích của lời gọi tới mission-core: token client dịch vụ {@code scoring-service} không mang
 * tenant, nên tenant đi ở {@code X-Tenant-Id} và mission-core chỉ nhận header này từ client khai
 * trong {@code okrkpi.tenant.delegating-clients} (như workflow-service, analytics-api).
 */
public final class CoreTenant {

  public static final String HEADER = "X-Tenant-Id";

  private CoreTenant() {}

  public static HttpHeaders headers(UUID tenantId) {
    HttpHeaders h = new HttpHeaders();
    if (tenantId != null) {
      h.set(HEADER, tenantId.toString());
    }
    return h;
  }
}
