package vn.viettel.okrkpi.oversight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.infrastructure.client.AuditIndexConfiguration;
import vn.viettel.okrkpi.oversight.infrastructure.client.OpenSearchAuditIndex;
import vn.viettel.okrkpi.oversight.infrastructure.listener.AuditIndexerListener;
import vn.viettel.okrkpi.oversight.infrastructure.web.OversightCaller;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.sys.v1.AuditEvent;
import vn.vtokr.sys.v1.AuditTarget;

/**
 * Kiểm thử tích hợp CORE-07-12 trên PostgreSQL và OpenSearch thật: sự kiện {@code XEM_C2} của
 * {@code sys.audit.v1} qua inbox của schema oversight vào chỉ mục {@code audit-<tenant>-<tháng>} và
 * tra được trong 10 giây; nhận trùng không sinh tài liệu trùng; tenant khác không thấy; không phải
 * cán bộ thanh tra nhận 403, phiên chưa AAL2 nhận 401 bước lên; mỗi lần tra cứu ghi đúng một sự
 * kiện kiểm toán vào {@code oversight.outbox_event}; tài liệu chỉ mục không chứa giá trị cột C2.
 * Thân Avro đưa thẳng vào bộ xử lý inbox (bộ giải mã kiểm thử), không qua Kafka.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import(AccessLogApiIT.Beans.class)
@DirtiesContext
class AccessLogApiIT extends ContractConformanceTest {

  private static final GenericContainer<?> OPENSEARCH =
      new GenericContainer<>(
              DockerImageName.parse("mirror.gcr.io/opensearchproject/opensearch:2.19.1")
                  .asCompatibleSubstituteFor("opensearchproject/opensearch"))
          .withEnv("discovery.type", "single-node")
          .withEnv("DISABLE_SECURITY_PLUGIN", "true")
          .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
          .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withExposedPorts(9200)
          .waitingFor(
              Wait.forHttp("/_cluster/health?wait_for_status=yellow")
                  .forPort(9200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  static {
    OPENSEARCH.start();
  }

  private static URI endpoint() {
    return URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
    registry.add("mission.oversight.audit-index.url", () -> endpoint().toString());
  }

  /** Người gọi và bộ tuần tự outbox kiểm thử (không có registry Avro). */
  static class Beans {
    static volatile UUID user = UUID.randomUUID();
    static volatile List<String> roles = List.of("inspector");
    static volatile String acr = "aal2";

    @Bean
    OversightCaller oversightTestCaller() {
      return new OversightCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(user);
        }

        @Override
        public List<String> roles() {
          return roles;
        }

        @Override
        public Optional<String> acr() {
          return Optional.ofNullable(acr);
        }
      };
    }

    @Bean
    OutboxPayloadSerializer oversightTestSerializer() {
      return (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private AuditIndexerListener listener;
  @Autowired private InboxProperties inboxProperties;
  @Autowired private InboxRetryDlqPublisher dlq;
  @Autowired private InboxMetrics metrics;

  @Autowired
  @Qualifier("oversightDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("oversightTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final TenantId other = TenantId.of(UUID.randomUUID());
  private final UUID viewer = UUID.randomUUID();
  private final UUID subject = UUID.randomUUID();

  @BeforeAll
  static void installTemplates() throws Exception {
    try (OpenSearchAuditIndex admin =
        new OpenSearchAuditIndex(endpoint(), null, null, null, Set.of(), new ObjectMapper())) {
      admin.installTemplates();
    }
  }

  @AfterEach
  void resetCaller() {
    Beans.user = UUID.randomUUID();
    Beans.roles = List.of("inspector");
    Beans.acr = "aal2";
  }

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OVERSIGHT;
  }

  @Test
  void indexedAccessIsSearchableIdempotentTenantIsolatedAndAudited() throws Exception {
    UUID eventId = UUID.randomUUID();
    AuditEvent seen = c2Access(subject.toString(), false);
    AuditEvent own = c2Access(viewer.toString(), true);
    Instant start = Instant.now();
    assertThat(process(eventId, tenant, seen)).isEqualTo(InboxOutcome.PROCESSED);
    // Nhận trùng: inbox bỏ qua; kể cả khi chạy lại, _id cố định không sinh bản trùng.
    assertThat(process(eventId, tenant, seen)).isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(process(UUID.randomUUID(), tenant, own)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(process(UUID.randomUUID(), tenant, seen)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(process(UUID.randomUUID(), other, seen)).isEqualTo(InboxOutcome.PROCESSED);

    JsonNode items = awaitItems(tenant, 3, b -> b);
    assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
    assertThat(items).hasSize(3);
    JsonNode first = items.get(0);
    assertThat(first.get("actorId").asText()).isEqualTo(viewer.toString());
    assertThat(first.get("objectType").asText()).isEqualTo("appraisal.ho_so");
    assertThat(first.has("system")).isTrue();
    // Tài liệu chỉ có tên cột, không có giá trị C2.
    String raw = rawIndex();
    assertThat(raw).contains("\"column\":\"diem_tu_cham\"").doesNotContain("8.75");

    // Lọc theo đối tượng và chỉ người khác xem.
    MvcResult filtered =
        search(
            tenant,
            get("/api/v1/oversight/access-logs")
                .param("objectType", "appraisal.ho_so")
                .param("objectId", subject.toString())
                .param("othersOnly", "true")
                .param("limit", "1"));
    assertThat(filtered.getResponse().getStatus())
        .as(filtered.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(200);
    assertResponseConforms(filtered, "/oversight/access-logs", PathItem.HttpMethod.GET);
    JsonNode page = body(filtered);
    assertThat(page.get("items")).hasSize(1);
    assertThat(page.get("items").get(0).get("self").asBoolean()).isFalse();
    String cursor = page.get("nextCursor").asText();
    JsonNode next =
        body(
            search(
                tenant,
                get("/api/v1/oversight/access-logs")
                    .param("objectType", "appraisal.ho_so")
                    .param("objectId", subject.toString())
                    .param("othersOnly", "true")
                    .param("limit", "1")
                    .param("cursor", cursor)));
    assertThat(next.get("items")).hasSize(1);
    assertThat(next.get("items").get(0).get("eventId").asText())
        .isNotEqualTo(page.get("items").get(0).get("eventId").asText());

    // Tenant khác chỉ thấy tài liệu của mình.
    assertThat(awaitItems(other, 1, b -> b)).hasSize(1);

    // Mỗi lần tra cứu ghi đúng một sự kiện kiểm toán.
    UUID inspector = UUID.randomUUID();
    Beans.user = inspector;
    int before = audits(tenant, inspector);
    search(tenant, get("/api/v1/oversight/access-logs"));
    assertThat(audits(tenant, inspector)).isEqualTo(before + 1);
  }

  @Test
  void nonInspectorForbiddenAndAal1NeedsStepUp() throws Exception {
    Beans.roles = List.of("employee");
    MvcResult forbidden = search(tenant, get("/api/v1/oversight/access-logs"));
    assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
    assertResponseConforms(forbidden, "/oversight/access-logs", PathItem.HttpMethod.GET);

    Beans.roles = List.of("inspector");
    Beans.acr = "aal1";
    MvcResult stepUp = search(tenant, get("/api/v1/oversight/access-logs"));
    assertThat(stepUp.getResponse().getStatus()).isEqualTo(401);

    Beans.acr = "aal2";
    MvcResult invalid =
        search(
            tenant,
            get("/api/v1/oversight/access-logs")
                .param("from", "2026-10-02T00:00:00Z")
                .param("to", "2026-10-01T00:00:00Z"));
    assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
    assertThat(audits(tenant, Beans.user)).isZero();
  }

  private AuditEvent c2Access(String objectId, boolean self) {
    return AuditEvent.newBuilder()
        .setAction("XEM_C2")
        .setObjectType("appraisal.ho_so")
        .setObjectId(objectId)
        .setOutcome("THANH_CONG")
        .setPurpose("DANH_GIA")
        .setActorRole("manager")
        .setSystem(false)
        .setTargets(
            List.of(
                AuditTarget.newBuilder()
                    .setObjectType("appraisal.ho_so")
                    .setObjectId(objectId)
                    .setColumn("diem_tu_cham")
                    .setDekPurpose("HO_SO")
                    .setSelf(self)
                    .build()))
        .build();
  }

  private InboxOutcome process(UUID eventId, TenantId t, AuditEvent event) {
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new org.springframework.jdbc.core.JdbcTemplate(dataSource),
            tm,
            "oversight",
            (topic, headers, data) -> event,
            dlq,
            metrics,
            inboxProperties.retry(),
            Clock.systemUtc());
    InboxEndpoint endpoint = AuditIndexConfiguration.endpoint(listener);
    ConsumerRecord<String, byte[]> r =
        new ConsumerRecord<>(
            EventTopics.SYS_AUDIT_V1, 0, 0L, t.value() + ":" + event.getObjectId(), new byte[] {0});
    r.headers().add(new RecordHeader(OutboxHeaders.EVENT_ID, bytes(eventId.toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.TENANT_ID, bytes(t.value().toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.CLASSIFICATION, bytes("C1")));
    r.headers().add(new RecordHeader(OutboxHeaders.ACTOR_ID, bytes(viewer.toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.OCCURRED_AT, bytes(Instant.now().toString())));
    return processor.process(endpoint, r, () -> {});
  }

  private JsonNode awaitItems(
      TenantId t,
      int expected,
      Function<MockHttpServletRequestBuilder, MockHttpServletRequestBuilder> f)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    JsonNode items = null;
    while (System.nanoTime() < deadline) {
      MvcResult r = search(t, f.apply(get("/api/v1/oversight/access-logs")));
      assertThat(r.getResponse().getStatus())
          .as(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
          .isEqualTo(200);
      items = body(r).get("items");
      if (items.size() >= expected) {
        return items;
      }
      Thread.sleep(200);
    }
    return items;
  }

  private String rawIndex() throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        endpoint().resolve("/audit-" + tenant.value() + "-*/_search"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    return r.body();
  }

  private MvcResult search(TenantId t, MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(t, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private int audits(TenantId t, UUID actor) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        t,
        () ->
            new TransactionTemplate(tm)
                .execute(
                    s ->
                        jdbc.sql(
                                "SELECT count(*) FROM oversight.outbox_event WHERE topic = ?"
                                    + " AND headers ->> 'actorid' = ?")
                            .param(EventTopics.SYS_AUDIT_V1)
                            .param(actor.toString())
                            .query(Integer.class)
                            .single()));
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
