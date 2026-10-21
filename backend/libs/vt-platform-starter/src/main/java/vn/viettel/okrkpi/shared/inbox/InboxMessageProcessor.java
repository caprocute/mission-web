package vn.viettel.okrkpi.shared.inbox;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.observability.LoggingContext;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * NEN-012 · ADR-03 · Bộ xử lý thông điệp chuẩn cho mọi {@link InboxListener} theo đúng thứ tự của
 * TL-03 ("Inbox idempotent", L4261–4320):
 *
 * <ol>
 *   <li>Đọc tiêu đề CloudEvents ({@code id}, {@code tenantid}, {@code aggregateversion}, {@code
 *       traceparent}...) và giải tuần tự thân Avro; thiếu {@code tenantid}/{@code id} hoặc lỗi lược
 *       đồ là lỗi vĩnh viễn → chuyển thẳng sang {@code <topic>.dlq.<consumer>}.
 *   <li>Đặt {@link TenantContext} và mở giao dịch CSDL cục bộ ({@code SET LOCAL app.tenant_id}).
 *   <li>{@code INSERT INTO <schema>.inbox_processed ... ON CONFLICT (consumer, event_id) DO NOTHING
 *       RETURNING event_id}; 0 dòng trả về → bỏ qua sự kiện trùng, tăng {@code duplicate_skipped},
 *       commit và xác nhận offset.
 *   <li>Khi {@code aggregateType} khác rỗng: upsert có điều kiện vào {@code
 *       <schema>.inbox_aggregate_version} với {@code WHERE v.last_version < EXCLUDED.last_version};
 *       0 dòng trả về → bỏ qua sự kiện cũ đến muộn, tăng {@code stale_skipped}, vẫn giữ dòng {@code
 *       inbox_processed}, commit và xác nhận offset.
 *   <li>Gọi phương thức nghiệp vụ trong cùng giao dịch.
 *   <li>Commit giao dịch rồi mới gọi {@code ack.acknowledge()} ở {@code afterCommit}.
 * </ol>
 */
public class InboxMessageProcessor {

  private static final Logger log = LoggerFactory.getLogger(InboxMessageProcessor.class);

