package vn.viettel.okrkpi.iam.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.events.EventTopics;
import vn.vtokr.org.v1.NhanSuDaThayDoi;

/**
 * DV-02-06 trên Kafka và Keycloak 26 thật: tạo, sửa, khoá người dùng; nhận trùng và sự kiện cũ không ghi đè;
 * Keycloak ngừng thì thông điệp được thử lại rồi vào thư chết; độ trễ đồng bộ tối đa không quá 5 giây.
 */
@SpringBootTest(properties = {"mission.user-sync.retry.initial=200ms", "mission.user-sync.retry.max-elapsed=2s",
    "mission.user-sync.keycloak.client-secret=" + UserSyncKeycloakIT.CLIENT_SECRET})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserSyncKeycloakIT {

  static final String CLIENT_SECRET = "kiem-thu-bi-mat-client";
  private static final String REALM = "mission";
  private static final UUID TENANT = UUID.fromString("0192f000-0000-7000-8000-0000000000a1");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
  private static final String USER_PASSWORD = "Kt-" + UUID.randomUUID();
  private static final String DLQ = EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI + ".dlq." + PersonListener.CONSUMER;

  static final KafkaContainer KAFKA = new KafkaContainer(
      DockerImageName.parse("mirror.gcr.io/apache/kafka:3.9.1").asCompatibleSubstituteFor("apache/kafka"));
  static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
      System.getProperty("keycloak.image", "mirror.gcr.io/keycloak/keycloak:26.4.0")).withExposedPorts(8080)
      .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "kc-admin").withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
      .withCommand("start-dev")
      .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

  private static String base;
  private static String orgId;
  private static KafkaProducer<String, byte[]> producer;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    KAFKA.start();
    KEYCLOAK.start();
    base = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    seedRealm();
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("mission.user-sync.keycloak.base-url", () -> base);
  }

  @AfterAll
  static void stop() {
    if (producer != null) {
      producer.close();
    }
    KEYCLOAK.stop();
    KAFKA.stop();
  }

  @Autowired
  MeterRegistry meters;

  @Test
  @Order(1)
  void taoSuaVaBoQuaSuKienTrungHoacCu() throws Exception {
    UUID p = UUID.randomUUID();
    send(p, "Nguyễn Văn An", "DANG_CONG_TAC", 1);
    JsonNode u = awaitUser(p, n -> "An".equals(n.path("firstName").asText()));
    assertThat(u.path("lastName").asText()).isEqualTo("Nguyễn Văn");
    assertThat(u.path("enabled").asBoolean()).isTrue();
    assertThat(u.path("attributes").path("tenant_id").get(0).asText()).isEqualTo(TENANT.toString());
    JsonNode members = JSON.readTree(adminApi("GET", "/organizations/" + orgId + "/members", null).body());
    assertThat(members.toString()).contains(p.toString());

    send(p, "Nguyễn Văn Ân", "DANG_CONG_TAC", 3);
    awaitUser(p, n -> "Ân".equals(n.path("firstName").asText()));
    send(p, "Tên cũ", "DANG_CONG_TAC", 2);
    send(p, "Tên trùng", "DANG_CONG_TAC", 3);
    UUID marker = UUID.randomUUID();
    send(marker, "Mốc Kiểm Thử", "DANG_CONG_TAC", 1);
    awaitUser(marker, n -> true);
    JsonNode after = JSON.readTree(adminApi("GET", "/users/" + p, null).body());
    assertThat(after.path("firstName").asText()).isEqualTo("Ân");
    assertThat(meters.get("iam_user_sync_events_total").tag("outcome", "SKIPPED").counter().count()).isEqualTo(2);
  }

  @Test
  @Order(2)
  void thoiViecThiKhoaVaRefreshTokenCuBiTuChoi() throws Exception {
    UUID p = UUID.randomUUID();
    send(p, "Lê Cường", "DANG_CONG_TAC", 1);
    awaitUser(p, n -> true);
    ObjectNode cred = JSON.createObjectNode().put("type", "password").put("value", USER_PASSWORD).put("temporary", false);
    assertThat(adminApi("PUT", "/users/" + p + "/reset-password", cred).statusCode()).isEqualTo(204);
    // Realm kiểm thử đòi email để đăng nhập bằng mật khẩu; tài khoản thật đăng nhập qua IdP của cơ quan.
    ObjectNode rep = (ObjectNode) JSON.readTree(adminApi("GET", "/users/" + p, null).body());
    rep.put("email", p + "@kiem-thu.invalid").put("emailVerified", true);
    assertThat(adminApi("PUT", "/users/" + p, rep).statusCode()).isEqualTo(204);
    HttpResponse<String> login = token("grant_type=password&client_id=kiem-thu-cli&username=" + p + "&password="
        + URLEncoder.encode(USER_PASSWORD, StandardCharsets.UTF_8), REALM);
    assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
    String refresh = JSON.readTree(login.body()).get("refresh_token").asText();

    send(p, "Lê Cường", "THOI_VIEC", 2);
    awaitUser(p, n -> !n.path("enabled").asBoolean());
    HttpResponse<String> r = token("grant_type=refresh_token&client_id=kiem-thu-cli&refresh_token=" + refresh, REALM);
    assertThat(r.statusCode()).isEqualTo(400);
  }

  @Test
  @Order(3)
  void donViHetHieuLucThiGoNguCanhVaDangXuat() throws Exception {
    UUID p = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    send(p, "Vũ Giang", "DANG_CONG_TAC", 1);
    awaitUser(p, n -> true);
    ObjectNode rep = (ObjectNode) JSON.readTree(adminApi("GET", "/users/" + p, null).body());
    rep.put("email", p + "@kiem-thu.invalid").put("emailVerified", true);
    ((ObjectNode) rep.get("attributes")).putArray("mission_context_unit").add(unit.toString());
    assertThat(adminApi("PUT", "/users/" + p, rep).statusCode()).isEqualTo(204);
    ObjectNode cred = JSON.createObjectNode().put("type", "password").put("value", USER_PASSWORD).put("temporary", false);
    assertThat(adminApi("PUT", "/users/" + p + "/reset-password", cred).statusCode()).isEqualTo(204);
    HttpResponse<String> login = token("grant_type=password&client_id=kiem-thu-cli&username=" + p + "&password="
        + URLEncoder.encode(USER_PASSWORD, StandardCharsets.UTF_8), REALM);
    assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
    String refresh = JSON.readTree(login.body()).get("refresh_token").asText();

    sendUnit(unit, java.time.LocalDate.now().plusDays(30));
    sendUnit(unit, java.time.LocalDate.now().minusDays(1));
    awaitUser(p, n -> n.path("attributes").path("mission_context_unit").isMissingNode());
    HttpResponse<String> r = token("grant_type=refresh_token&client_id=kiem-thu-cli&refresh_token=" + refresh, REALM);
    assertThat(r.statusCode()).isEqualTo(400);
  }

  @Test
  @Order(4)
  void doTreDongBoToiDaKhongQuaNamGiay() throws Exception {
    List<UUID> people = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      UUID p = UUID.randomUUID();
      people.add(p);
      send(p, "Cán Bộ Số" + i, "DANG_CONG_TAC", 1);
    }
    for (UUID p : people) {
      awaitUser(p, n -> true);
    }
    double max = meters.get("iam_user_sync_lag").timer().max(TimeUnit.MILLISECONDS);
    System.out.println("Độ trễ đồng bộ tối đa (ms): " + max);
    assertThat(max).isLessThanOrEqualTo(5000);
  }

  @Test
  @Order(5)
  void keycloakNgungThiThuLaiRoiVaoThuChet() throws Exception {
    UUID p = UUID.randomUUID();
    var docker = KEYCLOAK.getDockerClient();
    docker.pauseContainerCmd(KEYCLOAK.getContainerId()).exec();
    try {
      send(p, "Hoàng Dung", "DANG_CONG_TAC", 1);
      Properties c = new Properties();
      c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
      c.put(ConsumerConfig.GROUP_ID_CONFIG, "kiem-thu-dlq");
      c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      try (var consumer = new KafkaConsumer<>(c, new StringDeserializer(), new ByteArrayDeserializer())) {
        consumer.subscribe(List.of(DLQ));
        List<ConsumerRecord<String, byte[]>> got = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).until(() -> {
          consumer.poll(Duration.ofMillis(500)).forEach(got::add);
          return got.stream().anyMatch(rec -> rec.key().equals(TENANT + ":" + p));
        });
      }
    } finally {
      docker.unpauseContainerCmd(KEYCLOAK.getContainerId()).exec();
    }
    assertThat(meters.get("iam_user_sync_dlq_total").counter().count()).isGreaterThanOrEqualTo(1);
  }

  private JsonNode awaitUser(UUID id, java.util.function.Predicate<JsonNode> ok) {
    JsonNode[] out = new JsonNode[1];
    await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
      HttpResponse<String> r = adminApi("GET", "/users/" + id, null);
      if (r.statusCode() != 200) {
        return false;
      }
      out[0] = JSON.readTree(r.body());
      return ok.test(out[0]);
    });
    return out[0];
  }

  private static void send(UUID person, String name, String status, int version) throws Exception {
    if (producer == null) {
      producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
          new StringSerializer(), new ByteArraySerializer());
    }
    NhanSuDaThayDoi e = NhanSuDaThayDoi.newBuilder().setPersonId(person).setDisplayName(name).setSubjectGroupId(null)
        .setIsPartyMember(false).setStatus(status).setSourceVersion(version).setAggregateVersion(version).build();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
    new SpecificDatumWriter<>(NhanSuDaThayDoi.class).write(e, enc);
    enc.flush();
    var rec = new ProducerRecord<>(EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI, TENANT + ":" + person, out.toByteArray());
    rec.headers().add("tenantid", TENANT.toString().getBytes(StandardCharsets.UTF_8));
    rec.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    rec.headers().add("occurredat", Instant.now().toString().getBytes(StandardCharsets.UTF_8));
    producer.send(rec).get();
  }

  private static void sendUnit(UUID unit, java.time.LocalDate validTo) throws Exception {
    vn.vtokr.org.v1.DonViDaThayDoi e = vn.vtokr.org.v1.DonViDaThayDoi.newBuilder().setUnitId(unit)
        .setVersionId(UUID.randomUUID()).setSourceVersion(1).setCode("DV-KT").setName("Đơn vị kiểm thử")
        .setShortName(null).setUnitType("CO_QUAN").setSubjectGroupId(null).setIsCollectiveSubject(true)
        .setFlows(List.of("NHA_NUOC")).setLevel("TINH").setValidFrom(java.time.LocalDate.of(2026, 1, 1))
        .setValidTo(validTo).setChangeOrderId(null).setAggregateVersion(1).setPartyParentId(null)
        .setAdminParentId(null).build();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
    new SpecificDatumWriter<>(vn.vtokr.org.v1.DonViDaThayDoi.class).write(e, enc);
    enc.flush();
    var rec = new ProducerRecord<>(EventTopics.EVT_14_DON_VI_DA_THAY_DOI, TENANT + ":" + unit, out.toByteArray());
    rec.headers().add("tenantid", TENANT.toString().getBytes(StandardCharsets.UTF_8));
    producer.send(rec).get();
  }

  private static void seedRealm() throws Exception {
    ObjectNode realm = JSON.createObjectNode().put("realm", REALM).put("enabled", true)
        .put("organizationsEnabled", true);
    assertThat(adminApi("POST", "", realm).statusCode()).isEqualTo(201);
    ObjectNode profile = (ObjectNode) JSON.readTree(adminApi("GET", "/users/profile", null).body());
    profile.put("unmanagedAttributePolicy", "ADMIN_EDIT");
    assertThat(adminApi("PUT", "/users/profile", profile).statusCode()).isEqualTo(200);
    ObjectNode cli = JSON.createObjectNode().put("clientId", "kiem-thu-cli").put("publicClient", true)
        .put("directAccessGrantsEnabled", true).put("standardFlowEnabled", false);
    assertThat(adminApi("POST", "/clients", cli).statusCode()).isEqualTo(201);
    ObjectNode svc = JSON.createObjectNode().put("clientId", "iam-user-sync").put("publicClient", false)
        .put("secret", CLIENT_SECRET).put("serviceAccountsEnabled", true).put("standardFlowEnabled", false);
    assertThat(adminApi("POST", "/clients", svc).statusCode()).isEqualTo(201);
    String svcId = clientId("iam-user-sync");
    String saUser = JSON.readTree(adminApi("GET", "/clients/" + svcId + "/service-account-user", null).body())
        .get("id").asText();
    String rm = clientId("realm-management");
    ArrayNode roles = JSON.createArrayNode();
    for (String r : new String[] {"view-users", "manage-users", "query-users", "manage-realm"}) {
      roles.add(JSON.readTree(adminApi("GET", "/clients/" + rm + "/roles/" + r, null).body()));
    }
    assertThat(adminApi("POST", "/users/" + saUser + "/role-mappings/clients/" + rm, roles).statusCode())
        .isEqualTo(204);
    ObjectNode org = JSON.createObjectNode().put("name", "tenant-kiem-thu").put("alias", "tenant-kiem-thu");
    org.putArray("domains").addObject().put("name", "tenant-kiem-thu.example.invalid");
    org.putObject("attributes").putArray("tenant_id").add(TENANT.toString());
    HttpResponse<String> created = adminApi("POST", "/organizations", org);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    orgId = JSON.readTree(adminApi("GET", "/organizations?search=tenant-kiem-thu", null).body()).get(0).get("id")
        .asText();
  }

  private static String clientId(String clientId) throws Exception {
    return JSON.readTree(adminApi("GET", "/clients?clientId=" + clientId, null).body()).get(0).get("id").asText();
  }

  private static HttpResponse<String> token(String form, String realm) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/realms/" + realm + "/protocol/openid-connect/token"))
        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> adminApi(String method, String path, Object body) throws Exception {
    String admin = JSON.readTree(token("grant_type=password&client_id=admin-cli&username=kc-admin&password="
        + URLEncoder.encode(ADMIN_PASSWORD, StandardCharsets.UTF_8), "master").body()).get("access_token").asText();
    String url = path.isEmpty() ? base + "/admin/realms" : base + "/admin/realms/" + REALM + path;
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + admin)
        .header("Content-Type", "application/json");
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
}
