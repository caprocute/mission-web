package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.application.port.CdnInvalidator;
import vn.viettel.okrkpi.config.application.port.UiPackEvents;
import vn.viettel.okrkpi.config.application.port.UiPackSigner;
import vn.viettel.okrkpi.config.infrastructure.listener.UiPackPublishedListener;
import vn.viettel.okrkpi.config.infrastructure.persistence.OutboxUiPackEvents;
import vn.viettel.okrkpi.config.infrastructure.persistence.PeriodConfiguration;
import vn.viettel.okrkpi.config.infrastructure.persistence.UiPackConfiguration;
import vn.viettel.okrkpi.config.infrastructure.storage.KeyPairUiPackSigner;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.web.InMemoryJobRegistry;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.config.v1.UiPackDaPhatHanh;

/**
 * Kiểm thử tích hợp CORE-08-11 trên PostgreSQL thật và S3 giả lập: phát hành nhận job {@code
 * RENDER} (cùng {@code Idempotency-Key} trả cùng job), gói ký nằm trên kho với đường dẫn bất biến,
 * phiên bản chuyển {@code DA_PHAT_HANH}, EVT-23 vào {@code config.outbox_event}; consumer EVT-23
 * qua inbox ghi {@code current.json} và vô hiệu CDN đúng một lần; {@code getCurrentUiPack} và
 * {@code getHomeLayout} trả 200 rồi 304 theo ETag; tự phát hành 403 {@code UI-403-SAME-PERSON}. Bộ
 * tuần tự Avro thay bằng bản giả (môi trường kiểm thử không có registry).
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.config.ui-pack.store.bucket=ui-packs-it",
      "mission.config.ui-pack.store.access-key=it",
      "mission.config.ui-pack.store.secret-key=it"
    })
@AutoConfigureMockMvc(addFilters = false)
@Import({FeatureFlagApiIT.AuditConfig.class, UiPackApiIT.UiPackNoticeConfig.class})
@DirtiesContext
class UiPackPublishApiIT extends ContractConformanceTest {

  static final GenericContainer<?> S3 =
      new GenericContainer<>("adobe/s3mock:latest")
          .withExposedPorts(9090)
          .waitingFor(Wait.forListeningPort());

  static final List<List<String>> INVALIDATED = new CopyOnWriteArrayList<>();

  static {
    S3.start();
  }

  @TestConfiguration
  static class PublishConfig {

    @Bean
    @Primary
    UiPackEvents testUiPackEvents(
        @Qualifier("configDataSource") DataSource dataSource, ObjectMapper json) {
      return new OutboxUiPackEvents(
          new OutboxWriter(
              new JdbcTemplate(dataSource),
              "config",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()));
    }

    @Bean
    CdnInvalidator testCdnInvalidator() {
      return INVALIDATED::add;
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
    registry.add("mission.config.ui-pack.store.endpoint", UiPackPublishApiIT::endpoint);
  }

  static String endpoint() {
    return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
  }

  static S3Client s3() {
    return S3Client.builder()
        .region(Region.US_EAST_1)
        .httpClient(UrlConnectionHttpClient.create())
        .forcePathStyle(true)
        .endpointOverride(URI.create(endpoint()))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("it", "it")))
        .build();
  }

  @BeforeAll
  static void bucket() {
    try (S3Client s3 = s3()) {
      s3.createBucket(b -> b.bucket("ui-packs-it"));
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private UiPackSigner signer;
  @Autowired private UiPackPublishedListener listener;
  @Autowired private InboxProperties inboxProperties;
  @Autowired private InboxRetryDlqPublisher dlq;
  @Autowired private InboxMetrics metrics;

  @Autowired
  @Qualifier("configDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = PeriodApiIT.CallerConfig.ACTOR;
  private final UUID reviewer = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  @AfterEach
  void reset() {
    PeriodApiIT.CallerConfig.reset();
    InMemoryJobRegistry.clearActorOverride();
  }

  private void as(UUID user) {
    PeriodApiIT.CallerConfig.current = user;
    PeriodApiIT.CallerConfig.acr = "aal2";
    PeriodApiIT.CallerConfig.authTime = Instant.now().getEpochSecond() - 30;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String key, String ifMatch) {
    return b.header("Idempotency-Key", key)
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON);
  }

  private JsonNode await(String jobId, UUID owner) throws Exception {
    InMemoryJobRegistry.setActorOverride(ActorId.of(owner));
    JsonNode job = null;
    for (int i = 0; i < 200; i++) {
      job = body(perform(get("/api/v1/jobs/" + jobId)));
      String s = job.get("status").asText();
      if (s.equals("HOAN_TAT") || s.equals("THAT_BAI")) {
        break;
      }
      Thread.sleep(50);
    }
    return job;
  }

  private String etag(String url) throws Exception {
    return perform(get(url)).getResponse().getHeader("ETag");
  }

  @Test
  void publishStoresSignedBundleEmitsEvt23AndServesCurrent() throws Exception {
    as(author);
    String packId = body(perform(get("/api/v1/ui/packs"))).get("items").get(0).get("id").asText();
    String base = "/api/v1/ui/packs/" + packId + "/versions";
    perform(
        post(base)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":\"1.0.0\"}"));
    String v = base + "/1.0.0";
    MvcResult submitted =
        perform(write(post(v + "/submit"), UUID.randomUUID().toString(), "\"0\""));
    JsonNode checked = await(body(submitted).get("id").asText(), author);
    assertThat(checked.get("status").asText()).as(checked.toString()).isEqualTo("HOAN_TAT");
    assertThat(perform(get("/api/v1/ui/packs/current")).getResponse().getStatus()).isEqualTo(404);
    String ifMatch = etag(v);

    MvcResult self = perform(write(post(v + "/publish"), UUID.randomUUID().toString(), ifMatch));
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("UI-403-SAME-PERSON");

    as(reviewer);
    String key = UUID.randomUUID().toString();
    MvcResult accepted = perform(write(post(v + "/publish"), key, ifMatch));
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(
        accepted, "/ui/packs/{id}/versions/{v}/publish", PathItem.HttpMethod.POST);
    String jobId = body(accepted).get("id").asText();
    MvcResult replay = perform(write(post(v + "/publish"), key, ifMatch));
    assertThat(body(replay).get("id").asText()).isEqualTo(jobId);
    JsonNode job = await(jobId, reviewer);
    assertThat(job.get("type").asText()).isEqualTo("RENDER");
    assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("HOAN_TAT");

    JsonNode version = body(perform(get(v)));
    assertThat(version.get("status").asText()).isEqualTo("DA_PHAT_HANH");
    UUID versionId = UUID.fromString(version.get("id").asText());
    List<String> topics =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT topic FROM config.outbox_event WHERE aggregate_id = ?")
                    .param(versionId)
                    .query(String.class)
                    .list());
    assertThat(topics).contains(EventTopics.EVT_23_UI_PACK_DA_PHAT_HANH);

    String prefix = "ui-packs/" + tenant.value() + "/1.0.0/";
    byte[] packBytes;
    try (S3Client s3 = s3()) {
      var object = s3.getObjectAsBytes(b -> b.bucket("ui-packs-it").key(prefix + "pack.json"));
      packBytes = object.asByteArray();
      assertThat(object.response().cacheControl()).contains("immutable");
      assertThat(
              s3.getObjectAsBytes(b -> b.bucket("ui-packs-it").key(prefix + "theme.css"))
                  .asUtf8String())
          .contains("--vt-color-primary");
    }
    JsonNode packJson = json.readTree(packBytes);
    String sha = packJson.get("contentHash").asText();

    // Consumer EVT-23 qua inbox: giao hai lần chỉ ghi con trỏ và vô hiệu CDN một lần.
    UiPackDaPhatHanh event =
        UiPackDaPhatHanh.newBuilder()
            .setUiPackId(UUID.fromString(packId))
            .setVersionId(versionId)
            .setContentHash(ByteBuffer.wrap(HexFormat.of().parseHex(sha)))
            .setCdnPaths(List.of("/ui-packs/" + tenant.value() + "/current.json"))
            .setAggregateVersion(version.get("rowVersion").asInt())
            .build();
    InboxMessageProcessor processor =
        PeriodConfiguration.configInboxProcessor(
            dataSource, tm, inboxProperties, (topic, headers, data) -> event, dlq, metrics);
    var endpoint = UiPackConfiguration.publishedEndpoint(listener);
    UUID eventId = UUID.randomUUID();
    assertThat(processor.process(endpoint, record(eventId, versionId), () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(processor.process(endpoint, record(eventId, versionId), () -> {}))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(INVALIDATED)
        .containsExactly(List.of("/ui-packs/" + tenant.value() + "/current.json"));
    try (S3Client s3 = s3()) {
      JsonNode pointer =
          json.readTree(
              s3.getObjectAsBytes(
                      b ->
                          b.bucket("ui-packs-it")
                              .key("ui-packs/" + tenant.value() + "/current.json"))
                  .asByteArray());
      assertThat(pointer.get("pack").asText()).isEqualTo("/" + prefix + "pack.json");
      assertThat(pointer.get("contentHash").asText()).isEqualTo(sha);
      assertThat(pointer.get("signatureAlgorithm").asText()).isEqualTo("ES256");
    }

    as(author);
    MvcResult current = perform(get("/api/v1/ui/packs/current"));
    assertThat(current.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(current, "/ui/packs/current", PathItem.HttpMethod.GET);
    JsonNode runtime = body(current);
    assertThat(runtime).isEqualTo(packJson);
    byte[] signed =
        new vn.viettel.okrkpi.config.application.UiPackCompiler(json)
            .canonical(withoutIntegrity(runtime));
    assertThat(
            KeyPairUiPackSigner.verify(
                signer.publicKey(),
                signed,
                Base64.getDecoder().decode(runtime.get("signature").asText())))
        .isTrue();
    String currentTag = current.getResponse().getHeader("ETag");
    assertThat(currentTag).isEqualTo("\"" + sha + "\"");
    assertThat(
            perform(get("/api/v1/ui/packs/current").header("If-None-Match", currentTag))
                .getResponse()
                .getStatus())
        .isEqualTo(304);

    MvcResult home = perform(get("/api/v1/ui/layouts/home"));
    assertThat(home.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(home, "/ui/layouts/home", PathItem.HttpMethod.GET);
    assertThat(body(home).get("uiPackVersion").asText()).isEqualTo("1.0.0");
    assertThat(body(home).get("mode").asText()).isEqualTo("default");
    assertThat(
            perform(
                    get("/api/v1/ui/layouts/home")
                        .header("If-None-Match", home.getResponse().getHeader("ETag")))
                .getResponse()
                .getStatus())
        .isEqualTo(304);
  }

  private JsonNode withoutIntegrity(JsonNode runtime) {
    var copy = (com.fasterxml.jackson.databind.node.ObjectNode) runtime.deepCopy();
    copy.remove(List.of("contentHash", "signature", "publishedAt"));
    return copy;
  }

  private ConsumerRecord<String, byte[]> record(UUID eventId, UUID versionId) {
    ConsumerRecord<String, byte[]> r =
        new ConsumerRecord<>(
            EventTopics.EVT_23_UI_PACK_DA_PHAT_HANH,
            0,
            0L,
            tenant.value() + ":" + versionId,
            new byte[] {0});
    r.headers().add(new RecordHeader(OutboxHeaders.EVENT_ID, bytes(eventId.toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.TENANT_ID, bytes(tenant.value().toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.AGGREGATE_VERSION, bytes("3")));
    return r;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private <T> T inTenant(java.util.function.Function<JdbcClient, T> query) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> query.apply(jdbc)));
  }
}
