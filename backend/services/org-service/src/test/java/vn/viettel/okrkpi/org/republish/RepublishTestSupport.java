package vn.viettel.okrkpi.org.republish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.org.application.republish.Buckets;
import vn.viettel.okrkpi.org.application.republish.EntityKind;
import vn.viettel.okrkpi.org.infrastructure.republish.PayloadEncoder;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;
import vn.vtokr.sys.v1.AuditEvent;

/** Kafka Testcontainers, bộ mã hoá Avro không registry và một bên nhận thử dựng bản sao từ {@code org.bootstrap.v1}. */
final class RepublishTestSupport {

  static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

  private RepublishTestSupport() {
  }

  static synchronized void start() {
    if (!KAFKA.isRunning()) {
      KAFKA.start();
      try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
        admin.createTopics(List.of(new NewTopic("org.bootstrap.v1", 3, (short) 1),
            new NewTopic("sys.audit.v1", 1, (short) 1))).all().get();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Avro nhị phân thuần; consumer thử giải mã bằng lớp sinh. */
  static final class PlainAvroEncoder implements PayloadEncoder {
    @Override
    public Encoded encode(String topic, SpecificRecord payload) {
      EventContracts.trustContractPackages();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
      try {
        new SpecificDatumWriter<SpecificRecord>(payload.getSchema()).write(payload, enc);
        enc.flush();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return new Encoded(out.toByteArray(), null);
    }
  }

  record Received(ConsumerRecord<String, byte[]> record, OrgBootstrap message) {
  }

  /** Đọc mọi thông điệp của một lần phát lại (đến khi đủ số đếm của {@code KET_THUC}). */
  static List<Received> readBatch(UUID batchId, Duration timeout) {
    List<Received> out = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> c = consumer()) {
      List<TopicPartition> parts = c.partitionsFor("org.bootstrap.v1").stream()
          .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
      c.assign(parts);
      c.seekToBeginning(parts);
      long deadline = System.nanoTime() + timeout.toNanos();
      Long expected = null;
      while (System.nanoTime() < deadline && (expected == null || out.size() < expected)) {
        for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(200))) {
          OrgBootstrap m = decode(r.value(), OrgBootstrap.class);
          if (!batchId.equals(m.getBatchId())) {
            continue;
          }
          out.add(new Received(r, m));
          if ("KET_THUC".equals(m.getMarker())) {
            expected = m.getSeq() + 1;
          }
        }
      }
    }
    out.sort((a, b) -> Long.compare(a.message().getSeq(), b.message().getSeq()));
    return out;
  }

  static List<AuditEvent> audits(UUID batchId) {
    List<AuditEvent> out = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> c = consumer()) {
      TopicPartition p = new TopicPartition("sys.audit.v1", 0);
      c.assign(List.of(p));
      c.seekToBeginning(List.of(p));
      for (int i = 0; i < 10; i++) {
        for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(300))) {
          AuditEvent e = decode(r.value(), AuditEvent.class);
          if (batchId.toString().equals(e.getObjectId())) {
            out.add(e);
          }
        }
      }
    }
    return out;
  }

  private static KafkaConsumer<String, byte[]> consumer() {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5_000);
    return new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer());
  }

  static <T extends SpecificRecord> T decode(byte[] bytes, Class<T> type) {
    EventContracts.trustContractPackages();
    try {
      return new SpecificDatumReader<>(type).read(null, DecoderFactory.get().binaryDecoder(bytes, null));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Bên nhận thử: upsert có điều kiện {@code source_version} như {@code org_replica} (CORE-00-03). */
  static final class Replica {
    private final Map<String, Long> versions = new HashMap<>();
    private final Map<String, Object> rows = new HashMap<>();

    void apply(List<Received> batch) {
      for (Received r : batch) {
        Object e = r.message().getEntity();
        switch (e) {
          case null -> { }
          case DonViDaThayDoi u -> upsert(EntityKind.ORG_UNIT, u.getUnitId(), u.getValidFrom(), u.getSourceVersion(), u);
          case NhanSuDaThayDoi p -> upsert(EntityKind.PERSON, p.getPersonId(), null, p.getSourceVersion(), p);
          case PhanCongDaThayDoi a ->
              upsert(EntityKind.ASSIGNMENT, a.getAssignmentId(), a.getValidFrom(), a.getSourceVersion(), a);
          default -> throw new IllegalStateException(e.getClass().getName());
        }
      }
    }

    private void upsert(EntityKind kind, UUID id, LocalDate from, long sv, Object row) {
      String key = kind + "|" + id + "|" + from;
      Long current = versions.get(key);
      if (current == null || sv > current) {
        versions.put(key, sv);
        rows.put(key, row);
      }
    }

    Map<String, Object> rows() {
      return Map.copyOf(rows);
    }

    /** Băm tổng theo bucket tính lại từ bản sao (cách tính ở wiki trang 18). */
    Map<String, String> bucketHashes(List<Integer> buckets) {
      Buckets.Accumulator acc = new Buckets.Accumulator();
      versions.forEach((key, sv) -> {
        String[] k = key.split("\\|");
        acc.add(EntityKind.valueOf(k[0]), UUID.fromString(k[1]), "null".equals(k[2]) ? null : LocalDate.parse(k[2]),
            sv);
      });
      return acc.hashes(buckets);
    }
  }
}
