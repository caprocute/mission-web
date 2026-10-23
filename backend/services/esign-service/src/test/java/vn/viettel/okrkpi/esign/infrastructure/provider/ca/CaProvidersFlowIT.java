package vn.viettel.okrkpi.esign.infrastructure.provider.ca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.CscMockServer;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.MySignProperties;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.S3ObjectStorage;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.SignedPdfs;
import vn.viettel.okrkpi.esign.infrastructure.provider.trust.CertificateTrust;
import vn.viettel.okrkpi.esign.infrastructure.sign.PendingRequestScanner;
import vn.viettel.okrkpi.esign.infrastructure.sign.SignPollingWorker;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * Viettel-CA và CA chuyên dùng Chính phủ (DV-10-04) qua hai máy chủ CSC giả lập có chuỗi gốc riêng
 * (YC-PT-13): nhà cung cấp mặc định của tenant đổi bằng cấu hình; PDF ký xong được {@code openssl}
 * xác nhận theo đúng chứng thư gốc đã nạp; từ chối, hết hạn đi đúng trạng thái; chứng thư bị thu
 * hồi (OCSP giả lập) bị chặn bằng 422 {@code ESIGN-422-CERTIFICATE-INVALID}; danh sách chứng thư
 * gom từ mọi nhà cung cấp được bật.
 */
