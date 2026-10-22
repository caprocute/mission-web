package vn.viettel.okrkpi.iam.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.vtokr.org.v1.NhanSuDaThayDoi;

/**
 * Đọc EVT-16 {@code org.person.changed.v1} (thân Avro nhị phân như outbox của org-service, tenant ở header
 * {@code tenantid}) và áp lên Keycloak. Chỉ số: {@code iam_user_sync_lag} (từ lúc phát sự kiện tới khi Keycloak cập
 * nhật xong) và {@code iam_user_sync_events_total} theo kết quả. Nhật ký chỉ ghi mã, không ghi họ tên.
 */
public class PersonListener {

  static final String CONSUMER = "iam-user-sync";
  private static final Logger LOG = LoggerFactory.getLogger(PersonListener.class);

  private final PersonSyncService service;
  private final MeterRegistry meters;
  private final Timer lag;
  private final Clock clock;
  private final SpecificDatumReader<NhanSuDaThayDoi> reader;

  public PersonListener(PersonSyncService service, MeterRegistry meters, Clock clock) {
    this.service = service;
    this.meters = meters;
    this.clock = clock;
    this.lag = Timer.builder("iam_user_sync_lag").description("Độ trễ từ lúc phát EVT-16 tới khi Keycloak cập nhật")
        .publishPercentiles(0.5, 0.99).register(meters);
    EventContracts.trustContractPackages();
    this.reader = new SpecificDatumReader<>(NhanSuDaThayDoi.class);
  }

  @KafkaListener(topics = EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI, groupId = CONSUMER,
      containerFactory = "userSyncListenerFactory")
  public void onMessage(ConsumerRecord<String, byte[]> record) {
    NhanSuDaThayDoi e;
    try {
      e = reader.read(null, DecoderFactory.get().binaryDecoder(record.value(), null));
    } catch (IOException | RuntimeException ex) {
      throw new Directory.PermanentException("Thân EVT-16 không giải mã được");
    }
    UUID tenant = tenant(record);
    PersonSyncService.Outcome outcome = service.apply(new PersonSyncService.PersonState(tenant, e.getPersonId(),
        e.getDisplayName(), e.getStatus(), e.getAggregateVersion()));
    Instant occurred = occurredAt(record);
    if (occurred != null && outcome != PersonSyncService.Outcome.SKIPPED) {
      lag.record(Duration.between(occurred, clock.instant()).abs());
    }
    Counter.builder("iam_user_sync_events_total").tag("outcome", outcome.name()).register(meters).increment();
    LOG.debug("EVT-16 {} {} -> {}", tenant, e.getPersonId(), outcome);
  }

  private static UUID tenant(ConsumerRecord<?, ?> record) {
    String v = header(record, "tenantid");
    try {
      return UUID.fromString(v);
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new Directory.PermanentException("EVT-16 thiếu tenantid");
    }
  }

  private static Instant occurredAt(ConsumerRecord<?, ?> record) {
    String v = header(record, "occurredat");
    try {
      return v == null ? null : Instant.parse(v);
    } catch (DateTimeParseException ex) {
      return null;
    }
  }

  private static String header(ConsumerRecord<?, ?> record, String name) {
    Header h = record.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }
}
