package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
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
import vn.viettel.okrkpi.esign.infrastructure.sign.BatchItemScanner;
import vn.viettel.okrkpi.esign.infrastructure.sign.PendingRequestScanner;
import vn.viettel.okrkpi.esign.infrastructure.sign.SignBatchWorker;
import vn.viettel.okrkpi.esign.infrastructure.sign.SignPollingWorker;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * Ký lô một lần xác nhận (DV-10-06, ADR-31) qua MySign giả lập: người quyết định xác nhận một lần
 * cho 1.000 phiếu, hai worker ký lô chạy song song ký từng phiếu (mỗi lần 1–3 giây, lỗi ngẫu nhiên
 * 10%), thử lại không ký trùng; kết thúc đúng 1.000 chữ ký PAdES hợp lệ và 1.000 EVT-18.
 */
@SpringBootTest(
    properties = {
      "okrkpi.esign.mysign.enabled=true",
      "okrkpi.esign.provider-ids.MYSIGN=mysign",
      "okrkpi.esign.confirm-window=60s",
      "okrkpi.esign.worker.enabled=false",
      "okrkpi.esign.batch-worker.enabled=false",
      "okrkpi.esign.batch.backoff=200ms",
      "okrkpi.esign.batch.max-backoff=1s",
      "okrkpi.esign.batch.max-attempts=8",
      "okrkpi.esign.mysign.storage.bucket=dossier",
      "okrkpi.esign.mysign.storage.access-key=kiem-thu",
      "okrkpi.esign.mysign.storage.secret-key=kiem-thu",
      "resilience4j.circuitbreaker.instances.esign-mysign.failureRateThreshold=90",
      "resilience4j.bulkhead.instances.esign-mysign.maxConcurrentCalls=100",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
class BatchSigningFlowIT {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000b106");
  private static final String BUCKET = "dossier";
  private static final int LOT = 1_000;
  private static final int PARALLELISM = 24;
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
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "80");
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
  @Autowired private ObjectMapper json;

  private SignPollingWorker poller;

  @BeforeEach
  void setUp() {
    poller = new SignPollingWorker(new PendingRequestScanner(jdbc), service, 100);
    if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
      s3.createBucket(b -> b.bucket(BUCKET));
    }
    CSC.delay(Duration.ZERO);
    CSC.confirmAfter(Duration.ofMillis(500));
    CSC.batchSigning(Duration.ZERO, Duration.ZERO, 0);
  }

  /** Quyết định hàng loạt {@code docs} phiếu; {@code missing} phiếu đầu không có tệp trong kho. */
  private UUID decision(UUID decider, int docs, int missing) throws Exception {
    UUID ref = UUID.randomUUID();
    List<SourceDocumentPort.Document> documents = new ArrayList<>();
    for (int i = 0; i < docs; i++) {
      byte[] pdf = SignedPdfs.sample("Quyet dinh " + ref + " phieu " + i);
      String key = "quyet-dinh/" + ref + "/" + i + ".pdf";
      if (i >= missing) {
        s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(pdf));
      }
      documents.add(
          new SourceDocumentPort.Document(
              "QUYET_DINH_XEP_LOAI", key, Hashes.sha256(pdf), SignatureFormat.PADES_LTA));
    }
    SOURCES.put(
        ref, new SourceDocumentPort.SourceDocuments(true, Set.of(decider), documents, true));
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
                "{\"purpose\":\"QUYET_DINH\",\"sourceModule\":\"appraisal\",\"sourceRefId\":\""
                    + ref
                    + "\"}"));
  }

  private UUID created(UUID signer, UUID ref, int items) throws Exception {
    String location =
        create(signer, ref)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.isBatch").value(true))
            .andExpect(jsonPath("$.itemCount").value(items))
            .andExpect(jsonPath("$.signedCount").value(0))
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

  /** Worker thăm dò chạy tới khi người ký xác nhận lô; trả kiểu xác nhận đã ghi. */
  private String authorize(UUID id) throws InterruptedException {
    for (int i = 0; i < 60; i++) {
      String auth =
          jdbc.sql(
                  "SELECT batch_authorization FROM esign.sign_request"
                      + " WHERE id = ? AND batch_authorized_at IS NOT NULL")
              .param(id)
              .query(String.class)
              .optional()
              .orElse(null);
      if (auth != null || !"CHO_XAC_NHAN".equals(stateOf(id))) {
        return auth;
      }
      poller.poll();
      Thread.sleep(200);
    }
    return null;
  }

  private JsonNode view(UUID signer, UUID id) throws Exception {
    return json.readTree(
        mvc.perform(as(get("/api/v1/esign/requests/" + id), signer, "aal1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  /** Hai worker ký lô chạy song song tới khi lô kết thúc hoặc hết thời gian. */
  private void runWorkers(UUID id, Duration limit, Runnable whileRunning) throws Exception {
    List<SignBatchWorker> workers = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      workers.add(
          new SignBatchWorker(
              new BatchItemScanner(jdbc), service, 200, PARALLELISM, new SimpleMeterRegistry()));
    }
    AtomicBoolean stop = new AtomicBoolean();
    ExecutorService pool = Executors.newFixedThreadPool(workers.size());
    List<Future<?>> loops = new ArrayList<>();
    for (SignBatchWorker w : workers) {
      loops.add(
          pool.submit(
              () -> {
                while (!stop.get()) {
                  if (w.poll() == 0) {
                    try {
                      Thread.sleep(100);
                    } catch (InterruptedException e) {
                      return;
                    }
                  }
                }
              }));
    }
    Instant deadline = Instant.now().plus(limit);
    boolean observed = false;
    try {
      while (Instant.now().isBefore(deadline) && "CHO_XAC_NHAN".equals(stateOf(id))) {
        if (!observed && whileRunning != null && signedItems(id) > 0) {
          whileRunning.run();
          observed = true;
        }
        Thread.sleep(200);
      }
    } finally {
      stop.set(true);
      for (Future<?> f : loops) {
        f.get();
      }
      pool.shutdown();
      workers.forEach(SignBatchWorker::destroy);
    }
  }

  private long signedItems(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM esign.sign_item WHERE sign_request_id = ? AND item_status = 'DA_KY'")
        .param(id)
        .query(Long.class)
        .single();
  }

  private List<Map<String, Object>> itemEvents(UUID id) {
    return jdbc.sql(
            """
            SELECT o.aggregate_id FROM esign.outbox_event o
              JOIN esign.sign_item i ON i.id = o.aggregate_id
             WHERE o.topic = 'esign.signature.completed.v1' AND o.aggregate_type = 'SIGN_ITEM'
               AND i.sign_request_id = ?
            """)
        .param(id)
        .query()
        .listOfRows();
  }

  @Test
  void oneConfirmationSignsThousandDecisionsOnceEachWithRandomFailures() throws Exception {
    UUID decider = UUID.randomUUID();
    UUID ref = decision(decider, LOT, 0);
    UUID id = created(decider, ref, LOT);
    int sadBefore = CSC.sadSignatures();

    assertThat(authorize(id)).isEqualTo("SAD");
    CSC.batchSigning(Duration.ofSeconds(1), Duration.ofSeconds(3), 0.10);

    long started = System.nanoTime();
    List<long[]> progress = new ArrayList<>();
    runWorkers(
        id,
        Duration.ofMinutes(8),
        () -> {
          try {
            JsonNode mid = view(decider, id);
            progress.add(
                new long[] {mid.get("itemCount").asLong(), mid.get("signedCount").asLong()});
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    double seconds = (System.nanoTime() - started) / 1e9;

    assertThat(stateOf(id)).isEqualTo("DA_KY");
    // getEsignRequest trả tiến độ đúng trong lúc lô đang chạy.
    assertThat(progress).hasSize(1);
    assertThat(progress.get(0)[0]).isEqualTo(LOT);
    assertThat(progress.get(0)[1]).isBetween(1L, (long) LOT);
    JsonNode done = view(decider, id);
    assertThat(done.get("signedCount").asInt()).isEqualTo(LOT);
    assertThat(done.get("itemCount").asInt()).isEqualTo(LOT);
    assertThat(done.hasNonNull("problem")).isFalse();

    // Đúng 1.000 chữ ký: nhà cung cấp không ký trùng, mỗi khoá (yêu cầu, băm tài liệu) một chữ ký.
    assertThat(CSC.sadSignatures() - sadBefore).isEqualTo(LOT);
    assertThat(
            jdbc.sql(
                    "SELECT count(DISTINCT document_sha256) FROM esign.sign_item"
                        + " WHERE sign_request_id = ? AND signature_value IS NOT NULL")
                .param(id)
                .query(Long.class)
                .single())
        .isEqualTo(LOT);
    long retried =
        jdbc.sql("SELECT count(*) FROM esign.sign_item WHERE sign_request_id = ? AND attempts > 1")
            .param(id)
            .query(Long.class)
            .single();
    assertThat(retried).as("lỗi ngẫu nhiên 10% phải làm một số phiếu được thử lại").isPositive();

    // Đúng 1.000 EVT-18, mỗi phiếu một sự kiện.
    List<Map<String, Object>> events = itemEvents(id);
    assertThat(events).hasSize(LOT);
    assertThat(events.stream().map(e -> e.get("aggregate_id")).distinct().count()).isEqualTo(LOT);

    // 100% chữ ký PAdES hợp lệ, có dấu thời gian.
    List<Map<String, Object>> items =
        jdbc.sql(
                "SELECT document_ref, signed_object_key FROM esign.sign_item"
                    + " WHERE sign_request_id = ? ORDER BY document_ref")
            .param(id)
            .query()
            .listOfRows();
    int checked = 0;
    for (Map<String, Object> item : items) {
      byte[] original =
          s3.getObjectAsBytes(b -> b.bucket(BUCKET).key((String) item.get("document_ref")))
              .asByteArray();
      byte[] signed =
          s3.getObjectAsBytes(b -> b.bucket(BUCKET).key((String) item.get("signed_object_key")))
              .asByteArray();
      if (checked < 5) {
        SignedPdfs.assertValid(original, signed, CSC.signerCert, CSC.tsaCert);
        SignedPdfs.opensslVerify(signed, CSC.caCert);
      } else {
        SignedPdfs.assertCms(original, signed, CSC.signerCert, CSC.tsaCert);
      }
      checked++;
    }
    assertThat(checked).isEqualTo(LOT);

    // Số đo cục bộ cho báo cáo (không phải phép thử tải, YC-PT-19).
    System.out.printf(
        "KY-LO: %d phieu trong %.1f s, %.2f chu ky/s, %d phieu thu lai, 2 worker x %d luong%n",
        LOT, seconds, LOT / seconds, retried, PARALLELISM);
  }

  @Test
  void permanentItemErrorDoesNotBreakTheLot() throws Exception {
    UUID decider = UUID.randomUUID();
    UUID id = created(decider, decision(decider, 3, 1), 3);
    assertThat(authorize(id)).isEqualTo("SAD");
    runWorkers(id, Duration.ofMinutes(1), null);

    assertThat(stateOf(id)).isEqualTo("DA_KY");
    JsonNode done = view(decider, id);
    assertThat(done.get("signedCount").asInt()).isEqualTo(2);
    assertThat(done.get("problem").get("errors")).hasSize(1);
    assertThat(itemEvents(id)).hasSize(2);
    assertThat(
            jdbc.sql(
                    "SELECT item_status FROM esign.sign_item WHERE sign_request_id = ?"
                        + " AND signature_value IS NULL")
                .param(id)
                .query(String.class)
                .list())
        .containsExactly("LOI");
  }

  @Test
  void rejectedConfirmationEndsLotWithoutSigning() throws Exception {
    UUID decider = UUID.randomUUID();
    CSC.scenario(decider, CscMockServer.Scenario.REJECT);
    UUID id = created(decider, decision(decider, 3, 0), 3);
    assertThat(authorize(id)).isNull();
    assertThat(stateOf(id)).isEqualTo("TU_CHOI");
    assertThat(signedItems(id)).isZero();
  }

  @Test
  void lotLargerThanLimitIsRefused() throws Exception {
    UUID decider = UUID.randomUUID();
    UUID ref = UUID.randomUUID();
    List<SourceDocumentPort.Document> docs = new ArrayList<>();
    for (int i = 0; i <= LOT; i++) {
      byte[] sha = Hashes.sha256(("phieu-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      docs.add(
          new SourceDocumentPort.Document(
              "QUYET_DINH_XEP_LOAI", "k/" + i, sha, SignatureFormat.PADES_LTA));
    }
    SOURCES.put(ref, new SourceDocumentPort.SourceDocuments(true, Set.of(decider), docs, true));
    create(decider, ref)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("COMMON-422-VALIDATION"));
  }
}
