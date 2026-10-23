package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.config.api.RecoveryKeySettingApi;
import vn.viettel.okrkpi.config.application.port.RecoveryKeySource;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * CORE-07-06: khoá phục hồi tổ chức cho MOD-02, MOD-03. Có nguồn SVC-04 thì mỗi tenant hỏi lại tối
 * đa một lần mỗi {@code ttl} và ghi giá trị mới vào cấu hình tenant trước khi đọc; SVC-04 lỗi thì
 * dùng giá trị đã lưu (khoá đã đồng bộ vẫn đúng tới lần thay khoá kế tiếp). Không có nguồn (dev,
 * kiểm thử) thì chỉ đọc cấu hình do quản trị khai báo.
 */
public class RecoveryKeySettingService implements RecoveryKeySettingApi {

  private static final Logger log = LoggerFactory.getLogger(RecoveryKeySettingService.class);

  private final TenantSettingService settings;
  private final RecoveryKeySource source;
  private final Clock clock;
  private final Duration ttl;
  private final Map<TenantId, Instant> checkedAt = new ConcurrentHashMap<>();

  /**
   * @param source {@code null} khi không cấu hình client SVC-04
   */
  public RecoveryKeySettingService(
      TenantSettingService settings, RecoveryKeySource source, Clock clock, Duration ttl) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.source = source;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
  }

  @Override
  public Optional<JsonNode> current(TenantId tenantId) {
    if (source != null) {
      refresh(tenantId);
    }
    return settings.get(tenantId, KEY, null);
  }

  private void refresh(TenantId tenantId) {
    Instant now = clock.instant();
    Instant last = checkedAt.get(tenantId);
    if (last != null && now.isBefore(last.plus(ttl))) {
      return;
    }
    checkedAt.put(tenantId, now);
    try {
      source
          .current(tenantId)
          .ifPresent(
              k -> {
                if (settings.syncRecoveryKey(tenantId, k.id(), k.fingerprint())) {
                  log.info("Đã đồng bộ khoá phục hồi tổ chức cho tenant {}", tenantId.value());
                }
              });
    } catch (RuntimeException e) {
      log.warn(
          "Không đồng bộ được khoá phục hồi cho tenant {}, dùng giá trị đã lưu: {}",
          tenantId.value(),
          e.toString());
    }
  }
}