  static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
  static final String SET_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, true)";
  static final String RETRY_EXHAUSTED_CODE = "INBOX_RETRY_EXHAUSTED";

  private static final TextMapGetter<Headers> KAFKA_HEADER_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
          if (carrier == null) {
            return Collections.emptyList();
          }
          return () -> java.util.Arrays.stream(carrier.toArray()).map(Header::key).iterator();
        }

        @Override
        public String get(Headers carrier, String key) {
          return headerString(carrier, key);
        }
      };

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactionTemplate;
  private final InboxPayloadDeserializer deserializer;
  private final InboxRetryDlqPublisher retryDlqPublisher;
  private final InboxMetrics metrics;
  private final Clock clock;
  private final String schema;
  private final InboxProperties.Retry retryConfig;
  private final String insertInboxSql;
  private final String upsertVersionSql;

  public InboxMessageProcessor(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      String schema,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics,
      InboxProperties.Retry retryConfig,
      Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.schema = requireSchema(schema);
    this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
    this.retryDlqPublisher = Objects.requireNonNull(retryDlqPublisher, "retryDlqPublisher");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.retryConfig =
        retryConfig == null ? new InboxProperties.Retry(null, null, null, null) : retryConfig;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.insertInboxSql = buildInsertInboxSql(this.schema);
    this.upsertVersionSql = buildUpsertVersionSql(this.schema);
  }

  public String schema() {
    return schema;
  }

  /**
   * Xử lý một bản ghi Kafka cho {@code endpoint} và xác nhận offset sau khi đã xử lý hoặc đã chuyển
   * sang topic thử lại / thư chết.
   */
  public InboxOutcome process(
      InboxEndpoint endpoint, ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(record, "record");

    InboxEnvelope<SpecificRecord> envelope;
    try {
      envelope = parseEnvelope(endpoint, record);
    } catch (RuntimeException ex) {
      return sendToDlqAndAck(endpoint, record, extractErrorCode(ex), ex, ack);
    }

    int maxAttempts = retryConfig.maxInPlaceAttempts();
    Throwable lastError = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        InboxOutcome outcome = executeInTenantTransaction(endpoint, envelope, ack);
        metrics.record(endpoint.consumer(), endpoint.topic(), outcome);
        return outcome;
      } catch (RuntimeException ex) {
        lastError = ex;
        if (isPermanent(ex)) {
          return sendToDlqAndAck(endpoint, record, extractErrorCode(ex), ex, ack);
        }
        if (attempt < maxAttempts) {
          sleepBackoff(attempt);
        }
      }
    }

    return handleExhaustedInPlaceRetries(endpoint, record, lastError, ack);
  }

  @SuppressWarnings("try")
  private InboxOutcome executeInTenantTransaction(
      InboxEndpoint endpoint, InboxEnvelope<SpecificRecord> envelope, Acknowledgment ack) {
    Context otelContext =
        W3CTraceContextPropagator.getInstance()
            .extract(
                Context.current(),
                headersFromTraceparent(envelope.traceparent()),
                KAFKA_HEADER_GETTER);
    try {
      return TenantContext.call(
          envelope.tenantId(),
          () -> {
            String prevTenant = MDC.get(LoggingContext.TENANT_ID);
            MDC.put(LoggingContext.TENANT_ID, envelope.tenantId().toString());
            try (Scope ignored = otelContext.makeCurrent()) {
              AtomicBoolean ackedInSync = new AtomicBoolean(false);
              InboxOutcome outcome =
                  transactionTemplate.execute(
                      status -> {
                        jdbc.queryForObject(
                            SET_TENANT_SQL, String.class, envelope.tenantId().toString());

                        Boolean inserted =
                            jdbc.query(
                                insertInboxSql,
                                ps -> {
                                  ps.setObject(1, envelope.eventId());
                                  ps.setString(2, endpoint.consumer());
                                  ps.setObject(3, envelope.tenantId().value());
                                  ps.setString(4, endpoint.topic());
                                  ps.setInt(5, envelope.partition());
                                  ps.setLong(6, envelope.offset());
                                },
                                ResultSet::next);
                        if (!Boolean.TRUE.equals(inserted)) {
                          registerAckAfterCommit(ack, ackedInSync);
                          return InboxOutcome.DUPLICATE_SKIPPED;
                        }

                        if (endpoint.hasAggregateType()) {
                          Boolean advanced =
                              jdbc.query(
                                  upsertVersionSql,
                                  ps -> {
                                    ps.setString(1, endpoint.consumer());
                                    ps.setString(2, endpoint.aggregateType());
                                    ps.setObject(3, envelope.aggregateId());
                                    ps.setObject(4, envelope.tenantId().value());
                                    ps.setLong(5, envelope.aggregateVersion());
                                    ps.setObject(6, envelope.eventId());
                                  },
                                  ResultSet::next);
                          if (!Boolean.TRUE.equals(advanced)) {
                            registerAckAfterCommit(ack, ackedInSync);
                            return InboxOutcome.STALE_SKIPPED;
                          }
                        }

                        endpoint.invoke(envelope);
                        registerAckAfterCommit(ack, ackedInSync);
                        return InboxOutcome.PROCESSED;
                      });
              if (!ackedInSync.get() && ack != null) {
                ack.acknowledge();
              }
              return Objects.requireNonNull(outcome);
            } finally {
              if (prevTenant == null) {
                MDC.remove(LoggingContext.TENANT_ID);
              } else {
                MDC.put(LoggingContext.TENANT_ID, prevTenant);
              }
            }
          });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Lỗi bất ngờ khi thực thi trong TenantContext", e);
    }
  }

  private static void registerAckAfterCommit(Acknowledgment ack, AtomicBoolean ackedInSync) {
    if (ack == null) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              if (ackedInSync.compareAndSet(false, true)) {
                ack.acknowledge();
              }
            }
          });
    }
  }

  private InboxOutcome handleExhaustedInPlaceRetries(
      InboxEndpoint endpoint,
      ConsumerRecord<String, byte[]> record,
      Throwable error,
      Acknowledgment ack) {
    int currentTier = currentRetryTier(record.headers());
    List<Duration> delays = retryConfig.retryDelays();
    if (currentTier < delays.size()) {
      int nextTier = currentTier + 1;
      Duration delay = delays.get(currentTier);
      Instant notBefore = clock.instant().plus(delay);
      log.warn(
          "Inbox consumer {} gặp lỗi tạm thời sau {} lần thử tại chỗ; chuyển sang {}.retry.{} (bậc"
              + " {}, trễ {})",
          endpoint.consumer(),
          retryConfig.maxInPlaceAttempts(),
          endpoint.topic(),
          endpoint.consumer(),
          nextTier,
          delay,
          error);
      retryDlqPublisher.publishToRetry(
          record, endpoint.topic(), endpoint.consumer(), nextTier, delay, notBefore, error);
      metrics.record(endpoint.consumer(), endpoint.topic(), InboxOutcome.RETRIED);
      if (ack != null) {
        ack.acknowledge();
      }
      return InboxOutcome.RETRIED;
    }
    return sendToDlqAndAck(endpoint, record, RETRY_EXHAUSTED_CODE, error, ack);
  }

  private InboxOutcome sendToDlqAndAck(
      InboxEndpoint endpoint,
      ConsumerRecord<String, byte[]> record,
      String errorCode,
      Throwable error,
      Acknowledgment ack) {
    log.error(
        "Inbox consumer {} chuyển thông điệp sang {}.dlq.{} (errorCode={})",
        endpoint.consumer(),
        endpoint.topic(),
        endpoint.consumer(),
        errorCode,
        error);
    retryDlqPublisher.publishToDlq(
        record, endpoint.topic(), endpoint.consumer(), errorCode, error, clock.instant());
    metrics.record(endpoint.consumer(), endpoint.topic(), InboxOutcome.DLQ);
    if (ack != null) {
      ack.acknowledge();
    }
    return InboxOutcome.DLQ;
  }

  InboxEnvelope<SpecificRecord> parseEnvelope(
      InboxEndpoint endpoint, ConsumerRecord<String, byte[]> record) {
    Headers headers = record.headers();
    UUID eventId = requireUuidHeader(headers, OutboxHeaders.EVENT_ID);
    TenantId tenantId = TenantId.of(requireUuidHeader(headers, OutboxHeaders.TENANT_ID));

    SpecificRecord payload = deserializer.deserialize(endpoint.topic(), headers, record.value());

    UUID aggregateId = parseAggregateId(record.key(), tenantId);
    if (endpoint.hasAggregateType() && aggregateId == null) {
      throw new PermanentInboxException(
          PermanentInboxException.INVALID_HEADER_CODE,
          "Thiếu hoặc sai định dạng aggregateId trong khoá thông điệp Kafka cho consumer "
              + endpoint.consumer());
    }

    Long version = parseAggregateVersion(headers, payload);
    if (endpoint.hasAggregateType() && (version == null || version < 0)) {
      throw new PermanentInboxException(
          PermanentInboxException.INVALID_HEADER_CODE,
          "Thiếu hoặc sai aggregateVersion cho consumer " + endpoint.consumer());
    }
    long aggregateVersion = version == null ? 0L : version;

    ActorId actorId = parseOptionalActor(headers);
    Classification classification = parseClassification(headers);
    String policyVersion = headerString(headers, OutboxHeaders.POLICY_VERSION);
    String traceparent = headerString(headers, OutboxHeaders.TRACEPARENT);
    String eventType = headerString(headers, OutboxHeaders.CE_TYPE);
    if (eventType == null || eventType.isBlank()) {
      eventType = payload.getSchema().getName();
    }
    Instant occurredAt = parseOptionalInstant(headers, OutboxHeaders.OCCURRED_AT);

    return new InboxEnvelope<>(
        eventId,
        endpoint.topic(),
        record.key(),
        tenantId,
        aggregateId,
        aggregateVersion,
        eventType,
        classification,
        actorId,
        policyVersion,
        traceparent,
        occurredAt,
        record.partition(),
        record.offset(),
        payload);
  }

  static boolean isPermanent(Throwable ex) {
    Throwable cur = ex;
    while (cur != null) {
      if (cur instanceof PermanentInboxException
          || cur instanceof BusinessException
          || cur instanceof SerializationException
          || cur instanceof IllegalArgumentException) {
        return true;
      }
      if (cur instanceof TransientInboxException
          || cur instanceof TransientDataAccessException
          || cur instanceof CannotCreateTransactionException
          || cur instanceof SQLTransientException
          || cur instanceof TimeoutException) {
        return false;
      }
      cur = cur.getCause();
    }
    return false;
  }

  static String extractErrorCode(Throwable ex) {
    Throwable cur = ex;
    while (cur != null) {
      if (cur instanceof PermanentInboxException pie) {
        return pie.errorCode();
      }
      if (cur instanceof BusinessException be) {
        return be.errorCode().code();
      }
      if (cur instanceof SerializationException) {
        return PermanentInboxException.DESERIALIZATION_ERROR_CODE;
      }
      cur = cur.getCause();
    }
    return PermanentInboxException.DEFAULT_ERROR_CODE;
  }

  private void sleepBackoff(int attempt) {
    long baseMs = retryConfig.initialBackoff().toMillis();
    if (baseMs <= 0) {
      return;
    }
    long delayMs =
        Math.round(baseMs * Math.pow(retryConfig.backoffMultiplier(), Math.max(0, attempt - 1)));
    if (delayMs <= 0) {
      return;
    }
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static UUID requireUuidHeader(Headers headers, String name) {
    String raw = headerString(headers, name);
    if (raw == null || raw.isBlank()) {
      throw new PermanentInboxException(
          PermanentInboxException.INVALID_HEADER_CODE, "Thiếu tiêu đề bắt buộc: " + name);
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new PermanentInboxException(
          PermanentInboxException.INVALID_HEADER_CODE,
          "Tiêu đề " + name + " không phải UUID hợp lệ: " + raw,
          e);
    }
  }

  private static UUID parseAggregateId(String key, TenantId tenantId) {
    if (key == null || key.isBlank()) {
      return null;
    }
    String raw = key.trim();
    int sep = raw.indexOf(':');
    if (sep >= 0) {
      String keyTenant = raw.substring(0, sep);
      if (!tenantId.toString().equals(keyTenant)) {
        throw new PermanentInboxException(
            PermanentInboxException.INVALID_HEADER_CODE,
            "Tenant trong khoá thông điệp ("
                + keyTenant
                + ") lệch tiêu đề tenantid ("
                + tenantId
                + ")");
      }
      raw = raw.substring(sep + 1);
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Long parseAggregateVersion(Headers headers, SpecificRecord payload) {
    String raw = headerString(headers, OutboxHeaders.AGGREGATE_VERSION);
    if (raw != null && !raw.isBlank()) {
      try {
        return Long.parseLong(raw.trim());
      } catch (NumberFormatException e) {
        throw new PermanentInboxException(
            PermanentInboxException.INVALID_HEADER_CODE,
            "Tiêu đề aggregateversion không phải số nguyên: " + raw,
            e);
      }
    }
    Schema.Field field = payload.getSchema().getField("aggregateVersion");
    if (field != null) {
      Object val = payload.get(field.pos());
      if (val instanceof Number n) {
        return n.longValue();
      }
    }
    return null;
  }

  private static ActorId parseOptionalActor(Headers headers) {
    String raw = headerString(headers, OutboxHeaders.ACTOR_ID);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return ActorId.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException e) {
      throw new PermanentInboxException(
          PermanentInboxException.INVALID_HEADER_CODE,
          "Tiêu đề actorid không phải UUID hợp lệ: " + raw,
          e);
    }
  }

  private static Classification parseClassification(Headers headers) {
    String raw = headerString(headers, OutboxHeaders.CLASSIFICATION);
    if (raw == null || raw.isBlank()) {
      return Classification.C1;
    }
    try {
      return Classification.valueOf(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new PermanentInboxException(
          PermanentInboxException.INVALID_HEADER_CODE,
          "Tiêu đề classification không hợp lệ: " + raw,
          e);
    }
  }

  private static Instant parseOptionalInstant(Headers headers, String name) {
    String raw = headerString(headers, name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new PermanentInboxException(
          PermanentInboxException.INVALID_HEADER_CODE,
          "Tiêu đề " + name + " không phải thời điểm ISO-8601: " + raw,
          e);
    }
  }

  private static int currentRetryTier(Headers headers) {
    String raw = headerString(headers, InboxHeaders.RETRY_TIER);
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(raw.trim()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  static String headerString(Headers headers, String key) {
    if (headers == null) {
      return null;
    }
    Header header = headers.lastHeader(key);
    if (header == null || header.value() == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }

  private static Headers headersFromTraceparent(String traceparent) {
    org.apache.kafka.common.header.internals.RecordHeaders h =
        new org.apache.kafka.common.header.internals.RecordHeaders();
    if (traceparent != null && !traceparent.isBlank()) {
      h.add(OutboxHeaders.TRACEPARENT, traceparent.getBytes(StandardCharsets.UTF_8));
    }
    return h;
  }

  static String requireSchema(String schema) {
    if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches()) {
      throw new IllegalArgumentException(
          "okrkpi.inbox.schema phải là tên schema hợp lệ (chữ thường, số, gạch dưới): " + schema);
    }
    return schema;
  }

  private static String buildInsertInboxSql(String schema) {
    return "INSERT INTO "
        + schema
        + ".inbox_processed (event_id, consumer, tenant_id, source_topic, kafka_partition,"
        + " kafka_offset) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (consumer, event_id) DO NOTHING"
        + " RETURNING event_id";
  }

  private static String buildUpsertVersionSql(String schema) {
    return "INSERT INTO "
        + schema
        + ".inbox_aggregate_version AS v (consumer, aggregate_type, aggregate_id, tenant_id,"
        + " last_version, last_event_id, updated_at) VALUES (?, ?, ?, ?, ?, ?, now()) ON CONFLICT"
        + " (consumer, aggregate_type, aggregate_id) DO UPDATE SET last_version ="
        + " EXCLUDED.last_version, last_event_id = EXCLUDED.last_event_id, updated_at = now() WHERE"
        + " v.last_version < EXCLUDED.last_version RETURNING last_version";
  }
}
