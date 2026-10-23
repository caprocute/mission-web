package vn.viettel.okrkpi.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.apache.avro.Schema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vn.vtokr.sys.v1.AuditEvent;
import vn.vtokr.sys.v1.TenantProvisioned;

/** Lược đồ tra registry của lớp sinh trùng lược đồ của tệp hợp đồng đã đăng ký vào Apicurio. */
class RegistrySchemaTest {

  static java.util.stream.Stream<Arguments> hopDong() {
    return java.util.stream.Stream.of(
        Arguments.of(AuditEvent.getClassSchema(), "contracts/events/avro/sys/AuditEvent.avsc"),
        Arguments.of(
            TenantProvisioned.getClassSchema(),
            "contracts/events/avro/sys/TenantProvisioned.avsc"));
  }

  @ParameterizedTest
  @MethodSource("hopDong")
  void trungTepHopDong(Schema generated, String tep) throws IOException {
    assertTrue(
        generated.toString().contains(EventContracts.JAVA_STRING_PROP),
        "lớp sinh mang thuộc tính Java");
    Schema registry = EventContracts.registrySchema(generated);
    assertFalse(registry.toString().contains(EventContracts.JAVA_STRING_PROP));
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(tep)) {
      assertEquals(new Schema.Parser().parse(in), registry);
    }
  }
}
