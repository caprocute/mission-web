package vn.viettel.okrkpi.iam.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.vtokr.org.v1.DonViDaThayDoi;

/** Đọc EVT-14 {@code org.unit.changed.v1}; thư chết {@code org.unit.changed.v1.dlq.iam-unit-sync}. */
public class UnitListener {

  static final String CONSUMER = "iam-unit-sync";
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final UnitSyncService service;
  private final Clock clock;
  private final Counter cleared;
  private final SpecificDatumReader<DonViDaThayDoi> reader;

  public UnitListener(UnitSyncService service, MeterRegistry meters, Clock clock) {
    this.service = service;
    this.clock = clock;
    this.cleared = Counter.builder("iam_unit_sync_context_cleared_total")
        .description("Ngữ cảnh làm việc bị gỡ vì đơn vị hết hiệu lực").register(meters);
    EventContracts.trustContractPackages();
    this.reader = new SpecificDatumReader<>(DonViDaThayDoi.class);
  }

  @KafkaListener(topics = EventTopics.EVT_14_DON_VI_DA_THAY_DOI, groupId = CONSUMER,
      containerFactory = "unitSyncListenerFactory")
  public void onMessage(ConsumerRecord<String, byte[]> record) {
    DonViDaThayDoi e;
    try {
      e = reader.read(null, DecoderFactory.get().binaryDecoder(record.value(), null));
    } catch (IOException | RuntimeException ex) {
      throw new Directory.PermanentException("Thân EVT-14 không giải mã được");
    }
    Header h = record.headers().lastHeader("tenantid");
    UUID tenant;
    try {
      tenant = UUID.fromString(new String(h.value(), StandardCharsets.UTF_8));
    } catch (RuntimeException ex) {
      throw new Directory.PermanentException("EVT-14 thiếu tenantid");
    }
    cleared.increment(service.apply(tenant, e.getUnitId(), e.getValidTo(), LocalDate.now(clock.withZone(BUSINESS_ZONE))));
  }
}
