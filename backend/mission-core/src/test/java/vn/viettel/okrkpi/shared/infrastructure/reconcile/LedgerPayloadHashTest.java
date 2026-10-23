package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.viettel.okrkpi.shared.kernel.Jcs;
import vn.vtokr.task.v1.NhiemVuDaGiao;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/**
 * Băm tải trọng của R2 khớp bộ chuyển tiếp Go của ledger-service trên vector chung {@code
 * data/golden/ledger-canon/payload-avro} (union kiểu logic, decimal âm, timestamp-micros, chữ Việt
 * dạng tổ hợp).
 */
class LedgerPayloadHashTest {

  private static final Path GOLDEN =
      Path.of("..", "..", "data", "golden", "ledger-canon", "payload-avro");
  private static final Map<String, Schema> SCHEMAS =
      Map.of(
          "task.task.assigned.v1", NhiemVuDaGiao.getClassSchema(),
          "task.task.progressed.v1", TienDoDaCapNhat.getClassSchema());

  @ParameterizedTest
  @ValueSource(strings = {"evt04-nhiem-vu-da-giao.json", "evt05-tien-do-da-cap-nhat.json"})
  void khopVectorCuaLedgerService(String file) throws Exception {
    JsonNode v = new ObjectMapper().readTree(Files.readString(GOLDEN.resolve(file)));
    byte[] avro = Base64.getDecoder().decode(v.get("avroBase64").asText());
    Schema schema = SCHEMAS.get(v.get("topic").asText());

    var record = LedgerPayloadHash.decode(schema, avro);
    assertThat(Jcs.canonicalize(LedgerPayloadHash.avroJson(record)))
        .isEqualTo(v.get("canonicalJson").asText());
    assertThat(Jcs.hex(LedgerPayloadHash.of(schema, avro)))
        .isEqualTo(v.get("payloadHash").asText());
  }

  @Test
  void thanThuaByteLaLechLuocDo() throws Exception {
    JsonNode v =
        new ObjectMapper()
            .readTree(Files.readString(GOLDEN.resolve("evt05-tien-do-da-cap-nhat.json")));
    byte[] avro = Base64.getDecoder().decode(v.get("avroBase64").asText());
    byte[] longer = java.util.Arrays.copyOf(avro, avro.length + 1);
    assertThatThrownBy(() -> LedgerPayloadHash.decode(TienDoDaCapNhat.getClassSchema(), longer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte thừa");
    assertThatThrownBy(
            () -> LedgerPayloadHash.decode(TienDoDaCapNhat.getClassSchema(), new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
