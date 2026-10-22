package vn.viettel.okrkpi.platform.tenanttest.analytics;

import java.util.List;
import java.util.Map;

/**
 * Chạy một câu SQL phân tích dưới ngữ cảnh bảo mật của một tenant: người dùng ClickHouse có row
 * policy, hoặc lời gọi Cube với {@code securityContext.tenantId} (TL-01 SVC-15; cách gắn là suy
 * luận, DV-15 chốt). Mỗi dòng là bản đồ tên cột sang giá trị dạng chuỗi.
 */
@FunctionalInterface
public interface TenantScopedQuery {

  List<Map<String, String>> query(String tenant, String sql);
}
