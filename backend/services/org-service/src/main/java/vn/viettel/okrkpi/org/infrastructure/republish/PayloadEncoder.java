package vn.viettel.okrkpi.org.infrastructure.republish;

import org.apache.avro.specific.SpecificRecord;

/** Tuần tự hoá thân Avro cho một topic; {@code globalId} null khi không dùng registry (kiểm thử). */
public interface PayloadEncoder extends AutoCloseable {

  Encoded encode(String topic, SpecificRecord payload);

  @Override
  default void close() {
  }

  /** Avro nhị phân không tiền tố; mã lược đồ đi riêng vào tiêu đề {@code apicurio.value.globalId}. */
  record Encoded(byte[] bytes, Long globalId) {
  }
}
