package vn.viettel.okrkpi.config.infrastructure.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-03: EVT-09/EVT-26 khoá {@code tenant:period} xoá đúng mục đệm; khoá lạ xoá toàn bộ. */
class PeriodCacheEvictionListenerTest {

  private final PeriodConfigService periods = mock(PeriodConfigService.class);
  private final PeriodCacheEvictionListener listener = new PeriodCacheEvictionListener(periods);

  @Test
  void evictsTheKeyedPeriod() {
    UUID tenant = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    listener.onMessage(record(tenant + ":" + period));
    verify(periods).evictState(TenantId.of(tenant), PeriodId.of(period));
    verifyNoMoreInteractions(periods);
  }

  @Test
  void malformedOrMissingKeyEvictsEverything() {
    listener.onMessage(record("khong-hop-le"));
    listener.onMessage(record(null));
    verify(periods, org.mockito.Mockito.times(2)).evictAllStates();
    verifyNoMoreInteractions(periods);
  }

  private static ConsumerRecord<String, byte[]> record(String key) {
    return new ConsumerRecord<>("vt.okr.ky.da-khoa.v1", 0, 0L, key, new byte[0]);
  }
}
