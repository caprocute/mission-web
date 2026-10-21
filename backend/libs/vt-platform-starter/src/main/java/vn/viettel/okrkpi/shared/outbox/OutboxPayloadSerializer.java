package vn.viettel.okrkpi.shared.outbox;

import java.util.Objects;
import org.apache.avro.specific.SpecificRecord;

/**
 * Tuần tự hoá thân sự kiện sang Avro nhị phân theo lược đồ đã đăng ký (TL-03 "Transactional Outbox
 * và Debezium"). Hiện thực chuẩn là {@link ApicurioPayloadSerializer}; kiểm thử đơn vị có thể thay
 * bằng bản giả.
 */
@FunctionalInterface
public interface OutboxPayloadSerializer {

  /**
   * Tra lược đồ của {@code payload} trong registry theo topic rồi tuần tự hoá.
   *
   * @throws OutboxSerializationException khi registry không phản hồi, lược đồ chưa đăng ký hoặc
   *     không tuần tự hoá được; giao dịch nghiệp vụ phải thất bại theo
   */
  SerializedPayload serialize(String topic, SpecificRecord payload);

  /**
   * Thân đã tuần tự hoá.
   *
   * @param bytes Avro nhị phân, không có tiền tố mã lược đồ (mã lược đồ nằm ở tiêu đề)
   * @param globalId global id của phiên bản lược đồ trong Apicurio
   * @param schemaVersion số phiên bản lược đồ trong artifact (cột {@code schema_version})
   */
  record SerializedPayload(byte[] bytes, long globalId, int schemaVersion) {

    public SerializedPayload {
      Objects.requireNonNull(bytes, "bytes");
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof SerializedPayload other
          && java.util.Arrays.equals(bytes, other.bytes)
          && globalId == other.globalId
          && schemaVersion == other.schemaVersion;
    }

    @Override
    public int hashCode() {
      return 31 * (31 * java.util.Arrays.hashCode(bytes) + Long.hashCode(globalId)) + schemaVersion;
    }

    @Override
    public String toString() {
      return "SerializedPayload[bytes="
          + bytes.length
          + "B, globalId="
          + globalId
          + ", schemaVersion="
          + schemaVersion
          + "]";
    }
  }
}
