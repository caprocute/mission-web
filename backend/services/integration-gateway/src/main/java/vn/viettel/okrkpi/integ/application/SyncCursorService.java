package vn.viettel.okrkpi.integ.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.SyncCursors;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Con trỏ đồng bộ kéo: đọc trước khi gọi đối tác, chỉ tiến sau khi cả trang đã xử lý; lưu độ trễ so
 * với thời điểm dữ liệu nguồn để giám sát.
 */
public class SyncCursorService {

  private final SyncCursors cursors;
  private final Clock clock;
  private final TransactionOperations tx;

  public SyncCursorService(SyncCursors cursors, Clock clock, TransactionOperations tx) {
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  public String load(TenantId tenant, UUID connectorId, String stream, String initial) {
    return TenantScope.call(
        tenant, () -> tx.execute(s -> cursors.find(tenant, connectorId, stream).orElse(initial)));
  }

  public void advance(
      TenantId tenant, UUID connectorId, String stream, String value, Instant sourceTime) {
    if (value == null || value.isEmpty() || value.length() > 255) {
      throw new IllegalArgumentException("Con trỏ phải có từ 1 đến 255 ký tự");
    }
    Instant now = clock.instant();
    Integer lag =
        sourceTime == null
            ? null
            : (int)
                Math.min(
                    Integer.MAX_VALUE, Math.max(0, Duration.between(sourceTime, now).toSeconds()));
    TenantScope.call(
        tenant,
        () -> {
          tx.executeWithoutResult(s -> cursors.save(tenant, connectorId, stream, value, now, lag));
          return null;
        });
  }
}