@SpringBootTest(
    properties = {
      "okrkpi.esign.viettel-ca.enabled=true",
      "okrkpi.esign.gov-ca.enabled=true",
      "okrkpi.esign.confirm-window=30s",
      "okrkpi.esign.worker.enabled=false",
      "okrkpi.esign.tenants.[0192f1a2-0000-7000-8000-00000000ca01].enabled=VIETTEL_CA,CA_CHINH_PHU",
      "okrkpi.esign.tenants.[0192f1a2-0000-7000-8000-00000000ca01].default-provider=VIETTEL_CA",
      "okrkpi.esign.tenants.[0192f1a2-0000-7000-8000-00000000ca02].enabled=MYSIGN",
      "okrkpi.esign.tenants.[0192f1a2-0000-7000-8000-00000000ca02].default-provider=MYSIGN",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
class CaProvidersFlowIT {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000ca01");
  private static final UUID OTHER_TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000ca02");
  private static final String BUCKET = "dossier";
  private static final CscMockServer VIETTEL = start("CN=Viettel-CA thử nghiệm,O=Mission,C=VN");
  private static final CscMockServer GOV = start("CN=CA Chính phủ thử nghiệm,O=Mission,C=VN");
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

  private static CscMockServer start(String dn) {
    try {
      CscMockServer csc = new CscMockServer(dn);
      csc.certificatePerUser(true);
      return csc;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String s3Endpoint() {
    return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
  }

  static String pem(X509Certificate cert) {
    try {
      return "-----BEGIN CERTIFICATE-----\n"
          + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
          + "\n-----END CERTIFICATE-----\n";
    } catch (java.security.cert.CertificateEncodingException e) {
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
    for (var e : Map.of("viettel-ca", VIETTEL, "gov-ca", GOV).entrySet()) {
      String p = "okrkpi.esign." + e.getKey() + ".";
      CscMockServer csc = e.getValue();
      registry.add(p + "base-url", () -> csc.baseUrl() + "/csc/v2");
      registry.add(p + "token-url", () -> csc.baseUrl() + "/oauth2/token");
      registry.add(p + "client-id", () -> CscMockServer.CLIENT_ID);
      registry.add(p + "client-secret", () -> CscMockServer.CLIENT_SECRET);
      registry.add(p + "tsa-url", () -> csc.baseUrl() + "/tsa");
      registry.add(p + "ocsp-url", () -> csc.baseUrl() + "/ocsp");
      registry.add(p + "crl-url", () -> csc.baseUrl() + "/crl");
      registry.add(p + "trust-anchors[0]", () -> pem(csc.caCert));
      registry.add(p + "storage.endpoint", CaProvidersFlowIT::s3Endpoint);
      registry.add(p + "storage.bucket", () -> BUCKET);
      registry.add(p + "storage.access-key", () -> "kiem-thu");
      registry.add(p + "storage.secret-key", () -> "kiem-thu");
    }
  }

  @AfterAll
  static void stopCsc() {
    VIETTEL.close();
    GOV.close();
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
  @Autowired private ResilientClientFactory clients;

  private S3Client s3;
  private SignPollingWorker worker;

  @BeforeEach
  void setUp() {
    s3 =
        S3ObjectStorage.client(
            new MySignProperties.Storage(
                s3Endpoint(), "us-east-1", BUCKET, "kiem-thu", "kiem-thu", null));
    worker = new SignPollingWorker(new PendingRequestScanner(jdbc), service, 100);
    if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
      s3.createBucket(b -> b.bucket(BUCKET));
    }
    for (CscMockServer csc : List.of(VIETTEL, GOV)) {
      csc.delay(Duration.ZERO);
      csc.confirmAfter(Duration.ofMillis(500));
    }
  }

  @AfterEach
  void restore() {
    VIETTEL.revokeSigner(false);
    GOV.revokeSigner(false);
    s3.close();
  }

  private UUID source(UUID signer) throws Exception {
    UUID ref = UUID.randomUUID();
    byte[] pdf = SignedPdfs.sample("Ho so " + ref);
    String key = "ho-so/" + ref + "/0.pdf";
    s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(pdf));
    SOURCES.put(
        ref,
        new SourceDocumentPort.SourceDocuments(
            true,
            Set.of(signer),
            List.of(
                new SourceDocumentPort.Document(
                    "PDF_A", key, Hashes.sha256(pdf), SignatureFormat.PADES_LTA))));
    return ref;
  }

  private static MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder r, UUID tenant, UUID signer, String acr) {
    return r.with(
        jwt()
            .jwt(
                j ->
                    j.subject(signer.toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("acr", acr)
                        .claim("auth_time", Instant.now().getEpochSecond())));
  }

  private ResultActions create(UUID tenant, UUID signer, UUID ref, String provider)
      throws Exception {
    String extra = provider == null ? "" : ",\"provider\":\"" + provider + "\"";
    return mvc.perform(
        as(post("/api/v1/esign/requests"), tenant, signer, "aal2")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"purpose\":\"BIEN_BAN\",\"sourceModule\":\"dossier\",\"sourceRefId\":\""
                    + ref
                    + "\""
                    + extra
                    + "}"));
  }

  private UUID created(UUID signer, String provider, String expectedProvider) throws Exception {
    String location =
        create(TENANT, signer, source(signer), provider)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.provider").value(expectedProvider))
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

  private String settle(UUID id) throws InterruptedException {
    for (int i = 0; i < 40 && "CHO_XAC_NHAN".equals(stateOf(id)); i++) {
      worker.poll();
      Thread.sleep(200);
    }
    return stateOf(id);
  }

  private byte[] signedPdf(UUID id) {
    String key =
        jdbc.sql("SELECT signed_object_key FROM esign.sign_item WHERE sign_request_id = ?")
            .param(id)
            .query(String.class)
            .single();
    return s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray();
  }

  @Test
  void defaultProviderOfTenantSignsAndOpensslVerifiesAgainstLoadedRoot() throws Exception {
    UUID signer = UUID.randomUUID();
    UUID id = created(signer, null, "VIETTEL_CA");
    assertThat(settle(id)).isEqualTo("DA_KY");
    byte[] signed = signedPdf(id);
    SignedPdfs.opensslVerify(signed, VIETTEL.caCert);
    // Chuỗi gốc khác không xác nhận được chữ ký của Viettel-CA.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> SignedPdfs.opensslVerify(signed, GOV.caCert))
        .isInstanceOf(AssertionError.class);

    UUID other = UUID.randomUUID();
    UUID govId = created(other, "CA_CHINH_PHU", "CA_CHINH_PHU");
    assertThat(settle(govId)).isEqualTo("DA_KY");
    SignedPdfs.opensslVerify(signedPdf(govId), GOV.caCert);
  }

  @Test
  void rejectedAndExpiredSessionsEndInTheirStates() throws Exception {
    Map<CscMockServer.Scenario, String> expected =
        Map.of(CscMockServer.Scenario.REJECT, "TU_CHOI", CscMockServer.Scenario.EXPIRE, "HET_HAN");
    for (var e : expected.entrySet()) {
      for (String provider : List.of("VIETTEL_CA", "CA_CHINH_PHU")) {
        UUID signer = UUID.randomUUID();
        (provider.equals("VIETTEL_CA") ? VIETTEL : GOV).scenario(signer, e.getKey());
        UUID id = created(signer, provider, provider);
        assertThat(settle(id)).as(provider + " " + e.getKey()).isEqualTo(e.getValue());
      }
    }
  }

  @Test
  void revokedCertificateIsRefusedWith422AndNewSessionFails() throws Exception {
    UUID signer = UUID.randomUUID();
    UUID first = created(signer, null, "VIETTEL_CA");
    assertThat(settle(first)).isEqualTo("DA_KY");

    VIETTEL.revokeSigner(true);
    create(TENANT, signer, source(signer), null)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ESIGN-422-CERTIFICATE-INVALID"));
    // Lỗi 422 hoàn tác giao dịch của yêu cầu; danh sách chứng thư hỏi lại nhà cung cấp và lưu kết
    // quả.
    mvc.perform(as(get("/api/v1/esign/certificates/mine"), TENANT, signer, "aal1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));
    assertThat(
            jdbc.sql(
                    "SELECT last_ocsp_status FROM esign.certificate_ref WHERE person_id = ?"
                        + " AND issuer_dn LIKE '%Viettel-CA%'")
                .param(signer)
                .query(String.class)
                .list())
        .containsExactly("REVOKED");

    // Người chưa có chứng thư đã lưu: nhà cung cấp kiểm OCSP lúc mở phiên và không ký.
    UUID fresh = UUID.randomUUID();
    UUID id = created(fresh, null, "VIETTEL_CA");
    assertThat(settle(id)).isEqualTo("LOI");
  }

  @Test
  void providerNotEnabledForTenantIsUnavailable() throws Exception {
    UUID signer = UUID.randomUUID();
    create(OTHER_TENANT, signer, source(signer), "VIETTEL_CA")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ESIGN-422-PROVIDER-UNAVAILABLE"));
  }

  @Test
  void certificateListGathersEveryEnabledProvider() throws Exception {
    UUID signer = UUID.randomUUID();
    mvc.perform(as(get("/api/v1/esign/certificates/mine"), TENANT, signer, "aal1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(
            jsonPath("$.items[*].issuerDn")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        org.hamcrest.Matchers.containsString("Viettel-CA"),
                        org.hamcrest.Matchers.containsString("Chính phủ"))))
        .andExpect(
            jsonPath("$.items[*].lastOcspStatus")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("GOOD"))));
  }

  @Test
  void trustRejectsForeignChainAndFallsBackToCrl() {
    CertificateTrust viettelOnly =
        new CertificateTrust(
            "viettel-ca",
            List.of(VIETTEL.caCert),
            null,
            clients.create("esign-crl-kiem-thu", VIETTEL.baseUrl() + "/crl"),
            Clock.systemUTC());
    assertThat(viettelOnly.trusted(List.of(GOV.signerCert))).isFalse();
    assertThat(viettelOnly.trusted(List.of(VIETTEL.signerCert))).isTrue();
    assertThat(viettelOnly.check(List.of(VIETTEL.signerCert)).status())
        .isEqualTo(CertificateTrust.Status.GOOD);
    VIETTEL.revokeSigner(true);
    assertThat(viettelOnly.check(List.of(VIETTEL.signerCert)).status())
        .isEqualTo(CertificateTrust.Status.REVOKED);
  }
}
