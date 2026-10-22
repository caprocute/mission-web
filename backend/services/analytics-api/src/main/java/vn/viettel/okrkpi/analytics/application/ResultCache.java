package vn.viettel.okrkpi.analytics.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.domain.QueryResult;

/**
 * Bộ đệm kết quả truy vấn theo tenant (Valkey qua {@code TenantCache}, NEN-035). Khoá gồm các phần
 * chữ, số, gạch nối (đơn vị, kỳ, băm truy vấn và phạm vi), không có dấu hai chấm.
 */
public interface ResultCache {

  Optional<QueryResult> get(UUID tenant, List<String> key);

  void put(UUID tenant, List<String> key, QueryResult result, Duration ttl);
}
