package vn.viettel.okrkpi.config.infrastructure.listener;

import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.MessageListener;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Xoá đệm trạng thái kỳ của pod khi một pod khác chuyển trạng thái kỳ (CORE-08-03): nghe EVT-09
 * {@code config.period.locked.v1} và EVT-26 {@code config.period.unlocked.v1}. Chỉ đọc khoá bản ghi
 * {@code tenantId:periodId} (danh mục sự kiện), không giải mã thân Avro.
 *
 * <p>Mỗi pod dùng một consumer group riêng để pod nào cũng nhận đủ sự kiện; vì vậy không dùng inbox
 * dùng chung trong CSDL (inbox sẽ chỉ để pod đầu tiên xử lý). Việc xoá đệm vốn idempotent nên nhận
 * trùng không gây sai (wiki trang 14). Khoá sai dạng thì xoá cả đệm cho an toàn.
 */
public class PeriodCacheEvictionListener implements MessageListener<String, byte[]> {

  private static final Logger log = LoggerFactory.getLogger(PeriodCacheEvictionListener.class);

  private final PeriodConfigService periods;

  public PeriodCacheEvictionListener(PeriodConfigService periods) {
    this.periods = Objects.requireNonNull(periods, "periods");
  }

  @Override
  public void onMessage(ConsumerRecord<String, byte[]> record) {
    String key = record.key();
    int colon = key == null ? -1 : key.indexOf(':');
    try {
      if (colon > 0) {
        periods.evictState(
            TenantId.parse(key.substring(0, colon)), PeriodId.parse(key.substring(colon + 1)));
        return;
      }
    } catch (IllegalArgumentException e) {
      // rơi xuống xoá toàn bộ
    }
    log.warn("Khoá sự kiện kỳ sai dạng ở topic {}, xoá toàn bộ đệm trạng thái kỳ", record.topic());
    periods.evictAllStates();
  }
}
