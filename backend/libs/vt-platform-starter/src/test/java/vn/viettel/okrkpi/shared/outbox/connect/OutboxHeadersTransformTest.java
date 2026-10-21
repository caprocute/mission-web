package vn.viettel.okrkpi.shared.outbox.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

/** Tách cột headers (jsonb) thành tiêu đề Kafka sau EventRouter. */
class OutboxHeadersTransformTest {

  private static SourceRecord record(ConnectHeaders headers) {
    return new SourceRecord(
        Map.of(),
        Map.of(),
        "task.task.progressed.v1",
        2,
        Schema.STRING_SCHEMA,
        "t:a",
        Schema.BYTES_SCHEMA,
        new byte[] {1},
        1234L,
        headers);
  }

  private static String text(SourceRecord r, String key) {
    Header h = r.headers().lastWithName(key);
    return h == null ? null : new String((byte[]) h.value(), StandardCharsets.UTF_8);
  }

  @Test
  void tachJsonThanhTieuDeByteVaGlobalIdTamByte() {
    ConnectHeaders in = new ConnectHeaders();
    in.addString("id", "0192f5a0-7b1c-7cde-8f00-0123456789ab");
    in.addString(
        "vt_outbox_headers",
        "{\"traceparent\":\"00-abc-def-01\",\"tenantid\":\"t\",\"aggregateversion\":\"3\","
            + "\"policyversion\":null,\"apicurio.value.globalId\":77}");

    try (OutboxHeadersTransform<SourceRecord> smt = new OutboxHeadersTransform<>()) {
      smt.configure(Map.of());
      SourceRecord out = smt.apply(record(in));

      assertThat(out.topic()).isEqualTo("task.task.progressed.v1");
      assertThat(out.kafkaPartition()).isEqualTo(2);
      assertThat(out.key()).isEqualTo("t:a");
      assertThat((byte[]) out.value()).containsExactly(1);
      assertThat(out.timestamp()).isEqualTo(1234L);
      assertThat(out.headers().lastWithName("vt_outbox_headers")).isNull();
      assertThat(out.headers().lastWithName("policyversion")).isNull();
      assertThat(text(out, "id")).isEqualTo("0192f5a0-7b1c-7cde-8f00-0123456789ab");
      assertThat(text(out, "traceparent")).isEqualTo("00-abc-def-01");
      assertThat(text(out, "tenantid")).isEqualTo("t");
      assertThat(text(out, "aggregateversion")).isEqualTo("3");
      byte[] gid = (byte[]) out.headers().lastWithName("apicurio.value.globalId").value();
      assertThat(gid).hasSize(8);
      assertThat(ByteBuffer.wrap(gid).getLong()).isEqualTo(77L);
      for (Header h : out.headers()) {
        assertThat(h.schema().type()).isEqualTo(Schema.Type.BYTES);
      }
    }
  }

  @Test
  void cauHinhTenTieuDeVaSoNguyenTuyBien() {
    ConnectHeaders in = new ConnectHeaders();
    in.addBytes("raw", new byte[] {5});
    in.addString("h", "{\"n\":\"12\"}");
    try (OutboxHeadersTransform<SourceRecord> smt = new OutboxHeadersTransform<>()) {
      smt.configure(Map.of("source.header", "h", "long.headers", "n"));
      SourceRecord out = smt.apply(record(in));
      assertThat((byte[]) out.headers().lastWithName("raw").value()).containsExactly(5);
      assertThat(ByteBuffer.wrap((byte[]) out.headers().lastWithName("n").value()).getLong())
          .isEqualTo(12L);
      assertThat(smt.config().names()).contains("source.header", "long.headers");
    }
  }

  @Test
  void jsonSaiHoacSoSaiThiBaoLoi() {
    try (OutboxHeadersTransform<SourceRecord> smt = new OutboxHeadersTransform<>()) {
      smt.configure(Map.of());
      ConnectHeaders bad = new ConnectHeaders();
      bad.addString("vt_outbox_headers", "{khong-phai-json");
      assertThatThrownBy(() -> smt.apply(record(bad))).isInstanceOf(DataException.class);

      ConnectHeaders array = new ConnectHeaders();
      array.addString("vt_outbox_headers", "[1]");
      assertThatThrownBy(() -> smt.apply(record(array))).isInstanceOf(DataException.class);

      ConnectHeaders nan = new ConnectHeaders();
      nan.addString("vt_outbox_headers", "{\"apicurio.value.globalId\":\"abc\"}");
      assertThatThrownBy(() -> smt.apply(record(nan))).isInstanceOf(DataException.class);

      ConnectHeaders empty = new ConnectHeaders();
      empty.add("vt_outbox_headers", null, Schema.OPTIONAL_STRING_SCHEMA);
      assertThat(smt.apply(record(empty)).headers().size()).isZero();
      assertThat(smt.apply(null)).isNull();
    }
  }
}
