package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.config.api.RecoveryKeySettingApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.secret.port.RecoveryKeys;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.RecoveryKey;

/**
 * Khoá phục hồi tổ chức đọc từ cấu hình tenant {@value #KEY} (lược đồ {@code json.object}): {@code
 * {"fingerprints":["<sha256 SPKI hex>",…],"recipientId":"<uuid>"}}. {@code fingerprints} nhận nhiều
 * giá trị để xoay vòng khoá phục hồi; {@code recipientId} là mã người nhận đại diện ghi ở {@code
 * recipient_person_id} của bản bọc (suy luận, TL-03 không có cột riêng cho khoá phục hồi). Cấu hình
 * sai dạng coi như chưa khai báo, nên lưu nội dung mật bị từ chối. Giá trị được đồng bộ từ SVC-04
 * qua {@link RecoveryKeySettingApi} khi có client.
 */
public class TenantSettingRecoveryKeys implements RecoveryKeys {

  public static final String KEY = RecoveryKeySettingApi.KEY;

  private static final Logger log = LoggerFactory.getLogger(TenantSettingRecoveryKeys.class);
  private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

  private final RecoveryKeySettingApi settings;

  public TenantSettingRecoveryKeys(RecoveryKeySettingApi settings) {
    this.settings = settings;
  }

  @Override
  public Optional<RecoveryKey> of(TenantId tenant) {
    return settings.current(tenant).flatMap(TenantSettingRecoveryKeys::parse);
  }

  static Optional<RecoveryKey> parse(JsonNode v) {
    JsonNode fps = v.path("fingerprints");
    JsonNode recipient = v.path("recipientId");
    if (!fps.isArray() || !recipient.isTextual()) {
      log.warn("Bỏ qua cấu hình {} vì thiếu fingerprints hoặc recipientId", KEY);
      return Optional.empty();
    }
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode f : fps) {
      if (!f.isTextual() || !HEX64.matcher(f.asText()).matches()) {
        log.warn("Bỏ qua cấu hình {} vì vân tay khoá sai dạng", KEY);
        return Optional.empty();
      }
      ids.add(f.asText());
    }
    try {
      return Optional.of(new RecoveryKey(ids, UUID.fromString(recipient.asText())));
    } catch (IllegalArgumentException e) {
      log.warn("Bỏ qua cấu hình {} vì recipientId sai dạng", KEY);
      return Optional.empty();
    }
  }
}
