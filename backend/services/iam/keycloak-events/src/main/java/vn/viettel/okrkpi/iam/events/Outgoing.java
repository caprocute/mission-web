package vn.viettel.okrkpi.iam.events;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Một thông điệp chờ gửi Kafka: topic, khoá phân vùng, tiêu đề phong bì (CloudEvents rút gọn như outbox của các
 * service: {@code id}, {@code tenantid}, {@code ce_type}, {@code classification}, {@code occurredat},
 * {@code actorid}, {@code traceparent}) và thân Avro nhị phân. Mã lược đồ ({@code apicurio.value.globalId}) gắn lúc
 * gửi.
 */
record Outgoing(String topic, String key, Map<String, String> headers, byte[] payload) {

  Outgoing {
    headers = Map.copyOf(headers);
  }

  byte[] encode() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(128 + payload.length);
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeUTF(topic);
      out.writeUTF(key);
      out.writeShort(headers.size());
      for (Map.Entry<String, String> h : headers.entrySet()) {
        out.writeUTF(h.getKey());
        out.writeUTF(h.getValue());
      }
      out.writeInt(payload.length);
      out.write(payload);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  static Outgoing decode(byte[] data) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      String topic = in.readUTF();
      String key = in.readUTF();
      int n = in.readUnsignedShort();
      Map<String, String> headers = new LinkedHashMap<>();
      for (int i = 0; i < n; i++) {
        headers.put(in.readUTF(), in.readUTF());
      }
      byte[] payload = new byte[in.readInt()];
      in.readFully(payload);
      return new Outgoing(topic, key, headers, payload);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
