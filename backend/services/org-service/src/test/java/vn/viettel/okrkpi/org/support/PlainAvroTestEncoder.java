package vn.viettel.okrkpi.org.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.org.infrastructure.republish.PayloadEncoder;

/** Avro nhị phân thuần với mã lược đồ giả cố định, thay Apicurio trong kiểm thử tích hợp. */
public final class PlainAvroTestEncoder implements PayloadEncoder {

  /** Mã lược đồ giả ghi vào tiêu đề {@code apicurio.value.globalId}. */
  public static final long GLOBAL_ID = 4242L;

  @Override
  public Encoded encode(String topic, SpecificRecord payload) {
    EventContracts.trustContractPackages();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
    try {
      new SpecificDatumWriter<SpecificRecord>(payload.getSchema()).write(payload, enc);
      enc.flush();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return new Encoded(out.toByteArray(), GLOBAL_ID, 1);
  }
}
