package vn.viettel.okrkpi.shared.outbox;

import io.apicurio.registry.resolver.SchemaLookupResult;
import io.apicurio.registry.resolver.SchemaResolver;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import io.apicurio.registry.serde.avro.AvroSerializer;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.data.SerdeMetadata;
import io.apicurio.registry.serde.data.SerdeRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.specific.SpecificRecord;
import vn.viettel.okrkpi.events.EventContracts;

/**
 * Tuần tự hoá thân sự kiện bằng serde Avro của Apicurio Registry 3.x (ADR-03, TL-03 "Transactional
 * Outbox và Debezium"):
 *
 * <ul>
 *   <li>tra phiên bản lược đồ theo nội dung trong artifact {@code <topic>-value} của nhóm {@code
 *       default} (chiến lược {@code TopicIdStrategy}, trùng cách NEN-007 đăng ký), không tự đăng ký
 *       lược đồ: lược đồ chưa đăng ký là lỗi của bản phát hành, không được lách ở lúc chạy;
 *   <li>đệm kết quả tra cứu theo (topic, lược đồ) suốt đời tiến trình, vì nội dung một phiên bản
 *       lược đồ và global id của nó không đổi; registry chỉ được gọi ở lần phát đầu tiên của mỗi
 *       lớp sự kiện;
 *   <li>trả Avro nhị phân không có tiền tố mã lược đồ; global id đi riêng vào tiêu đề {@code
 *       apicurio.value.globalId} (TL-03 cấu hình connector mẫu).
 * </ul>
 *
 * <p>Registry không phản hồi ở lần tra đầu tiên thì ném {@link OutboxSerializationException} và
 * giao dịch nghiệp vụ thất bại (suy luận: không bao giờ ghi thân không gắn lược đồ).
 */
public final class ApicurioPayloadSerializer implements OutboxPayloadSerializer, AutoCloseable {

  /** Cấu hình serde mặc định; giá trị trong {@code overrides} thắng. */
  static Map<String, Object> defaults(String registryUrl) {
    Map<String, Object> config = new HashMap<>();
    config.put(SerdeConfig.REGISTRY_URL, registryUrl);
    // Client HTTP của JDK: không dựng Vert.x trong tiến trình service (suy luận).
    config.put(SerdeConfig.HTTP_ADAPTER, "JDK");
    config.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, "false");
    config.put(SerdeConfig.FIND_LATEST_ARTIFACT, "false");
    // Lớp sinh in lược đồ khác định dạng tệp .avsc đã đăng ký; so theo dạng chuẩn hoá.
    config.put(SerdeConfig.CANONICALIZE, "true");
    config.put(SerdeConfig.USE_ID, "globalId");
    return config;
  }

  private final AvroSerializer<IndexedRecord> serializer;
  private final ConcurrentMap<CacheKey, SchemaLookupResult<Schema>> lookups =
      new ConcurrentHashMap<>();

  /**
   * @param registryUrl địa chỉ API v3 của Apicurio, ví dụ {@code
   *     http://registry:8080/apis/registry/v3}
   * @param overrides cấu hình serde bổ sung ({@code apicurio.registry.*}, ví dụ xác thực)
   */
  public ApicurioPayloadSerializer(String registryUrl, Map<String, ?> overrides) {
    Objects.requireNonNull(registryUrl, "registryUrl");
    EventContracts.trustContractPackages();
    Map<String, Object> config = defaults(registryUrl);
    if (overrides != null) {
      config.putAll(overrides);
    }
    this.serializer = new AvroSerializer<>();
    this.serializer.configure(new SerdeConfig(config), false);
  }

  @Override
  public SerializedPayload serialize(String topic, SpecificRecord payload) {
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(payload, "payload");
    SchemaLookupResult<Schema> lookup = lookup(topic, payload);
    ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    try {
      serializer.serializeData(lookup.getParsedSchema(), payload, out);
    } catch (IOException | RuntimeException e) {
      throw new OutboxSerializationException(
          "Không tuần tự hoá được thân sự kiện " + payload.getSchema().getFullName(), e);
    }
    return new SerializedPayload(
        out.toByteArray(), lookup.getGlobalId(), schemaVersion(topic, lookup.getVersion()));
  }

  private SchemaLookupResult<Schema> lookup(String topic, SpecificRecord payload) {
    CacheKey key = new CacheKey(topic, payload.getSchema());
    SchemaLookupResult<Schema> cached = lookups.get(key);
    if (cached != null) {
      return cached;
    }
    SchemaLookupResult<Schema> found;
    try {
      found = resolve(topic, payload);
    } catch (RuntimeException e) {
      throw new OutboxSerializationException(
          "Không tra được lược đồ "
              + payload.getSchema().getFullName()
              + " của topic "
              + topic
              + " trong Apicurio Registry (registry không phản hồi hoặc lược đồ chưa đăng ký)",
          e);
    }
    SchemaLookupResult<Schema> prev = lookups.putIfAbsent(key, found);
    return prev != null ? prev : found;
  }

  /**
   * Tra theo nội dung bằng lược đồ dạng tệp hợp đồng; lược đồ đăng ký kèm tham chiếu tới kiểu có
   * tên ở artifact khác (ví dụ {@code org.bootstrap.v1}, Job {@code apicurio-dang-ky}) không bao
   * giờ khớp cách tra này vì serde chỉ so dạng không tham chiếu, nên khi đó lấy bản mới nhất của
   * {@code <topic>-value} và chỉ dùng khi bản đó trùng lược đồ của lớp sinh.
   */
  private SchemaLookupResult<Schema> resolve(String topic, SpecificRecord payload) {
    SchemaResolver<Schema, IndexedRecord> resolver =
        serializer.getSerdeConfigurer().getSchemaResolver();
    IndexedRecord form = registryForm(payload);
    try {
      return resolver.resolveSchema(new SerdeRecord<>(new SerdeMetadata(topic, false), form));
    } catch (RuntimeException byContent) {
      SchemaLookupResult<Schema> latest;
      try {
        latest =
            resolver.resolveSchemaByArtifactReference(
                ArtifactReference.builder()
                    .groupId("default")
                    .artifactId(topic + "-value")
                    .build());
      } catch (RuntimeException byArtifact) {
        byContent.addSuppressed(byArtifact);
        throw byContent;
      }
      Schema registered = EventContracts.registrySchema(latest.getParsedSchema().getParsedSchema());
      if (!form.getSchema().equals(registered)) {
        byContent.addSuppressed(
            new IllegalStateException(
                "Bản mới nhất của " + topic + "-value khác lược đồ của lớp sinh"));
        throw byContent;
      }
      return latest;
    }
  }

  /**
   * Bản ghi rỗng mang lược đồ dạng tệp hợp đồng ({@link EventContracts#registrySchema}): tra theo
   * nội dung bằng lược đồ lớp sinh không khớp vì thuộc tính {@code avro.java.string}. Lược đồ trả
   * về dùng để ghi thân; mã hoá nhị phân như lược đồ lớp sinh.
   */
  private static IndexedRecord registryForm(SpecificRecord payload) {
    return new GenericData.Record(EventContracts.registrySchema(payload.getSchema()));
  }

  private static int schemaVersion(String topic, String version) {
    try {
      return Integer.parseInt(version);
    } catch (NumberFormatException e) {
      throw new OutboxSerializationException(
          "Phiên bản lược đồ của topic " + topic + " không phải số nguyên: " + version, e);
    }
  }

  @Override
  public void close() {
    serializer.close();
  }

  private record CacheKey(String topic, Schema schema) {}
}
