package vn.viettel.okrkpi.search.application;

import vn.viettel.okrkpi.search.domain.IndexNames;

/**
 * Phạm vi dựng lại chỉ mục: một tenant có chỉ mục riêng (silo, bridge) hoặc toàn bộ chỉ mục chung
 * của cell pool.
 *
 * @param tenantId {@code null} khi dựng lại chỉ mục chung
 */
public record RebuildScope(String tenantId) {

  public static final RebuildScope POOL = new RebuildScope(null);

  public RebuildScope {
    tenantId = tenantId == null ? null : IndexNames.tenant(tenantId);
  }

  public static RebuildScope parse(String value) {
    return "pool".equalsIgnoreCase(value) ? POOL : new RebuildScope(value);
  }

  public boolean pool() {
    return tenantId == null;
  }
}
