package vn.viettel.okrkpi.shared.inbox;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.header.Headers;

/**
 * NEN-012 · Giải tuần tự thân sự kiện Avro từ thông điệp Kafka. Hiện thực chuẩn là {@link
 * ApicurioPayloadDeserializer}; kiểm thử đơn vị có thể thay bằng bản giả.
 */
@FunctionalInterface
public interface InboxPayloadDeserializer {

  /**
   * Giải tuần tự mảng byte thân thông điệp sang lớp Avro {@link SpecificRecord}.
   *
   * @param topic tên topic gốc của sự kiện
   * @param headers tiêu đề Kafka (chứa {@code apicurio.value.globalId})
   * @param data mảng byte Avro nhị phân
   * @return đối tượng {@link SpecificRecord} đã giải mã
   * @throws PermanentInboxException khi thiếu mã lược đồ, lược đồ không tương thích hoặc dữ liệu
   *     nhị phân bị hỏng
   */
  SpecificRecord deserialize(String topic, Headers headers, byte[] data);
}
