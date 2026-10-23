package vn.viettel.okrkpi.esign.infrastructure.provider.bcy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import vn.viettel.okrkpi.esign.infrastructure.sign.PendingRequestScanner;
import vn.viettel.okrkpi.esign.infrastructure.sign.SignPollingWorker;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignedItem;
import vn.viettel.okrkpi.shared.crypto.CryptoProvider;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.crypto.bcy.BcyCryptoProvider;
import vn.viettel.okrkpi.shared.crypto.bcy.GiaLapBcyCryptoLibrary;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * esign-service ở chế độ {@code okrkpi.crypto.provider=bcy} với thư viện Ban Cơ yếu giả lập
 * (DV-10-05, QT-09/QT-11 bản cách ly): yêu cầu ký CAdES đi {@code CHO_XAC_NHAN} → {@code DA_KY} qua
 * nhà cung cấp {@code BAN_CO_YEU}, chữ ký lưu ở {@code esign.sign_item} kiểm lại được bằng thư
 * viện, chỉ một {@link CryptoProvider} (Ban Cơ yếu) và một nhà cung cấp chữ ký được nạp.
 */
@SpringBootTest(
    properties = {
      "okrkpi.crypto.provider=bcy",
      "okrkpi.crypto.bcy.library-class=vn.viettel.okrkpi.shared.crypto.bcy.GiaLapBcyCryptoLibrary",
      "okrkpi.crypto.bcy.params.slot=0",
      "okrkpi.crypto.bcy.params.key-labels=ky-van-ban",
      "okrkpi.crypto.bcy.allowed-algorithms=GIA_LAP_AEAD,GIA_LAP_MAC,GIA_LAP_BAM,GIA_LAP_KY,"
          + "GIA_LAP_DAU_THOI_GIAN,GIA_LAP_NGAU_NHIEN",
      "okrkpi.esign.default-provider=BAN_CO_YEU",
      "okrkpi.esign.worker.enabled=false",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(BcyEsignFlowIT.Fakes.class)
class BcyEsignFlowIT {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000b001");
  private static final UUID SIGNER = UUID.fromString("0192f1a2-0000-7000-8000-00000000b002");
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

  /** Lớp thường nạp qua {@code @Import}, không quét thành phần sang IT khác. */
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
  @Autowired private BcySignatureProvider bcy;
  @Autowired private ApplicationContext context;

  private static UUID source(SignatureFormat format) {
    UUID ref = UUID.randomUUID();
    List<SourceDocumentPort.Document> documents =
        java.util.stream.IntStream.range(0, 2)
            .mapToObj(
                i -> {
                  byte[] sha = new byte[32];
                  sha[0] = (byte) (i + 1);
                  sha[31] = (byte) ref.hashCode();
                  return new SourceDocumentPort.Document(
                      "PDF_A", "dossier/" + ref + "/" + i, sha, format);
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

  private MockHttpServletRequestBuilder create(UUID ref) {
    return as(post("/api/v1/esign/requests"), "aal2")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"purpose\":\"BIEN_BAN\",\"sourceModule\":\"dossier\",\"sourceRefId\":\""
                + ref
                + "\"}");
  }

  @Test
  void onlyBanCoYeuProvidersAreLoaded() {
    assertThat(context.getBean(CryptoProvider.class)).isInstanceOf(BcyCryptoProvider.class);
    assertThat(context.getBeansOfType(JcaAesGcmCryptoProvider.class)).isEmpty();
    assertThat(context.getBeansOfType(SignatureProvider.class).values())
        .extracting(SignatureProvider::id)
        .containsExactly(BcySignatureProvider.ID);
  }

  @Test
  void cadesRequestIsSignedByDeviceAndVerifiable() throws Exception {
    String location =
        mvc.perform(create(source(SignatureFormat.CADES_T)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    SignPollingWorker worker = new SignPollingWorker(new PendingRequestScanner(jdbc), service, 100);
    worker.poll();
    worker.poll();

    mvc.perform(as(get(location), "aal1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DA_KY"))
        .andExpect(jsonPath("$.signedCount").value(2))
        .andExpect(jsonPath("$.signatureFormat").value("CADES_T"))
        .andExpect(jsonPath("$.certificate.subjectDn").value("CN=Khoá ký giả lập ky-van-ban"));

    UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    var rows =
        jdbc.sql(
                """
                SELECT i.document_sha256, i.signature_value, c.cert_der, i.signature_format
                  FROM esign.sign_item i
                  JOIN esign.certificate_ref c ON c.id = i.certificate_ref_id
                 WHERE i.sign_request_id = ?
                """)
            .param(id)
            .query(
                (rs, n) ->
                    new Object[] {rs.getBytes(1), rs.getBytes(2), rs.getBytes(3), rs.getString(4)})
            .list();
    assertThat(rows).hasSize(2);
    for (Object[] row : rows) {
      assertThat(row[3]).isEqualTo("CADES_T");
      SignedItem.Certificate cert =
          new SignedItem.Certificate((byte[]) row[2], "", "", "", null, null, null, null);
      assertThat(bcy.verify((byte[]) row[1], (byte[]) row[0], cert)).isTrue();
      byte[] other = ((byte[]) row[0]).clone();
      other[1] ^= 0x01;
      assertThat(bcy.verify((byte[]) row[1], other, cert)).isFalse();
    }
    assertThat(GiaLapBcyCryptoLibrary.FORMAT).isEqualTo(BcySignatureProvider.LIBRARY_FORMAT);
  }

  @Test
  void padesRequestIsRefusedForBanCoYeu() throws Exception {
    int code =
        mvc.perform(create(source(SignatureFormat.PADES_LTA)))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(code).isBetween(400, 499);
  }
}
