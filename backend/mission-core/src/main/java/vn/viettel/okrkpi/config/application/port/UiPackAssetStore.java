package vn.viettel.okrkpi.config.application.port;

import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nội dung tài nguyên của UI Pack theo {@code path} trong {@code assets_json} (CORE-08-10), để kiểm
 * kích thước, băm và làm sạch SVG. Kho tệp thật gắn sau; chưa có thì không đọc được tệp nào.
 */
public interface UiPackAssetStore {

  Optional<byte[]> read(TenantId tenantId, String path);

  UiPackAssetStore NONE = (tenant, path) -> Optional.empty();
}
