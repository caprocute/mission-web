package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.esign.application.sign.EsignService;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.application.sign.SourceDocumentPort;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;
import vn.viettel.okrkpi.esign.infrastructure.sign.PendingRequestScanner;
import vn.viettel.okrkpi.esign.infrastructure.sign.SignPollingWorker;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * MySign qua máy chủ CSC giả lập (DV-10-02) trên PostgreSQL và kho S3 giả lập: API trả 202 không
 * chờ MySign; worker đi đủ năm trạng thái {@code CHO_XAC_NHAN}, {@code DA_KY}, {@code TU_CHOI},
 * {@code HET_HAN}, {@code LOI}; PDF đã ký có chữ ký và dấu thời gian hợp lệ; lỗi 5xx lặp làm ngắt
 * mạch và API trả 422 {@code ESIGN-422-PROVIDER-UNAVAILABLE}.
 */
@SpringBootTest(
    properties = {
      "okrkpi.esign.mysign.enabled=true",
      "okrkpi.esign.provider-ids.MYSIGN=mysign",
      "okrkpi.esign.confirm-window=30s",
      "okrkpi.esign.worker.enabled=false",
      "okrkpi.esign.mysign.storage.bucket=dossier",
      "okrkpi.esign.mysign.storage.access-key=kiem-thu",
      "okrkpi.esign.mysign.storage.secret-key=kiem-thu",
      "resilience4j.circuitbreaker.instances.esign-mysign.minimumNumberOfCalls=4",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
class MySignFlowIT {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000f101");
  private static final String BUCKET = "dossier";
  private static final CscMockServer CSC = start();
  private static final Map<UUID, SourceDocumentPort.SourceDocuments> SOURCES =
      new ConcurrentHashMap<>();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_esign")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> S3 =
      new GenericContainer<>("adobe/s3mock:4.2.0")
          .withExposedPorts(9090)
          .waitingFor(Wait.forListeningPort());

  private static CscMockServer start() {
    try {
      return new CscMockServer();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("okrkpi.esign.mysign.base-url", () -> CSC.baseUrl() + "/csc/v2");
    registry.add("okrkpi.esign.mysign.token-url", () -> CSC.baseUrl() + "/oauth2/token");
    registry.add("okrkpi.esign.mysign.client-id", () -> CscMockServer.CLIENT_ID);
    registry.add("okrkpi.esign.mysign.client-secret", () -> CscMockServer.CLIENT_SECRET);
    registry.add("okrkpi.esign.mysign.tsa-url", () -> CSC.baseUrl() + "/tsa");
    registry.add(
        "okrkpi.esign.mysign.storage.endpoint",
        () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(9090));
  }

  @AfterAll
  static void stopCsc() {
    CSC.close();
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
  @Autowired private S3Client s3;
  @Autowired private CircuitBreakerRegistry breakers;

  private SignPollingWorker worker;

  @BeforeEach
  void setUp() {
    worker = new SignPollingWorker(new PendingRequestScanner(jdbc), service, 100);
    if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
      s3.createBucket(b -> b.bucket(BUCKET));
    }
    CSC.delay(Duration.ZERO);
    CSC.confirmAfter(Duration.ofSeconds(1));
  }

  @AfterEach
  void closeCircuit() {
    breakers.circuitBreaker(MySignConfiguration.INSTANCE).reset();
  }

  /** Hồ sơ có {@code docs} tệp PDF thật trên kho S3, người ký {@code signer}. */
  private UUID source(UUID signer, int docs) throws Exception {
    UUID ref = UUID.randomUUID();
    List<SourceDocumentPort.Document> documents = new ArrayList<>();
    for (int i = 0; i < docs; i++) {
      byte[] pdf = SignedPdfs.sample("Ho so " + ref + " tep " + i);
      String key = "ho-so/" + ref + "/" + i + ".pdf";
      s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(pdf));
      documents.add(
          new SourceDocumentPort.Document(
              "PDF_A", key, Hashes.sha256(pdf), SignatureFormat.PADES_LTA));
    }
    SOURCES.put(ref, new SourceDocumentPort.SourceDocuments(true, Set.of(signer), documents));
    return ref;
  }

  private static MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder r, UUID signer, String acr) {
    return r.with(
        jwt()
            .jwt(
                j ->
                    j.subject(signer.toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("acr", acr)
                        .claim("auth_time", Instant.now().getEpochSecond())));
  }

  private ResultActions create(UUID signer, UUID ref) throws Exception {
    return mvc.perform(
        as(post("/api/v1/esign/requests"), signer, "aal2")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"purpose\":\"BIEN_BAN\",\"sourceModule\":\"dossier\",\"sourceRefId\":\""
                    + ref
                    + "\"}"));
  }

  private UUID created(UUID signer, UUID ref) throws Exception {
    String location =
        create(signer, ref)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("CHO_XAC_NHAN"))
            .andReturn()
            .getResponse()
            .getHeader("Location");
    return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
  }

  private String stateOf(UUID id) {
    return jdbc.sql("SELECT status FROM esign.sign_request WHERE id = ?")
        .param(id)
        .query(String.class)
        .single();
  }

  /** Chạy worker tới khi yêu cầu rời {@code CHO_XAC_NHAN} hoặc hết lượt. */
  private String settle(UUID id) throws InterruptedException {
    for (int i = 0; i < 40 && "CHO_XAC_NHAN".equals(stateOf(id)); i++) {
      worker.poll();
      Thread.sleep(250);
    }
    return stateOf(id);
  }

  private long events(UUID id) {
    return jdbc.sql("SELECT count(*) FROM esign.outbox_event WHERE topic = ? AND aggregate_id = ?")
        .param("esign.signature.completed.v1")
        .param(id)
        .query(Long.class)
        .single();
  }

  @Test
  void confirmedOnPhoneProducesValidTimestampedPadesAndOneEvent() throws Exception {
    UUID signer = UUID.randomUUID();
    UUID ref = source(signer, 2);

    // API không gọi MySign: MySign chậm 3 giây mà API vẫn trả 202 trong 200 ms.
    CSC.delay(Duration.ofSeconds(3));
    int callsBefore = CSC.cscCalls();
    long started = System.nanoTime();
    UUID id = created(signer, ref);
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;
    assertThat(elapsedMs).isLessThanOrEqualTo(200);
    assertThat(CSC.cscCalls()).isEqualTo(callsBefore);
    CSC.delay(Duration.ZERO);

    assertThat(settle(id)).isEqualTo("DA_KY");
    mvc.perform(as(get("/api/v1/esign/requests/" + id), signer, "aal1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DA_KY"))
        .andExpect(jsonPath("$.signedCount").value(2))
        .andExpect(
            jsonPath("$.certificate.subjectDn")
                .value(org.hamcrest.Matchers.containsString("Nguyễn Văn Thử")));
    assertThat(events(id)).isEqualTo(1);

    List<Map<String, Object>> items =
        jdbc.sql(
                "SELECT document_ref, signed_object_key FROM esign.sign_item"
                    + " WHERE sign_request_id = ? ORDER BY document_ref")
            .param(id)
            .query()
            .listOfRows();
    assertThat(items).hasSize(2);
    for (Map<String, Object> item : items) {
      String signedKey = (String) item.get("signed_object_key");
      assertThat(signedKey).startsWith("ky-so/" + id + "/");
      byte[] original =
          s3.getObjectAsBytes(b -> b.bucket(BUCKET).key((String) item.get("document_ref")))
              .asByteArray();
      byte[] signed = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(signedKey)).asByteArray();
      SignedPdfs.assertValid(original, signed, CSC.signerCert, CSC.tsaCert);
    }
    assertThat(
            jdbc.sql(
                    "SELECT DISTINCT c.last_ocsp_status FROM esign.certificate_ref c JOIN esign.sign_item i"
                        + " ON i.certificate_ref_id = c.id WHERE i.sign_request_id = ?")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo("GOOD");
  }

  @Test
  void rejectedExpiredAndRefusedSessionsEndWithoutEvent() throws Exception {
    Map<CscMockServer.Scenario, String> expected =
        Map.of(
            CscMockServer.Scenario.REJECT, "TU_CHOI",
            CscMockServer.Scenario.EXPIRE, "HET_HAN",
            CscMockServer.Scenario.ERROR, "LOI");
    for (var e : expected.entrySet()) {
      UUID signer = UUID.randomUUID();
      CSC.scenario(signer, e.getKey());
      UUID id = created(signer, source(signer, 1));
      assertThat(settle(id)).as(e.getKey().name()).isEqualTo(e.getValue());
      assertThat(events(id)).isZero();
    }
  }

  @Test
  void repeated5xxOpensCircuitAndApiAnswers422() throws Exception {
    UUID signer = UUID.randomUUID();
    CSC.scenario(signer, CscMockServer.Scenario.FAIL_5XX);
    UUID id = created(signer, source(signer, 1));
    for (int i = 0; i < 6; i++) {
      worker.poll();
    }
    assertThat(stateOf(id)).isEqualTo("CHO_XAC_NHAN");
    UUID other = UUID.randomUUID();
    create(other, source(other, 1))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ESIGN-422-PROVIDER-UNAVAILABLE"));
  }
}
