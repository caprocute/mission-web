package vn.viettel.okrkpi.esign.infrastructure.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.esign.application.sign.EsignService;
import vn.viettel.okrkpi.esign.application.sign.SourceDocumentPort;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * esign-service trên PostgreSQL thật (DV-10-01): tạo yêu cầu qua API với nhà cung cấp giả lập, đi
 * hết {@code CHO_XAC_NHAN} → {@code DA_KY} với đúng một EVT-18 trong outbox; quá hạn chuyển {@code
 * HET_HAN}; gọi lại cùng {@code Idempotency-Key} trả cùng yêu cầu; thiếu AAL2 bị chặn.
 */
@SpringBootTest(
    properties = {
      "okrkpi.esign.fake-provider.enabled=true",
      "okrkpi.esign.fake-provider.confirm-after=0s",
      "okrkpi.esign.provider-ids.MYSIGN=fake",
      "okrkpi.esign.confirm-window=2s",
      "okrkpi.esign.worker.enabled=false",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
class EsignFlowIT {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000f001");
  private static final UUID SIGNER = UUID.fromString("0192f1a2-0000-7000-8000-00000000f002");

  static final Map<UUID, SourceDocumentPort.SourceDocuments> SOURCES = new ConcurrentHashMap<>();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_esign")
          .withUsername("postgres")
          .withPassword("postgres");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    @Primary
    SourceDocumentPort fakeSources() {
      return (tenant, module, ref, purpose) -> Optional.ofNullable(SOURCES.get(ref));
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private EsignService service;
  @Autowired private FakeSignatureProvider fake;

  private SignPollingWorker worker;

  @BeforeEach
  void setUp() {
    worker = new SignPollingWorker(new PendingRequestScanner(jdbc), service, 100);
    fake.nextOutcome(FakeSignatureProvider.Outcome.SIGN);
  }

  private static UUID source(int docs) {
    UUID ref = UUID.randomUUID();
    List<SourceDocumentPort.Document> documents =
        java.util.stream.IntStream.range(0, docs)
            .mapToObj(
                i -> {
                  byte[] sha = new byte[32];
                  sha[0] = (byte) (i + 1);
                  sha[31] = (byte) ref.hashCode();
                  return new SourceDocumentPort.Document(
                      "PDF_A", "dossier/" + ref + "/" + i, sha, SignatureFormat.PADES_LTA);
                })
            .toList();
    SOURCES.put(ref, new SourceDocumentPort.SourceDocuments(true, Set.of(SIGNER), documents));
    return ref;
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String acr) {
    return r.with(
        jwt()
            .jwt(
                j ->
                    j.subject(SIGNER.toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("acr", acr)
                        .claim("auth_time", Instant.now().getEpochSecond())));
  }

  private String create(UUID ref, String key) throws Exception {
    return mvc.perform(
            as(post("/api/v1/esign/requests"), "aal2")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"purpose\":\"BIEN_BAN\",\"sourceModule\":\"dossier\",\"sourceRefId\":\""
                        + ref
                        + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CHO_XAC_NHAN"))
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  private long outbox(String location) {
    UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    return jdbc.sql("SELECT count(*) FROM esign.outbox_event WHERE topic = ? AND aggregate_id = ?")
        .param("esign.signature.completed.v1")
        .param(id)
        .query(Long.class)
        .single();
  }

  @Test
  void requestGoesFromWaitingToSignedWithExactlyOneEvent() throws Exception {
    UUID ref = source(2);
    String key = UUID.randomUUID().toString();
    String location = create(ref, key);
    assertThat(location).startsWith("/api/v1/esign/requests/");
    assertThat(create(ref, key)).isEqualTo(location);

    worker.poll();
    worker.poll();

    mvc.perform(as(get(location), "aal1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DA_KY"))
        .andExpect(jsonPath("$.isBatch").value(true))
        .andExpect(jsonPath("$.itemCount").value(2))
        .andExpect(jsonPath("$.signedCount").value(2))
        .andExpect(jsonPath("$.signatureFormat").value("PADES_LTA"))
        .andExpect(jsonPath("$.certificate.subjectDn").value("CN=Người ký thử nghiệm"));
    assertThat(outbox(location)).isEqualTo(1);

    mvc.perform(as(get("/api/v1/esign/requests").param("status", "DA_KY"), "aal1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].status").value("DA_KY"));
    mvc.perform(as(get("/api/v1/esign/certificates/mine"), "aal1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
    Long stored =
        jdbc.sql("SELECT count(*) FROM esign.sign_item WHERE signature_value IS NOT NULL")
            .query(Long.class)
            .single();
    assertThat(stored).isGreaterThanOrEqualTo(2);
  }

  @Test
  void unconfirmedRequestExpires() throws Exception {
    fake.nextOutcome(FakeSignatureProvider.Outcome.NEVER_CONFIRM);
    String location = create(source(1), UUID.randomUUID().toString());
    worker.poll();
    Thread.sleep(2100);
    worker.poll();
    mvc.perform(as(get(location), "aal1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HET_HAN"))
        .andExpect(header().doesNotExist("Retry-After"));
    assertThat(outbox(location)).isZero();
  }

  @Test
  void createWithoutRecentStrongAuthenticationIsRefused() throws Exception {
    mvc.perform(
            as(post("/api/v1/esign/requests"), "aal1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"purpose\":\"BIEN_BAN\",\"sourceModule\":\"dossier\",\"sourceRefId\":\""
                        + source(1)
                        + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void otherPersonsRequestIsNotFoundAndUnknownSourceIs404() throws Exception {
    String location = create(source(1), UUID.randomUUID().toString());
    mvc.perform(
            get(location)
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(UUID.randomUUID().toString())
                                    .claim("tenant_id", TENANT.toString()))))
        .andExpect(status().isNotFound());
    mvc.perform(
            as(post("/api/v1/esign/requests"), "aal2")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"purpose\":\"BIEN_BAN\",\"sourceModule\":\"dossier\",\"sourceRefId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void fakeProviderRefusesProductionProfile() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    assertThatThrownBy(
            () -> EsignConfiguration.FakeProviderConfiguration.requireNotProduction(prod))
        .isInstanceOf(IllegalStateException.class);
    EsignConfiguration.FakeProviderConfiguration.requireNotProduction(new MockEnvironment());
  }
}
