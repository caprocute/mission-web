package vn.viettel.okrkpi.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import vn.viettel.okrkpi.events.EventContracts;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.sys.v1.AuditEvent;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/**
 * NEN-011: serde tra được lược đồ đã đăng ký từ tệp {@code .avsc} của hợp đồng (cùng cách Job
 * {@code apicurio-dang-ky} và {@code infra/dev/scripts/dang-ky-luoc-do.sh}) trên Apicurio Registry
 * 3.3.3, kể cả lược đồ có tham chiếu ({@code org.bootstrap.v1}); trước bản sửa mọi lệnh ghi có sự
 * kiện lỗi 500 vì lớp sinh mang thuộc tính {@code avro.java.string}.
 */
class ApicurioPayloadSerializerIT {

  private static final String IMAGE =
      System.getProperty("apicurio.image", "apicurio/apicurio-registry:3.3.3");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static GenericContainer<?> registry;
  private static String api;
  private static final Map<String, Long> GLOBAL_IDS = new HashMap<>();

  @BeforeAll
  static void start() throws Exception {
    registry =
        new GenericContainer<>(IMAGE)
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/apis/registry/v3/system/info")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    registry.start();
    api = "http://" + registry.getHost() + ":" + registry.getMappedPort(8080) + "/apis/registry/v3";
    register("sys.audit.v1", "sys/AuditEvent", List.of());
    register("task.task.progressed.v1", "task/TienDoDaCapNhat", List.of());
    register("org.unit.changed.v1", "org/DonViDaThayDoi", List.of());
    register("org.person.changed.v1", "org/NhanSuDaThayDoi", List.of());
    register("org.assignment.changed.v1", "org/PhanCongDaThayDoi", List.of());
    register(
        "org.bootstrap.v1",
        "org/OrgBootstrap",
        List.of(
            Map.of("org.unit.changed.v1", "vn.vtokr.org.v1.DonViDaThayDoi"),
            Map.of("org.person.changed.v1", "vn.vtokr.org.v1.NhanSuDaThayDoi"),
            Map.of("org.assignment.changed.v1", "vn.vtokr.org.v1.PhanCongDaThayDoi")));
  }

  @AfterAll
  static void stop() {
    if (registry != null) {
      registry.stop();
    }
  }

  @Test
  void traDuocLuocDoDangKyTuTepHopDongVaGiaiMaLaiDuoc() throws IOException {
    AuditEvent audit =
        AuditEvent.newBuilder()
            .setAction("DOI_NGU_CANH")
            .setObjectType("ASSIGNMENT")
            .setObjectId(UUID.randomUUID().toString())
            .setOutcome("THANH_CONG")
            .build();
    TienDoDaCapNhat tienDo = OutboxFixtures.tienDo(UUID.randomUUID(), 3);
    try (ApicurioPayloadSerializer serializer = new ApicurioPayloadSerializer(api, Map.of())) {
      OutboxPayloadSerializer.SerializedPayload a = serializer.serialize("sys.audit.v1", audit);
      assertThat(a.globalId()).isEqualTo(GLOBAL_IDS.get("sys.audit.v1"));
      assertThat(decode(a.bytes(), AuditEvent.getClassSchema())).isEqualTo(audit);

      OutboxPayloadSerializer.SerializedPayload t =
          serializer.serialize("task.task.progressed.v1", tienDo);
      assertThat(t.globalId()).isEqualTo(GLOBAL_IDS.get("task.task.progressed.v1"));
      assertThat(t.schemaVersion()).isEqualTo(1);
      assertThat(decode(t.bytes(), TienDoDaCapNhat.getClassSchema())).isEqualTo(tienDo);
    }
  }

  @Test
  void luocDoCoThamChieuTraTheoArtifactKhiTrungLopSinh() throws IOException {
    OrgBootstrap marker =
        OrgBootstrap.newBuilder()
            .setBatchId(UUID.randomUUID())
            .setSeq(1L)
            .setIsLast(true)
            .setEntity(null)
            .setMarker("END")
            .build();
    try (ApicurioPayloadSerializer serializer = new ApicurioPayloadSerializer(api, Map.of())) {
      OutboxPayloadSerializer.SerializedPayload p =
          serializer.serialize("org.bootstrap.v1", marker);
      assertThat(p.globalId()).isEqualTo(GLOBAL_IDS.get("org.bootstrap.v1"));
      assertThat(decode(p.bytes(), OrgBootstrap.getClassSchema())).isEqualTo(marker);
    }
  }

  private static Object decode(byte[] bytes, Schema schema) throws IOException {
    EventContracts.trustContractPackages();
    SpecificDatumReader<Object> reader = new SpecificDatumReader<>(schema);
    return reader.read(null, DecoderFactory.get().binaryDecoder(bytes, null));
  }

  /**
   * Đăng ký tệp hợp đồng như {@code dang_ky.py}: nội dung tệp nguyên văn, tham chiếu theo tên kiểu.
   */
  private static void register(String topic, String tep, List<Map<String, String>> refs)
      throws Exception {
    String content;
    try (InputStream in =
        ApicurioPayloadSerializerIT.class
            .getClassLoader()
            .getResourceAsStream("contracts/events/avro/" + tep + ".avsc")) {
      assertThat(in).as(tep).isNotNull();
      content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    ObjectNode body = JSON.createObjectNode();
    body.put("artifactId", topic + "-value").put("artifactType", "AVRO");
    ObjectNode c = body.putObject("firstVersion").putObject("content");
    c.put("content", content).put("contentType", "application/json");
    ArrayNode references = c.putArray("references");
    for (Map<String, String> r : refs) {
      Map.Entry<String, String> e = r.entrySet().iterator().next();
      references
          .addObject()
          .put("groupId", "default")
          .put("artifactId", e.getKey() + "-value")
          .put("version", "1")
          .put("name", e.getValue());
    }
    HttpResponse<String> res =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(api + "/groups/default/artifacts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    JsonNode version = JSON.readTree(res.body()).get("version");
    GLOBAL_IDS.put(topic, version.get("globalId").asLong());
  }
}
