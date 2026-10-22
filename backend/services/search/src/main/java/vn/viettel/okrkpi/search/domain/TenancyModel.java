package vn.viettel.okrkpi.search.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Mô hình thuê bao của tenant theo {@code sys.tenant.provisioned.v1}. Silo, bridge và airgap có chỉ
 * mục riêng mỗi tenant; pool dùng chỉ mục chung {@code pool_task_v<n>} với định tuyến và bí danh
 * lọc theo tenant (TL-03).
 */
public enum TenancyModel {
  SILO,
  BRIDGE,
  POOL,
  AIRGAP;

  public boolean sharedIndex() {
    return this == POOL;
  }

  public static Optional<TenancyModel> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
