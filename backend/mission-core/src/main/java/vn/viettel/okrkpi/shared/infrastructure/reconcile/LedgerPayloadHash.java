package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import vn.viettel.okrkpi.shared.kernel.Jcs;

/**
 * Tính lại {@code payload_hash} của sổ cái từ thân Avro nhị phân ở {@code outbox_event.payload},
 * đúng cách bộ chuyển tiếp của ledger-service tính (DV-09-02): SHA-256 của dạng JCS + NFC của bản
 * mã hoá JSON Avro theo đặc tả. Đọc bằng dữ liệu generic không chuyển kiểu logic, nên decimal là
 * chuỗi byte, date là số ngày, timestamp là số nguyên như bản JSON của đặc tả. Vector chung với Go
 * ở {@code data/golden/ledger-canon/payload-avro}.
 */
public final class LedgerPayloadHash {

  private LedgerPayloadHash() {}

  /** Đọc thân Avro theo lược đồ người ghi; còn byte thừa là lệch phiên bản lược đồ. */
  public static GenericRecord decode(Schema writer, byte[] avro) {
    try {
      BinaryDecoder in = DecoderFactory.get().binaryDecoder(avro, null);
      GenericRecord record =
          new GenericDatumReader<GenericRecord>(writer, writer, new GenericData()).read(null, in);
      if (!in.isEnd()) {
        throw new IllegalArgumentException(
            "Thân Avro " + writer.getFullName() + " còn byte thừa (lệch phiên bản lược đồ)");
      }
      return record;
    } catch (IOException | RuntimeException e) {
      if (e instanceof IllegalArgumentException iae) {
        throw iae;
      }
      throw new IllegalArgumentException("Thân Avro " + writer.getFullName() + " hỏng", e);
    }
  }

  /** Bản mã hoá JSON Avro theo đặc tả của một bản ghi generic. */
  public static String avroJson(GenericRecord record) {
    Schema schema = record.getSchema();
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream(256);
      JsonEncoder encoder = EncoderFactory.get().jsonEncoder(schema, out);
      new GenericDatumWriter<GenericRecord>(schema, new GenericData()).write(record, encoder);
      encoder.flush();
      return out.toString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Không mã hoá được JSON Avro " + schema.getFullName(), e);
    }
  }

  /** {@code payload_hash} của một bản ghi đã đọc. */
  public static byte[] of(GenericRecord record) {
    return Jcs.sha256(Jcs.canonicalize(avroJson(record)));
  }

  /** {@code payload_hash} của thân Avro nhị phân. */
  public static byte[] of(Schema writer, byte[] avro) {
    return of(decode(writer, avro));
  }
}
