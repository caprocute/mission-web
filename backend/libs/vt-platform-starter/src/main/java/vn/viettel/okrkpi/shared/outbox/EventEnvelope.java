package vn.viettel.okrkpi.shared.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * NEN-011 · ADR-03 · Phong bì một sự kiện phát qua outbox (TL-01 "Phong bì sự kiện", TL-03 "Bảng
 * outbox_event"). Thân sự kiện là lớp Avro sinh từ {@code contracts/events/avro} (vt-events); các
 * thuộc tính phong bì đi vào cột và {@code headers} của {@code <schema>.outbox_event}, rồi thành
 * tiêu đề Kafka qua Debezium, không nằm trong thân Avro.
 *
 * <p>Phong bì từ chối ngay khi dựng: topic sai quy ước (cùng biểu thức với CHECK {@code
 * ck_outbox_event__topic} của NEN-032), phân loại C2/C3 (TL-03: không sự kiện nào mang nội dung C2,
 * khi cần chỉ mang mã tham chiếu), độ dài vượt cột, {@code aggregateVersion} lệch trường cùng tên
 * trong thân.
 *
 * @param eventId mã sự kiện UUIDv7 (cột {@code id}, tiêu đề {@code id} của Debezium)
 * @param topic topic đích, ví dụ {@code task.task.progressed.v1}; Debezium định tuyến theo cột này
 * @param tenantId tenant phát sự kiện
 * @param aggregateType loại thực thể gốc, ví dụ {@code Task}
 * @param aggregateId mã thực thể gốc
 * @param aggregateVersion {@code row_version} của thực thể sau thay đổi
 * @param eventType tên nghiệp vụ (mặc định là tên record Avro, ví dụ {@code TienDoDaCapNhat})
 * @param payload thân sự kiện, lớp Avro sinh từ hợp đồng
 * @param classification phân loại cao nhất của dữ liệu trong sự kiện, chỉ C0 hoặc C1
 * @param actorId người thực hiện, {@code null} khi do hệ thống
 * @param policyVersion phiên bản Policy Pack áp dụng, {@code null} khi không có
 * @param occurredAt thời điểm xảy ra, {@code null} thì {@link OutboxWriter} lấy đồng hồ nền tảng
 */
public record EventEnvelope(
    UUID eventId,
    String topic,
    TenantId tenantId,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    String eventType,
    SpecificRecord payload,
    Classification classification,
    ActorId actorId,
    String policyVersion,
    Instant occurredAt) {

  /**
   * Quy ước topic, trùng CHECK {@code ck_outbox_event__topic} của bảng outbox (NEN-032, nới ở V0_24
   * cho topic kỹ thuật ba đến năm đoạn như {@code sys.audit.v1}, CORE-00-05).
   */
  public static final Pattern TOPIC_PATTERN = Pattern.compile("^[a-z]+(\\.[a-z]+){1,3}\\.v[0-9]+$");

  /**
   * Ngoại lệ duy nhất của {@link #TOPIC_PATTERN}: dòng đánh dấu bước 2 saga chốt kỳ, tên không có
   * phiên bản theo TL-03 (contracts/events/catalog.yaml). Bảng outbox của schema ghi marker nới
   * CHECK cho đúng tên này (config V13, CORE-08-05).
   */
  public static final String DRAIN_MARKER_TOPIC = "period.drain.marker";

  /** Độ dài cột {@code topic}. */
  static final int TOPIC_MAX = 128;

  /** Độ dài cột {@code aggregate_type}. */
  static final int AGGREGATE_TYPE_MAX = 64;

  /** Độ dài cột {@code event_type}. */
  static final int EVENT_TYPE_MAX = 96;

  /** Độ dài tối đa của phiên bản Policy Pack ghi vào tiêu đề (suy luận). */
  static final int POLICY_VERSION_MAX = 64;

  /** Tên trường phiên bản thực thể trong thân sự kiện đi qua outbox (contracts/events/README). */
  static final String AGGREGATE_VERSION_FIELD = "aggregateVersion";

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    if (!Ids.isUuidV7(eventId)) {
      throw new IllegalArgumentException("Mã sự kiện phải là UUIDv7: " + eventId);
    }
    Objects.requireNonNull(topic, "topic");
    if (topic.length() > TOPIC_MAX
        || !(TOPIC_PATTERN.matcher(topic).matches() || DRAIN_MARKER_TOPIC.equals(topic))) {
      throw new IllegalArgumentException(
          "Topic không đúng quy ước <miền>.<thực thể>.<sự kiện>.v<n>: " + topic);
    }
    Objects.requireNonNull(tenantId, "tenantId");
    requireText(aggregateType, "aggregateType", AGGREGATE_TYPE_MAX);
    Objects.requireNonNull(aggregateId, "aggregateId");
    if (aggregateVersion < 0) {
      throw new IllegalArgumentException("aggregateVersion âm: " + aggregateVersion);
    }
    requireText(eventType, "eventType", EVENT_TYPE_MAX);
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(classification, "classification");
    if (classification.isSensitive()) {
      throw new SensitiveEventException(topic, classification);
    }
    if (policyVersion != null) {
      requireText(policyVersion, "policyVersion", POLICY_VERSION_MAX);
    }
    checkPayloadVersion(payload, aggregateVersion);
  }

  /**
   * Phong bì mới với mã sự kiện UUIDv7 mới và {@code eventType} là tên record Avro của thân.
   *
   * @throws SensitiveEventException khi {@code classification} là C2 hoặc C3
   * @throws IllegalArgumentException khi tham số sai quy ước
   */
  public static EventEnvelope of(
      String topic,
      TenantId tenantId,
      String aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      SpecificRecord payload,
      Classification classification) {
    Objects.requireNonNull(payload, "payload");
    return new EventEnvelope(
        Ids.newUuidV7(),
        topic,
        tenantId,
        aggregateType,
        aggregateId,
        aggregateVersion,
        payload.getSchema().getName(),
        payload,
        classification,
        null,
        null,
        null);
  }

  /** Bản sao có người thực hiện (tiêu đề {@code actorid}). */
  public EventEnvelope withActor(ActorId actor) {
    return new EventEnvelope(
        eventId,
        topic,
        tenantId,
        aggregateType,
        aggregateId,
        aggregateVersion,
        eventType,
        payload,
        classification,
        actor,
        policyVersion,
        occurredAt);
  }

  /** Bản sao có phiên bản Policy Pack (tiêu đề {@code policyversion}). */
  public EventEnvelope withPolicyVersion(String version) {
    return new EventEnvelope(
        eventId,
        topic,
        tenantId,
        aggregateType,
        aggregateId,
        aggregateVersion,
        eventType,
        payload,
        classification,
        actorId,
        version,
        occurredAt);
  }

  /** Bản sao có thời điểm xảy ra tường minh (tiêu đề {@code occurredat}). */
  public EventEnvelope withOccurredAt(Instant at) {
    return new EventEnvelope(
        eventId,
        topic,
        tenantId,
        aggregateType,
        aggregateId,
        aggregateVersion,
        eventType,
        payload,
        classification,
        actorId,
        policyVersion,
        at);
  }

  /** Khoá Kafka {@code tenantId:aggregateId} (cột {@code event_key}, TL-03). */
  public String eventKey() {
    return tenantId + ":" + aggregateId;
  }

  private static void requireText(String value, String name, int max) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException(name + " rỗng hoặc dài quá " + max + " ký tự");
    }
  }

  private static void checkPayloadVersion(SpecificRecord payload, long aggregateVersion) {
    Schema.Field field = payload.getSchema().getField(AGGREGATE_VERSION_FIELD);
    if (field == null) {
      return;
    }
    Object value = payload.get(field.pos());
    if (value instanceof Number n && n.longValue() != aggregateVersion) {
      throw new IllegalArgumentException(
          "aggregateVersion của phong bì ("
              + aggregateVersion
              + ") lệch trường aggregateVersion trong thân ("
              + n
              + ")");
    }
  }
}
