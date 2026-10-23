package vn.viettel.okrkpi.org.infrastructure.republish;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.specific.SpecificRecord;
import vn.viettel.okrkpi.events.EventContracts;

/**
 * Tra lược đồ đã đăng ký trong Apicurio Registry (không tự đăng ký, chiến lược {@code TopicIdStrategy}) và tuần tự
 * hoá Avro nhị phân, cùng cách {@code ApicurioPayloadSerializer} của NEN-011 để consumer dùng một bộ giải mã. Bản sao
 * gọn vì org-service chưa dùng thư viện nền (suy luận; thay bằng thư viện nền khi org-service chuyển sang
 * {@code vt-platform-starter}).
 */
public final class ApicurioPayloadEncoder implements PayloadEncoder {

  private final AvroSerializer<IndexedRecord> serializer = new AvroSerializer<>();
  private final ConcurrentMap<String, SchemaLookupResult<Schema>> lookups = new ConcurrentHashMap<>();

  public ApicurioPayloadEncoder(String registryUrl, Map<String, ?> overrides) {
    EventContracts.trustContractPackages();
    Map<String, Object> config = new HashMap<>();
    config.put(SerdeConfig.REGISTRY_URL, registryUrl);
    config.put(SerdeConfig.HTTP_ADAPTER, "JDK");
    config.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, "false");
    config.put(SerdeConfig.FIND_LATEST_ARTIFACT, "false");
    config.put(SerdeConfig.CANONICALIZE, "true");
    config.put(SerdeConfig.USE_ID, "globalId");
    if (overrides != null) {
      config.putAll(overrides);
    }
    serializer.configure(new SerdeConfig(config), false);
  }

  @Override
  public Encoded encode(String topic, SpecificRecord payload) {
    SchemaLookupResult<Schema> lookup =
        lookups.computeIfAbsent(topic + "|" + payload.getSchema().getFullName(), k -> resolve(topic, payload));
    ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    try {
      serializer.serializeData(lookup.getParsedSchema(), payload, out);
    } catch (IOException e) {
      throw new IllegalStateException("Không tuần tự hoá được " + payload.getSchema().getFullName(), e);
    }
    return new Encoded(out.toByteArray(), lookup.getGlobalId());
  }

  private SchemaLookupResult<Schema> resolve(String topic, SpecificRecord payload) {
    SchemaResolver<Schema, IndexedRecord> resolver = serializer.getSerdeConfigurer().getSchemaResolver();
    // Lược đồ dạng tệp hợp đồng: lược đồ lớp sinh mang avro.java.string nên tra theo nội dung không khớp.
    Schema contract = EventContracts.registrySchema(payload.getSchema());
    try {
      return resolver.resolveSchema(
          new SerdeRecord<>(new SerdeMetadata(topic, false), new GenericData.Record(contract)));
    } catch (RuntimeException byContent) {
      // org.bootstrap.v1 đăng ký kèm tham chiếu tới EVT-14..EVT-16 (Job apicurio-dang-ky), còn tra theo nội dung chỉ
      // so dạng không tham chiếu: lấy bản mới nhất của <topic>-value và chỉ dùng khi trùng lược đồ của lớp sinh.
      SchemaLookupResult<Schema> latest =
          resolver.resolveSchemaByArtifactReference(
              ArtifactReference.builder().groupId("default").artifactId(topic + "-value").build());
      if (!contract.equals(EventContracts.registrySchema(latest.getParsedSchema().getParsedSchema()))) {
        byContent.addSuppressed(
            new IllegalStateException("Bản mới nhất của " + topic + "-value khác lược đồ lớp sinh"));
        throw byContent;
      }
      return latest;
    }
  }

  @Override
  public void close() {
    serializer.close();
  }
}
