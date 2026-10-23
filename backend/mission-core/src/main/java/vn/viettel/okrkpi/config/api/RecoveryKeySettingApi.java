package vn.viettel.okrkpi.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Khoá phục hồi tổ chức của tenant (ADR-27) ở cấu hình {@value #KEY}: {@code
 * {"fingerprints":["<sha256 SPKI hex>",…],"recipientId":"<uuid>"}}. Khi có client SVC-04, giá trị
 * được đồng bộ theo {@code org.recovery_key} đang hiệu lực (CORE-07-06); vân tay của khoá đã thay
 * vẫn giữ trong danh sách để nội dung mật đã bọc cho khoá cũ còn được chia sẻ, thu hồi.
 */
public interface RecoveryKeySettingApi {

  String KEY = "e2ee.recovery-key";

  Optional<JsonNode> current(TenantId tenantId);
}
