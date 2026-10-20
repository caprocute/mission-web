package vn.viettel.okrkpi.shared.tenant;

import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nơi giữ tenant của luồng hiện hành, tách khỏi {@link TenantContext} để đổi được cơ chế. */
public interface TenantContextStorage {

  /** Tenant hiện hành hoặc {@code null}. */
  TenantId get();

  void set(TenantId tenant);

  void clear();
}
