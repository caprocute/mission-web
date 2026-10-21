package vn.viettel.okrkpi.shared.inbox;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroSerdeConfig;
import io.apicurio.registry.serde.config.SerdeConfig;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;

/**
 * Bộ giải tuần tự thân sự kiện Avro của hộp thư đến tích hợp Apicurio Registry 3.x ({@code
 * NEN-012}).
 */
public final class ApicurioPayloadDeserializer implements InboxPayloadDeserializer, AutoCloseable {

  static Map<String, Object> defaults(String registryUrl) {
    Map<String, Object> config = new HashMap<>();
    config.put(SerdeConfig.REGISTRY_URL, registryUrl);
    config.put(SerdeConfig.HTTP_ADAPTER, "JDK");
    config.put(SerdeConfig.USE_ID, "globalId");
    config.put("apicurio.registry.headers.enabled", "true");
    config.put(AvroSerdeConfig.USE_SPECIFIC_AVRO_READER, "true");
    return config;
  }

  private final AvroKafkaDeserializer<SpecificRecord> deserializer;

  public ApicurioPayloadDeserializer(String registryUrl, Map<String, ?> overrides) {
    Objects.requireNonNull(registryUrl, "registryUrl");
    EventContracts.trustContractPackages();
    Map<String, Object> config = defaults(registryUrl);
    if (overrides != null) {
      config.putAll(overrides);
    }
    this.deserializer = new AvroKafkaDeserializer<>();
    this.deserializer.configure(config, false);
  }

  @Override
  public SpecificRecord deserialize(String topic, Headers headers, byte[] data) {
    Objects.requireNonNull(topic, "topic");
    if (data == null || data.length == 0) {
      throw new PermanentInboxException(
          PermanentInboxException.DESERIALIZATION_ERROR_CODE,
          "Thân sự kiện rỗng trên topic " + topic);
    }
    try {
      SpecificRecord record = deserializer.deserialize(topic, normalizeHeaders(headers), data);
      if (record == null) {
        throw new PermanentInboxException(
            PermanentInboxException.DESERIALIZATION_ERROR_CODE,
            "Giải tuần tự trả về null trên topic " + topic);
      }
      return record;
    } catch (PermanentInboxException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PermanentInboxException(
          PermanentInboxException.DESERIALIZATION_ERROR_CODE,
          "Không giải tuần tự được thân Avro trên topic " + topic,
          e);
    }
  }

  private static Headers normalizeHeaders(Headers headers) {
    if (headers == null) {
      return new RecordHeaders();
    }
    Header gid = headers.lastHeader(OutboxHeaders.APICURIO_GLOBAL_ID);
    if (gid == null || gid.value() == null || gid.value().length == Long.BYTES) {
      return headers;
    }
    long parsed = Long.parseLong(new String(gid.value(), StandardCharsets.UTF_8).trim());
    RecordHeaders copy = new RecordHeaders(headers);
    copy.remove(OutboxHeaders.APICURIO_GLOBAL_ID);
    copy.add(
        OutboxHeaders.APICURIO_GLOBAL_ID, ByteBuffer.allocate(Long.BYTES).putLong(parsed).array());
    return copy;
  }

  @Override
  public void close() {
    deserializer.close();
  }
}
