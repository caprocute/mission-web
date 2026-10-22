package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import vn.viettel.okrkpi.config.domain.UiComponent;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Widget của plugin chuyên ngành có trong manifest của tenant (BR-18-12). CORE-08-15 cung cấp bản
 * thật; trước đó không có widget plugin nào.
 */
public interface PluginWidgets {

  /** Widget plugin dùng được với phiên bản registry {@code registryVersion}. */
  List<UiComponent> forTenant(TenantId tenantId, String registryVersion);

  PluginWidgets NONE = (tenant, version) -> List.of();
}
