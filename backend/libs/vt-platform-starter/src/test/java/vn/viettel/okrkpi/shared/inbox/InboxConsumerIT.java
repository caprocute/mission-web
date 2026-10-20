package vn.viettel.okrkpi.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.MockApicurioRegistryServer;
import vn.viettel.okrkpi.shared.outbox.OutboxFixtures;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/**
 * Kiểm thử tích hợp 5 kịch bản bắt buộc và tác vụ dọn hộp thư đến cho {@link InboxMessageProcessor}
 * và {@link InboxPurgeJob} ({@code NEN-012}).
 */
class InboxConsumerIT {

  private static final String SCHEMA = "svc_eval";
  private static final String TOPIC = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT;
  private static final String CONSUMER = "svc-eval.tien-do";
  private static final TenantId TENANT = OutboxFixtures.TENANT;

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

  private static MockApicurioRegistryServer registryServer;
  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager txManager;
  private static ApicurioPayloadSerializer serializer;
  private static ApicurioPayloadDeserializer deserializer;
  private static KafkaTemplate<String, byte[]> kafkaTemplate;

  @BeforeAll
  static void setUpCluster() throws Exception {
    POSTGRES.start();
    KAFKA.start();
    registryServer = new MockApicurioRegistryServer();
    registryServer.registerAvroSchema(
        "vn.vtokr.task.v1", TOPIC + "-value", TienDoDaCapNhat.getClassSchema());
    registryServer.registerAvroSchema(
        "default", TOPIC + "-value", TienDoDaCapNhat.getClassSchema());
    registryServer.registerAvroSchema(
        "vn.vtokr.task.v1", "TienDoDaCapNhat", TienDoDaCapNhat.getClassSchema());

    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setURL(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());

    try (Connection conn = ds.getConnection();
        Statement st = conn.createStatement()) {
      st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
      st.execute(
          """
          CREATE TABLE svc_eval.inbox_processed (
              event_id UUID NOT NULL,
              consumer VARCHAR(100) NOT NULL,
              tenant_id UUID NOT NULL,
              source_topic VARCHAR(128) NOT NULL,
              kafka_partition INT NOT NULL,
              kafka_offset BIGINT NOT NULL,
              processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
              PRIMARY KEY (consumer, event_id)
          )
          """);
      st.execute(
          """
          CREATE TABLE svc_eval.inbox_aggregate_version (
              consumer VARCHAR(100) NOT NULL,
              aggregate_type VARCHAR(64) NOT NULL,
              aggregate_id UUID NOT NULL,
              tenant_id UUID NOT NULL,
              last_version BIGINT NOT NULL,
              last_event_id UUID,
              updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
              PRIMARY KEY (consumer, aggregate_type, aggregate_id)
          )
          """);
      st.execute(
          """
          CREATE TABLE svc_eval.eval_progress_projection (
              task_id UUID PRIMARY KEY,
              tenant_id UUID NOT NULL,
              last_version BIGINT NOT NULL,
              progress_percent INT NOT NULL,
              rls_tenant VARCHAR(64) NOT NULL
          )
          """);
      st.execute(
          """
          CREATE OR REPLACE FUNCTION svc_eval.fn_purge_inbox_processed(
              p_retention INTERVAL DEFAULT INTERVAL '14 days',
              p_batch_size INT DEFAULT 5000
          ) RETURNS INT
          LANGUAGE plpgsql
          AS $$
          DECLARE
              v_deleted INT;
          BEGIN
              WITH doomed AS (
                  SELECT consumer, event_id FROM svc_eval.inbox_processed
                  WHERE processed_at < NOW() - p_retention
                  LIMIT p_batch_size
              )
              DELETE FROM svc_eval.inbox_processed t
              USING doomed d
              WHERE t.consumer = d.consumer AND t.event_id = d.event_id;
              GET DIAGNOSTICS v_deleted = ROW_COUNT;
              RETURN v_deleted;
          END;
          $$;
          """);
    }

    jdbc = new JdbcTemplate(ds);
    txManager = new DataSourceTransactionManager(ds);
    serializer = new ApicurioPayloadSerializer(registryServer.baseUrl(), Map.of());
    deserializer = new ApicurioPayloadDeserializer(registryServer.baseUrl(), Map.of());

    Map<String, Object> prodProps = new HashMap<>();
    prodProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    prodProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    prodProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(prodProps));
  }

  @AfterAll
  static void tearDownCluster() {
    if (deserializer != null) {
      deserializer.close();
    }
    if (registryServer != null) {
      registryServer.close();
    }
    KAFKA.stop();
    POSTGRES.stop();
  }

  @Test
  void scenario1_newEventProcessesBusinessLogicRecordsInboxAndUpdatesVersionAndSetsTenantRls()
      throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InboxMetrics metrics = new InboxMetrics(registry);
    InboxMessageProcessor processor = newProcessor(metrics);
    RecordingHandler handler = new RecordingHandler(jdbc);
    InboxEndpoint endpoint = handler.endpoint();

    UUID eventId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> record = buildRecord(eventId, taskId, 1, 10L);
    CountingAck ack = new CountingAck();

    InboxOutcome outcome = processor.process(endpoint, record, ack);

    assertThat(outcome).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(ack.count.get()).isEqualTo(1);
    assertThat(handler.invocations.get()).isEqualTo(1);
    assertThat(metrics.count(CONSUMER, TOPIC, InboxOutcome.PROCESSED)).isEqualTo(1.0d);

    Long storedVersion =
        jdbc.queryForObject(
            "SELECT last_version FROM svc_eval.inbox_aggregate_version "
                + "WHERE consumer = ? AND aggregate_type = 'Task' AND aggregate_id = ?",
            Long.class,
            CONSUMER,
            taskId);
    assertThat(storedVersion).isEqualTo(1L);

    String rlsTenant =
        jdbc.queryForObject(
            "SELECT rls_tenant FROM svc_eval.eval_progress_projection WHERE task_id = ?",
            String.class,
            taskId);
    assertThat(rlsTenant).isEqualTo(TENANT.value().toString());
  }

  @Test
  void scenario2_fiveDuplicateDeliveriesExecuteHandlerOnlyOnceAndCountFourDuplicates()
      throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InboxMetrics metrics = new InboxMetrics(registry);
    InboxMessageProcessor processor = newProcessor(metrics);
    RecordingHandler handler = new RecordingHandler(jdbc);
    InboxEndpoint endpoint = handler.endpoint();

    UUID eventId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();

    for (int i = 0; i < 5; i++) {
      CountingAck ack = new CountingAck();
      InboxOutcome outcome =
          processor.process(endpoint, buildRecord(eventId, taskId, 1, 20L + i), ack);
      assertThat(outcome)
          .isEqualTo(i == 0 ? InboxOutcome.PROCESSED : InboxOutcome.DUPLICATE_SKIPPED);
      assertThat(ack.count.get()).isEqualTo(1);
    }

    assertThat(handler.invocations.get()).isEqualTo(1);
    assertThat(metrics.count(CONSUMER, TOPIC, InboxOutcome.PROCESSED)).isEqualTo(1.0d);
    assertThat(metrics.count(CONSUMER, TOPIC, InboxOutcome.DUPLICATE_SKIPPED)).isEqualTo(4.0d);
  }

  @Test
  void scenario3_outOfOrderVersion5ThenVersion3SkipsStaleVersion3WithoutCallingHandler()
      throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InboxMetrics metrics = new InboxMetrics(registry);
    InboxMessageProcessor processor = newProcessor(metrics);
    RecordingHandler handler = new RecordingHandler(jdbc);
    InboxEndpoint endpoint = handler.endpoint();

    UUID taskId = UUID.randomUUID();
    UUID eventV5 = UUID.randomUUID();
    UUID eventV3 = UUID.randomUUID();

    InboxOutcome first =
        processor.process(endpoint, buildRecord(eventV5, taskId, 5, 30L), new CountingAck());
    InboxOutcome second =
        processor.process(endpoint, buildRecord(eventV3, taskId, 3, 31L), new CountingAck());

    assertThat(first).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(second).isEqualTo(InboxOutcome.STALE_SKIPPED);
    assertThat(handler.invocations.get()).isEqualTo(1);
    assertThat(metrics.count(CONSUMER, TOPIC, InboxOutcome.STALE_SKIPPED)).isEqualTo(1.0d);

    Integer processedRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM svc_eval.inbox_processed "
                + "WHERE consumer = ? AND event_id IN (?, ?)",
            Integer.class,
            CONSUMER,
            eventV5,
            eventV3);
    assertThat(processedRows).isEqualTo(2);

    Long lastVersion =
        jdbc.queryForObject(
            "SELECT last_version FROM svc_eval.inbox_aggregate_version "
                + "WHERE consumer = ? AND aggregate_type = 'Task' AND aggregate_id = ?",
            Long.class,
            CONSUMER,
            taskId);
    assertThat(lastVersion).isEqualTo(5L);
  }

  @Test
  void scenario4_transientErrorRetriesThenRoutesToDlqAndRollsBackWhilePermanentGoesStraightToDlq()
      throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InboxMetrics metrics = new InboxMetrics(registry);
    InboxProperties.Retry retryCfg =
        new InboxProperties.Retry(
            3,
            Duration.ofMillis(1),
            1.0,
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(25)));
    InboxRetryDlqPublisher publisher = new InboxRetryDlqPublisher(kafkaTemplate);
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            jdbc,
            txManager,
            SCHEMA,
            deserializer,
            publisher,
            metrics,
            retryCfg,
            Clock.fixed(Instant.now()));

    FailingHandler handler = new FailingHandler(jdbc);
    InboxEndpoint endpoint = handler.endpoint();

    // 4a. Lỗi tạm thời ở bậc 0 -> sang <topic>.retry.<consumer> và rollback bảng nghiệp vụ + inbox
    UUID transientEventId = UUID.randomUUID();
    UUID transientTaskId = UUID.randomUUID();
    handler.failTransient = true;
    InboxOutcome retryOutcome =
        processor.process(
            endpoint, buildRecord(transientEventId, transientTaskId, 1, 40L), new CountingAck());
    assertThat(retryOutcome).isEqualTo(InboxOutcome.RETRIED);
    assertThat(handler.attempts.get()).isEqualTo(3);

    Integer inboxRowCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM svc_eval.inbox_processed WHERE event_id = ?",
            Integer.class,
            transientEventId);
    assertThat(inboxRowCount).isZero();

    String retryTopic = InboxRetryDlqPublisher.retryTopic(TOPIC, CONSUMER);
    ConsumerRecord<String, byte[]> retryRecord = consumeById(retryTopic, transientEventId);
    assertThat(headerUtf8(retryRecord, InboxHeaders.RETRY_TIER)).isEqualTo("1");

    // Khi đã hết 3 tầng retry (tier = 3) mà vẫn lỗi tạm thời -> sang <topic>.dlq.<consumer>
    ConsumerRecord<String, byte[]> exhaustedRecord =
        buildRecord(transientEventId, transientTaskId, 1, 41L);
    exhaustedRecord.headers().add(InboxHeaders.RETRY_TIER, "3".getBytes(StandardCharsets.UTF_8));
    handler.attempts.set(0);
    InboxOutcome dlqAfterExhausted =
        processor.process(endpoint, exhaustedRecord, new CountingAck());
    assertThat(dlqAfterExhausted).isEqualTo(InboxOutcome.DLQ);
    String dlqTopic = InboxRetryDlqPublisher.dlqTopic(TOPIC, CONSUMER);
    ConsumerRecord<String, byte[]> dlqRecord1 = consumeById(dlqTopic, transientEventId);
    assertThat(headerUtf8(dlqRecord1, InboxHeaders.DLQ_REASON)).contains("Lỗi kết nối tạm thời");

    // 4b. Lỗi vĩnh viễn -> vào thẳng <topic>.dlq.<consumer> ngay lần gọi đầu tiên
    UUID permanentEventId = UUID.randomUUID();
    UUID permanentTaskId = UUID.randomUUID();
    handler.failTransient = false;
    handler.attempts.set(0);
    InboxOutcome permanentOutcome =
        processor.process(
            endpoint, buildRecord(permanentEventId, permanentTaskId, 1, 42L), new CountingAck());
    assertThat(permanentOutcome).isEqualTo(InboxOutcome.DLQ);
    assertThat(handler.attempts.get()).isEqualTo(1);

    ConsumerRecord<String, byte[]> dlqRecord2 = consumeById(dlqTopic, permanentEventId);
    assertThat(headerUtf8(dlqRecord2, InboxHeaders.DLQ_ERROR_CODE)).isEqualTo("INVALID_TASK_STATE");
  }

  @Test
  void scenario5_tenThousandEventsWithDuplicatesAndOutOfOrderMaintainExactFinalProjection()
      throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InboxMetrics metrics = new InboxMetrics(registry);
    InboxMessageProcessor processor = newProcessor(metrics);
    InMemoryProjectionHandler handler = new InMemoryProjectionHandler();
    InboxEndpoint endpoint = handler.endpoint();

    int totalDeliveries = 10_000;
    int duplicateDeliveries = 1_000; // 10%
    int outOfOrderDeliveries = 500; // 5%
    int normalNewDeliveries = totalDeliveries - duplicateDeliveries - outOfOrderDeliveries;

    int aggregateCount = 200;
    List<UUID> aggregateIds = new ArrayList<>(aggregateCount);
    for (int i = 0; i < aggregateCount; i++) {
      aggregateIds.add(UUID.randomUUID());
    }

    Map<UUID, Integer> maxVersionByAggregate = new HashMap<>();
    List<ConsumerRecord<String, byte[]>> stream = new ArrayList<>(totalDeliveries);
    List<ConsumerRecord<String, byte[]>> uniqueRecordsPool = new ArrayList<>(normalNewDeliveries);
    Random rnd = new Random(42L);
    long offsetSeq = 1_000L;

    for (int i = 0; i < normalNewDeliveries; i++) {
      UUID aggId = aggregateIds.get(i % aggregateCount);
      int nextVer = maxVersionByAggregate.getOrDefault(aggId, 1) + 1;
      maxVersionByAggregate.put(aggId, nextVer);
      ConsumerRecord<String, byte[]> rec =
          buildRecord(UUID.randomUUID(), aggId, nextVer, offsetSeq++);
      stream.add(rec);
      uniqueRecordsPool.add(rec);
    }

    for (int i = 0; i < duplicateDeliveries; i++) {
      ConsumerRecord<String, byte[]> orig =
          uniqueRecordsPool.get(rnd.nextInt(uniqueRecordsPool.size()));
      UUID origEventId = UUID.fromString(headerUtf8(orig, OutboxHeaders.EVENT_ID));
      UUID origAggId = UUID.fromString(orig.key().substring(orig.key().indexOf(':') + 1));
      int origVer = Integer.parseInt(headerUtf8(orig, OutboxHeaders.AGGREGATE_VERSION));
      stream.add(buildRecord(origEventId, origAggId, origVer, offsetSeq++));
    }

    for (int i = 0; i < outOfOrderDeliveries; i++) {
      UUID aggId = aggregateIds.get(i % aggregateCount);
      stream.add(buildRecord(UUID.randomUUID(), aggId, 1, offsetSeq++));
    }

    assertThat(stream).hasSize(totalDeliveries);
    CountingAck ack = new CountingAck();
    for (ConsumerRecord<String, byte[]> rec : stream) {
      processor.process(endpoint, rec, ack);
    }

    assertThat(ack.count.get()).isEqualTo(totalDeliveries);
    assertThat(metrics.count(CONSUMER, TOPIC, InboxOutcome.PROCESSED))
        .isEqualTo((double) normalNewDeliveries);
    assertThat(metrics.count(CONSUMER, TOPIC, InboxOutcome.DUPLICATE_SKIPPED))
        .isEqualTo((double) duplicateDeliveries);
    assertThat(metrics.count(CONSUMER, TOPIC, InboxOutcome.STALE_SKIPPED))
        .isEqualTo((double) outOfOrderDeliveries);

    for (UUID aggId : aggregateIds) {
      int expectedMax = maxVersionByAggregate.get(aggId);
      assertThat(handler.latestAppliedVersion.get(aggId)).isEqualTo(expectedMax);
      Long dbVer =
          jdbc.queryForObject(
              "SELECT last_version FROM svc_eval.inbox_aggregate_version "
                  + "WHERE consumer = ? AND aggregate_type = 'Task' AND aggregate_id = ?",
              Long.class,
              CONSUMER,
              aggId);
      assertThat(dbVer).isEqualTo((long) expectedMax);
    }
  }

  @Test
  void purgeJobDeletesExpiredInboxRowsInBatchesAndKeepsRecentOnes() {
    UUID expiredId = UUID.randomUUID();
    UUID recentId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO svc_eval.inbox_processed "
            + "(event_id, consumer, tenant_id, source_topic, kafka_partition, kafka_offset, "
            + "processed_at) "
            + "VALUES (?, ?, ?, ?, 0, 1, NOW() - INTERVAL '20 days')",
        expiredId,
        CONSUMER,
        TENANT.value(),
        TOPIC);
    jdbc.update(
        "INSERT INTO svc_eval.inbox_processed "
            + "(event_id, consumer, tenant_id, source_topic, kafka_partition, kafka_offset, "
            + "processed_at) "
            + "VALUES (?, ?, ?, ?, 0, 2, NOW())",
        recentId,
        CONSUMER,
        TENANT.value(),
        TOPIC);

    InboxPurgeJob purgeJob =
        new InboxPurgeJob(
            jdbc,
            txManager,
            SCHEMA,
            new InboxProperties.Purge(true, "14 days", 100, 5, "0 15 3 * * *"));
    int deleted = purgeJob.purgeExpired();
    assertThat(deleted).isGreaterThanOrEqualTo(1);

    Integer remainingExpired =
        jdbc.queryForObject(
            "SELECT count(*) FROM svc_eval.inbox_processed WHERE event_id = ?",
            Integer.class,
            expiredId);
    Integer remainingRecent =
        jdbc.queryForObject(
            "SELECT count(*) FROM svc_eval.inbox_processed WHERE event_id = ?",
            Integer.class,
            recentId);
    assertThat(remainingExpired).isZero();
    assertThat(remainingRecent).isEqualTo(1);
  }

  private static InboxMessageProcessor newProcessor(InboxMetrics metrics) {
    InboxProperties.Retry retryCfg = new InboxProperties.Retry(null, null, null, null);
    InboxRetryDlqPublisher publisher = new InboxRetryDlqPublisher(kafkaTemplate);
    return new InboxMessageProcessor(
        jdbc,
        txManager,
        SCHEMA,
        deserializer,
        publisher,
        metrics,
        retryCfg,
        Clock.fixed(Instant.now()));
  }

  private static ConsumerRecord<String, byte[]> buildRecord(
      UUID eventId, UUID taskId, int version, long offset) {
    TienDoDaCapNhat payload = OutboxFixtures.tienDo(taskId, version);
    SerializedPayload serialized = serializer.serialize(TOPIC, payload);

    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(TOPIC, 0, offset, TENANT.value() + ":" + taskId, serialized.bytes());
    addHeader(record, OutboxHeaders.EVENT_ID, eventId.toString());
    addHeader(record, OutboxHeaders.TENANT_ID, TENANT.value().toString());
    addHeader(record, OutboxHeaders.AGGREGATE_VERSION, Integer.toString(version));
    addHeader(record, OutboxHeaders.CE_TYPE, "TienDoDaCapNhat");
    addHeader(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    addHeader(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    record
        .headers()
        .add(
            OutboxHeaders.APICURIO_GLOBAL_ID,
            ByteBuffer.allocate(Long.BYTES).putLong(serialized.globalId()).array());
    return record;
  }

  private static void addHeader(ConsumerRecord<String, byte[]> record, String key, String value) {
    record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  private static String headerUtf8(ConsumerRecord<String, byte[]> record, String key) {
    var h = record.headers().lastHeader(key);
    return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  private static ConsumerRecord<String, byte[]> consumeById(String topic, UUID eventId) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "inbox-it-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

    try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(Collections.singletonList(topic));
      long deadline = System.currentTimeMillis() + 5_000L;
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
        for (ConsumerRecord<String, byte[]> rec : records) {
          if (eventId.toString().equals(headerUtf8(rec, OutboxHeaders.EVENT_ID))) {
            return rec;
          }
        }
      }
    }
    throw new AssertionError("Không tìm thấy sự kiện " + eventId + " trên topic " + topic);
  }

  static final class CountingAck implements Acknowledgment {
    final AtomicInteger count = new AtomicInteger();

    @Override
    public void acknowledge() {
      count.incrementAndGet();
    }
  }

  static final class RecordingHandler {
    final JdbcTemplate jdbc;
    final AtomicInteger invocations = new AtomicInteger();

    RecordingHandler(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @InboxListener(topic = TOPIC, consumer = CONSUMER, aggregateType = "Task")
    public void handle(InboxEnvelope<TienDoDaCapNhat> envelope) {
      invocations.incrementAndGet();
      assertThat(TenantContext.require()).isEqualTo(envelope.tenantId());
      String rls =
          jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
      jdbc.update(
          """
          INSERT INTO svc_eval.eval_progress_projection
              (task_id, tenant_id, last_version, progress_percent, rls_tenant)
          VALUES (?, ?, ?, ?, ?)
          ON CONFLICT (task_id) DO UPDATE
              SET last_version = EXCLUDED.last_version,
                  progress_percent = EXCLUDED.progress_percent,
                  rls_tenant = EXCLUDED.rls_tenant
          """,
          envelope.aggregateId(),
          envelope.tenantId().value(),
          envelope.aggregateVersion(),
          envelope.payload().getProgressPct().intValue(),
          rls);
    }

    InboxEndpoint endpoint() throws Exception {
      var m = RecordingHandler.class.getDeclaredMethod("handle", InboxEnvelope.class);
      return InboxEndpoint.fromMethod(this, m, m.getAnnotation(InboxListener.class));
    }
  }

  static final class FailingHandler {
    final JdbcTemplate jdbc;
    final AtomicInteger attempts = new AtomicInteger();
    volatile boolean failTransient = true;

    FailingHandler(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @InboxListener(topic = TOPIC, consumer = CONSUMER, aggregateType = "Task")
    public void handle(InboxEnvelope<TienDoDaCapNhat> envelope) {
      attempts.incrementAndGet();
      jdbc.update(
          """
          INSERT INTO svc_eval.eval_progress_projection
              (task_id, tenant_id, last_version, progress_percent, rls_tenant)
          VALUES (?, ?, ?, ?, 'pending')
          ON CONFLICT (task_id) DO NOTHING
          """,
          envelope.aggregateId(),
          envelope.tenantId().value(),
          envelope.aggregateVersion(),
          envelope.payload().getProgressPct().intValue());
      if (failTransient) {
        throw new TransientInboxException("Lỗi kết nối tạm thời");
      }
      throw new PermanentInboxException("INVALID_TASK_STATE", "Trạng thái công việc không hợp lệ");
    }

    InboxEndpoint endpoint() throws Exception {
      var m = FailingHandler.class.getDeclaredMethod("handle", InboxEnvelope.class);
      return InboxEndpoint.fromMethod(this, m, m.getAnnotation(InboxListener.class));
    }
  }

  static final class InMemoryProjectionHandler {
    final Map<UUID, Integer> latestAppliedVersion = new ConcurrentHashMap<>();

    @InboxListener(topic = TOPIC, consumer = CONSUMER, aggregateType = "Task")
    public void handle(InboxEnvelope<TienDoDaCapNhat> envelope) {
      latestAppliedVersion.put(envelope.aggregateId(), (int) envelope.aggregateVersion());
    }

    InboxEndpoint endpoint() throws Exception {
      var m = InMemoryProjectionHandler.class.getDeclaredMethod("handle", InboxEnvelope.class);
      return InboxEndpoint.fromMethod(this, m, m.getAnnotation(InboxListener.class));
    }
  }
}
