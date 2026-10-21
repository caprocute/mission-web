package vn.viettel.okrkpi.org.infrastructure.republish;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeaders;
import vn.viettel.okrkpi.org.application.port.BootstrapPublisher;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Phát {@code org.bootstrap.v1} và {@code sys.audit.v1} bằng producer Kafka idempotent. Tiêu đề trùng phong bì của
 * outbox (NEN-011: {@code id}, {@code tenantid}, {@code aggregateversion}, {@code ce_type}, {@code classification},
 * {@code occurredat}, {@code apicurio.value.globalId} 8 byte) để inbox NEN-012 của bên nhận đọc như sự kiện thường.
 */
public class KafkaBootstrapPublisher implements BootstrapPublisher {

  public static final String BOOTSTRAP_TOPIC = "org.bootstrap.v1";
  public static final String AUDIT_TOPIC = "sys.audit.v1";

  private final Producer<String, byte[]> producer;
  private final PayloadEncoder encoder;
  private final Clock clock;
  private final AtomicReference<Exception> failure = new AtomicReference<>();
  private final List<Future<RecordMetadata>> pending = new ArrayList<>();

  public KafkaBootstrapPublisher(Producer<String, byte[]> producer, PayloadEncoder encoder, Clock clock) {
    this.producer = producer;
    this.encoder = encoder;
    this.clock = clock;
  }

  @Override
  public void publish(UUID tenant, UUID aggregateId, long aggregateVersion, OrgBootstrap message) {
    Exception earlier = failure.get();
    if (earlier != null) {
      throw new IllegalStateException("Phát org.bootstrap.v1 đã lỗi, dừng lần phát lại", earlier);
    }
    ProducerRecord<String, byte[]> record = record(BOOTSTRAP_TOPIC, tenant, aggregateId, aggregateVersion, null,
        "OrgBootstrap", message);
    pending.add(producer.send(record, (meta, e) -> {
      if (e != null) {
        failure.compareAndSet(null, e);
      }
    }));
    if (pending.size() >= 10_000) {
      flush();
    }
  }

  @Override
  public void flush() {
    producer.flush();
    for (Future<RecordMetadata> f : pending) {
      await(f);
    }
    pending.clear();
    Exception e = failure.get();
    if (e != null) {
      throw new IllegalStateException("Có thông điệp org.bootstrap.v1 không được Kafka xác nhận", e);
    }
  }

  @Override
  public void audit(UUID tenant, UUID batchId, UUID actorId, AuditEvent event) {
    await(producer.send(record(AUDIT_TOPIC, tenant, batchId, 0, actorId, "AuditEvent", event)));
  }

  private ProducerRecord<String, byte[]> record(String topic, UUID tenant, UUID aggregateId, long version,
      UUID actorId, String type, SpecificRecord payload) {
    PayloadEncoder.Encoded encoded = encoder.encode(topic, payload);
    RecordHeaders h = new RecordHeaders();
    h.add("id", utf8(UuidV7.next(clock).toString()));
    h.add("tenantid", utf8(tenant.toString()));
    h.add("aggregateversion", utf8(Long.toString(version)));
    h.add("ce_type", utf8(type));
    h.add("classification", utf8("C1"));
    h.add("occurredat", utf8(clock.instant().toString()));
    if (actorId != null) {
      h.add("actorid", utf8(actorId.toString()));
    }
    if (encoded.globalId() != null) {
      h.add("apicurio.value.globalId", ByteBuffer.allocate(Long.BYTES).putLong(encoded.globalId()).array());
    }
    return new ProducerRecord<>(topic, null, tenant + ":" + aggregateId, encoded.bytes(), h);
  }

  private static void await(Future<RecordMetadata> f) {
    try {
      f.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi chờ Kafka xác nhận", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Kafka không xác nhận thông điệp", e.getCause());
    }
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
