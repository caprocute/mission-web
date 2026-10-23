package vn.viettel.okrkpi.esign.infrastructure.tsa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Calendar;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.ObjectStorage;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * Job gia hạn dấu thời gian lưu trữ trên PostgreSQL thật (DV-10-03): quét PAdES-LTA của mọi tenant
 * qua {@code esign.fn_tai_lieu_luu_tru}, gia hạn tài liệu có chứng thư TSA sắp hết hạn, bỏ qua tài
 * liệu còn hạn hoặc thiếu tệp, không đụng CAdES.
 */
@SpringBootTest(properties = {"okrkpi.esign.worker.enabled=false"})
@Testcontainers
class ArchiveTimestampRenewalIT {

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
  }

  static TsaMockServer tsa;

  @BeforeAll
  static void start() throws Exception {
    tsa = new TsaMockServer();
  }

  @AfterAll
  static void stop() {
    tsa.close();
  }

  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactions;

  final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  final ObjectStorage storage =
      new ObjectStorage() {
        @Override
        public Optional<byte[]> get(String key) {
          return Optional.ofNullable(objects.get(key));
        }

        @Override
        public void put(String key, byte[] content, String contentType) {
          objects.put(key, content);
        }
      };

  UUID request(UUID tenant) {
    UUID id = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO esign.sign_request (id, tenant_id, signer_person_id, purpose, source_module,"
                + " source_ref_id, provider, is_batch, status, expires_at, created_by, updated_by)"
                + " VALUES (?, ?, ?, 'QUYET_DINH', 'appraisal', ?, 'MYSIGN', false, 'DA_KY', now(), ?,"
                + " ?)")
        .params(id, tenant, person, UUID.randomUUID(), person, person)
        .update();
    return id;
  }

  UUID item(UUID tenant, UUID request, String format, String key) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO esign.sign_item (id, tenant_id, sign_request_id, document_kind,"
                + " document_ref, document_sha256, signature_format, signed_object_key, signed_at)"
                + " VALUES (?, ?, ?, 'PDF_A', ?, ?, ?, ?, now())")
        // Mỗi tài liệu một băm riêng: một yêu cầu không ký trùng tài liệu
        // (uq_sign_item__request_sha256).
        .params(
            id,
            tenant,
            request,
            key,
            Hashes.sha256(key.getBytes(StandardCharsets.UTF_8)),
            format,
            key)
        .update();
    return id;
  }

  @Test
  void giaHanTaiLieuSapHetHanVaBoQuaTaiLieuConHan() throws Exception {
    UUID t1 = UUID.randomUUID();
    UUID t2 = UUID.randomUUID();
    UUID r1 = request(t1);
    UUID r2 = request(t2);
    byte[] blank = ArchiveTimestampsTest.blankPdf();
    objects.put("t1/het-han.pdf", blank);
    objects.put(
        "t2/con-han.pdf",
        ArchiveTimestamps.addDocumentTimestamp(blank, tsa.client(), Calendar.getInstance()));
    UUID expiring = item(t1, r1, "PADES_LTA", "t1/het-han.pdf");
    UUID fresh = item(t2, r2, "PADES_LTA", "t2/con-han.pdf");
    item(t2, r2, "PADES_LTA", "t2/mat-tep.pdf");
    item(t1, r1, "CADES_T", "t1/cades.p7s");
    ArchiveTimestampRenewalJob job =
        new ArchiveTimestampRenewalJob(
            jdbc,
            storage,
            tsa.client(),
            new TransactionTemplate(transactions),
            Clock.systemUTC(),
            Duration.ofDays(30),
            2);
    ArchiveTimestampRenewalJob.Result r = job.run();
    assertThat(r).isEqualTo(new ArchiveTimestampRenewalJob.Result(3, 1, 2, 0));
    String key =
        jdbc.sql("SELECT signed_object_key FROM esign.sign_item WHERE id = ?")
            .param(expiring)
            .query(String.class)
            .single();
    assertThat(key).startsWith("t1/het-han.lta-").endsWith(".pdf");
    assertThat(ArchiveTimestamps.latestTsaExpiry(objects.get(key))).isPresent();
    assertThat(objects).containsKey("t1/het-han.pdf");
    assertThat(
            jdbc.sql("SELECT signed_object_key FROM esign.sign_item WHERE id = ?")
                .param(fresh)
                .query(String.class)
                .single())
        .isEqualTo("t2/con-han.pdf");
  }
}
