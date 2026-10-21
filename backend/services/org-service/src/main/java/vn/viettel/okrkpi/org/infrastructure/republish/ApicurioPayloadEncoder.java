package vn.viettel.okrkpi.org.infrastructure.republish;

import io.apicurio.registry.resolver.SchemaLookupResult;
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
import org.apache.avro.specific.SpecificRecord;
import vn.viettel.okrkpi.events.EventContracts;

/**
 * Tra lược đồ đã đăng ký trong Apicurio Registry (không tự đăng ký, chiến lược {@code TopicIdStrategy}) và tuần tự
 * hoá Avro nhị phân, cùng cách {@code ApicurioPayloadSerializer} của NEN-011 để consumer dùng một bộ giải mã. Bản sao
 * gọn vì org-service chưa dùng thư viện nền (suy luận; thay bằng thư viện nền khi org-service chuyển sang
 * {@code vt-platform-starter}).
 */
public final class ApicurioPayloadEncoder implements PayloadEncoder {

  private final AvroSerializer<SpecificRecord> serializer = new AvroSerializer<>();
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
    SchemaLookupResult<Schema> lookup = lookups.computeIfAbsent(topic + "|" + payload.getSchema().getFullName(),
        k -> serializer.getSerdeConfigurer().getSchemaResolver()
            .resolveSchema(new SerdeRecord<>(new SerdeMetadata(topic, false), payload)));
    ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    try {
      serializer.serializeData(lookup.getParsedSchema(), payload, out);
    } catch (IOException e) {
      throw new IllegalStateException("Không tuần tự hoá được " + payload.getSchema().getFullName(), e);
    }
    return new Encoded(out.toByteArray(), lookup.getGlobalId());
  }

  @Override
  public void close() {
    serializer.close();
  }
}
