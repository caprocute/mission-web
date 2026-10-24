package vn.viettel.okrkpi.product.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
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
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.product.application.evidence.EvidenceActor;
import vn.viettel.okrkpi.product.application.evidence.EvidenceExternalDocService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceLifecycleService;
import vn.viettel.okrkpi.product.application.evidence.port.AiSuggestionDecisions;
import vn.viettel.okrkpi.product.application.evidence.port.CommentSealer;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments;
import vn.viettel.okrkpi.product.infrastructure.listener.EvidenceObjectListener;
import vn.viettel.okrkpi.product.infrastructure.persistence.EvidenceSubmissionConfiguration;
import vn.viettel.okrkpi.product.infrastructure.persistence.FieldEncryptorCommentSealer;
import vn.viettel.okrkpi.product.infrastructure.persistence.OutboxEvidenceEvents;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.evidence.v1.ObjectScanned;
import vn.vtokr.sys.v1.EvidenceObjectStored;

/**
 * CORE-03-03 trên PostgreSQL thật: mười thao tác của SCR-0701 khớp {@code products.yaml}; bản chiếu
 * đối tượng nhận sự kiện lưu và quét qua inbox (trùng thì bỏ qua); tệp chưa quét sạch, mã băm lệch,
 * tệp trùng với nhiệm vụ khác thiếu giải trình đều chặn nộp; nộp đủ sản phẩm đưa nhiệm vụ sang chờ
 * nghiệm thu; gửi lại cùng {@code Idempotency-Key} không ghi EVT-07 lần hai.
 */
@SpringBootTest(
    classes = {MissionCoreApplication.class, EvidenceSubmissionApiIT.Beans.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "mission.outbox.relay.enabled=false",
      "mission.config.period-opening.enabled=false",
      "mission.product.evidence-object.listener.enabled=false"
    })
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvidenceSubmissionApiIT extends ContractConformanceTest {

  static final int BUCKET = 20281;
  static final UUID ACTOR = UUID.fromString("01927a03-0000-7000-8000-00000000d303");
  static final UUID ASSIGNER = UUID.fromString("01927a03-0000-7000-8000-00000000d304");

  /** Người gọi hiện tại của ngữ cảnh, mức xác thực và thời điểm xác thực (giây epoch). */
  static volatile UUID caller = ACTOR;

  static volatile String callerAcr;
  static volatile Long callerAuthTime;

  static final byte[] KEY = new byte[32];

  /** Lời gọi {@code decideAiSuggestion} tới SVC-14 giả: mã gợi ý, quyết định, bản ghi đích. */
  static final List<String> AI_CALLS = new CopyOnWriteArrayList<>();

  /** SVC-14 giả quá thời hạn (CORE-03-05). */
  static volatile boolean aiDown;

  /** Đơn vị của người gọi và văn bản đã ban hành trên SVC-13 giả (CORE-03-04). */
  static final UUID UNIT = UUID.fromString("01927a03-0000-7000-8000-00000000d3a1");

  static final String GOOD_REF = "47-BC/BTCĐUK";
  static final String BAD_REF = "12-BC/BTCĐUK";

  /** SVC-13 giả không phản hồi; tệp đã chuyển có chữ ký không hợp lệ. */
  static volatile boolean integDown;

  static volatile boolean tampered;

  /** Đối tượng SVC-08 theo khoá chống ghi trùng của lệnh chuyển. */
  static final Map<String, UUID> TRANSFERRED = new ConcurrentHashMap<>();

  /** Người gọi và outbox giả (không cần registry) chỉ của ngữ cảnh này. */
  static class Beans {

    @Bean
    EvidenceExternalDocService.Units fakeUnits() {
      return (tenant, personId, on) -> Optional.of(UNIT);
    }

    @Bean
    IssuedDocuments fakeIssuedDocuments() {
      return new IssuedDocuments() {
        private final List<Doc> docs =
            List.of(
                new Doc(
                    GOOD_REF,
                    GOOD_REF,
                    LocalDate.of(2027, 1, 19),
                    "Báo cáo chuyên đề công tác kiểm tra quý IV",
                    "Trần Thị Bình",
                    true),
                new Doc(
                    BAD_REF,
                    BAD_REF,
                    LocalDate.of(2027, 1, 12),
                    "Báo cáo có chữ ký không hợp lệ",
                    "Nguyễn Văn An",
                    false));

        @Override
        public Page search(TenantId t, UUID orgUnitId, String q, int limit, String cursor) {
          down();
          return new Page(
              docs.stream()
                  .filter(d -> UNIT.equals(orgUnitId))
                  .filter(d -> q == null || d.ref().contains(q) || d.summary().contains(q))
                  .limit(limit)
                  .toList(),
              null);
        }

        @Override
        public Optional<Transferred> transfer(
            TenantId t,
            UUID orgUnitId,
            String ref,
            UUID evidenceId,
            UUID requestedBy,
            String idempotencyKey) {
          down();
          return docs.stream()
              .filter(d -> d.ref().equals(ref) && UNIT.equals(orgUnitId))
              .findFirst()
              .map(
                  d ->
                      new Transferred(
                          TRANSFERRED.computeIfAbsent(idempotencyKey, k -> UUID.randomUUID()),
                          1,
                          sha(91),
                          4096,
                          "application/pdf",
                          d.signatureValid() && !tampered,
                          d));
        }

        private void down() {
          if (integDown) {
            throw new DependencyUnavailableException(
                "integration-gateway",
                DependencyUnavailableException.Reason.TIMEOUT,
                java.time.Duration.ofSeconds(5),
                null);
          }
        }
      };
    }

    @Bean
    AiSuggestionDecisions fakeAiSuggestionDecisions() {
      return (tenant, suggestionId, decision, targetRef, authorization, idempotencyKey) -> {
        AI_CALLS.add(suggestionId + " " + decision + " " + targetRef);
        if (aiDown) {
          throw new DependencyUnavailableException(
              "ai-gateway",
              DependencyUnavailableException.Reason.TIMEOUT,
              java.time.Duration.ofSeconds(5),
              null);
        }
      };
    }

    @Bean
    EvidenceCaller evidenceCaller() {
      return () ->
          Optional.of(
              new EvidenceActor(
                  caller,
                  Set.of(ASSIGNER.equals(caller) ? "manager" : "member"),
                  callerAcr,
                  callerAuthTime));
    }

    /** Mã hoá thật AES-GCM với DEK giả (không cần OpenBao). */
    @Bean
    @Primary
    CommentSealer testCommentSealer() {
      DekService deks = Mockito.mock(DekService.class);
      UUID dekId = UUID.randomUUID();
      Mockito.when(deks.getOrCreateActiveDek(Mockito.any(), Mockito.any()))
          .thenAnswer(
              inv ->
                  new CachedDek(
                      dekId,
                      inv.getArgument(0),
                      inv.getArgument(1),
                      LocalDate.of(2027, 1, 1),
                      DekStatus.ACTIVE,
                      KEY.clone()));
      // GET phiên bản giải mã nhận xét cho người nộp và người nghiệm thu
      Mockito.when(deks.getDekForDecryption(Mockito.any(), Mockito.eq(dekId)))
          .thenAnswer(
              inv ->
                  new CachedDek(
                      dekId,
                      inv.getArgument(0),
                      DekPurpose.NHAN_XET_DANH_GIA,
                      LocalDate.of(2027, 1, 1),
                      DekStatus.ACTIVE,
                      KEY.clone()));
      FieldEncryptor encryptor = new FieldEncryptor(deks, new JcaAesGcmCryptoProvider());
      return new FieldEncryptorCommentSealer(() -> encryptor);
    }

    @Bean
    @Primary
    EvidenceEvents testEvidenceEvents(
        @Qualifier("productDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxEvidenceEvents(
          new OutboxWriter(
              new JdbcTemplate(ds),
              "product",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              vn.viettel.okrkpi.shared.kernel.Clock.systemUtc()));
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private EvidenceObjectListener listener;
  @Autowired private EvidenceQueryApi evidenceQuery;
  @Autowired private EvidenceLifecycleService lifecycle;
  @Autowired private InboxProperties inboxProperties;
  @Autowired private InboxRetryDlqPublisher dlq;
  @Autowired private InboxMetrics metrics;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("productDataSource") private DataSource productDataSource;

  @Autowired
  @Qualifier("productTransactionManager") private PlatformTransactionManager productTm;

  private TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.PRODUCTS;
  }

  @BeforeAll
  void seed() throws Exception {
    in(
        taskTm,
        taskDataSource,
        jdbc -> {
          for (String t : List.of("task", "task_assignee", "task_state_log")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + t, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
    in(
        productTm,
        productDataSource,
        jdbc -> {
          for (String t :
              List.of(
                  "deliverable",
                  "evidence",
                  "evidence_version",
                  "evidence_version_file",
                  "acceptance")) {
            jdbc.sql("SELECT product.fn_create_period_partitions(?::regclass, ?)")
                .params("product." + t, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @Test
  void khaiBaoLuuNhapVaNop() throws Exception {
    tenant = TenantId.of(UUID.randomUUID());
    caller = ACTOR;
    callerAcr = null;
    callerAuthTime = null;
    UUID task = task(ACTOR);
    UUID otherTask = task(ACTOR);
    UUID foreignTask = task(UUID.randomUUID());

    // ---- sản phẩm
    String key = UUID.randomUUID().toString();
    String body = deliverableBody(task);
    MvcResult created = perform(post("/api/v1/products").header("Idempotency-Key", key), body);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/products", PathItem.HttpMethod.POST);
    String deliverable = body(created).get("id").asText();
    assertThat(body(perform(post("/api/v1/products").header("Idempotency-Key", key), body)))
        .isEqualTo(body(created));
    MvcResult notOwner =
        perform(
            post("/api/v1/products").header("Idempotency-Key", UUID.randomUUID().toString()),
            deliverableBody(foreignTask));
    assertThat(notOwner.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(notOwner).get("code").asText()).isEqualTo("PRODUCT-403-NOT-TASK-OWNER");

    MvcResult list = perform(get("/api/v1/products").param("task", task.toString()));
    assertResponseConforms(list, "/products", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items")).hasSize(1);
    MvcResult one = perform(get("/api/v1/products/" + deliverable));
    assertResponseConforms(one, "/products/{id}", PathItem.HttpMethod.GET);
    assertThat(one.getResponse().getHeader("ETag")).isEqualTo("\"0\"");

    // ---- đối tượng ở SVC-08: lưu (trùng bị bỏ qua), quét
    UUID objA = UUID.randomUUID();
    UUID objB = UUID.randomUUID();
    UUID storedA = UUID.randomUUID();
    assertThat(stored(storedA, objA, 1, sha(7))).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(stored(storedA, objA, 1, sha(7))).isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(scanned(objA, 1, "CLEAN")).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(stored(UUID.randomUUID(), objB, 1, sha(8))).isEqualTo(InboxOutcome.PROCESSED);

    // ---- nhiệm vụ khác đã nộp tệp A trước
    String otherEvidence = evidence(deliverable(otherTask));
    String v1Other = draft(otherEvidence, List.of(objA), null);
    assertThat(
            submit(otherEvidence, 1, v1Other, UUID.randomUUID().toString())
                .getResponse()
                .getStatus())
        .isEqualTo(200);

    // ---- minh chứng của nhiệm vụ chính
    MvcResult evidenceCreated =
        perform(
            post("/api/v1/evidences").header("Idempotency-Key", UUID.randomUUID().toString()),
            """
            {"deliverableId":"%s","title":"Báo cáo tổng kết quý","sourceType":"TAI_LEN"}
            """
                .formatted(deliverable));
    assertThat(evidenceCreated.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(evidenceCreated, "/evidences", PathItem.HttpMethod.POST);
    String evidence = body(evidenceCreated).get("id").asText();

    // Tệp B chưa quét: lưu nháp được, nộp bị chặn.
    MvcResult v1 =
        perform(
            post("/api/v1/evidences/" + evidence + "/versions")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            draftBody(List.of(objB), null));
    assertThat(v1.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(v1, "/evidences/{id}/versions", PathItem.HttpMethod.POST);
    assertThat(body(v1).get("scanStatus").asText()).isEqualTo("CHO_QUET");
    assertThat(body(v1).get("files").get(0).get("contentSha256").asText())
        .isEqualTo(HexFormat.of().formatHex(sha(8)));
    MvcResult notClean = submit(evidence, 1, "\"1\"", UUID.randomUUID().toString());
    assertThat(notClean.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(notClean).get("code").asText()).isEqualTo("PRODUCT-422-NOT-CLEAN");

    // Kết quả quét nhiễm chép vào bản nháp.
    assertThat(scanned(objB, 1, "INFECTED")).isEqualTo(InboxOutcome.PROCESSED);
    MvcResult infected = perform(get("/api/v1/evidences/" + evidence + "/versions/1"));
    assertResponseConforms(infected, "/evidences/{id}/versions/{v}", PathItem.HttpMethod.GET);
    assertThat(body(infected).get("scanStatus").asText()).isEqualTo("NHIEM");
    assertThat(body(infected).get("permittedActions").isArray()).isTrue();
    assertThat(body(infected).get("files").get(0).get("scanStatus").asText()).isEqualTo("NHIEM");

    // Tệp chưa lưu xong: 422.
    MvcResult unknown =
        perform(
            put("/api/v1/evidences/" + evidence + "/versions/1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"1\""),
            draftBody(List.of(UUID.randomUUID()), null));
    assertThat(body(unknown).get("code").asText()).isEqualTo("PRODUCT-422-NO-OBJECT");

    // Đổi sang tệp A (trùng với nhiệm vụ khác): thiếu If-Match 428, lệch 409.
    assertThat(
            perform(
                    put("/api/v1/evidences/" + evidence + "/versions/1")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                    draftBody(List.of(objA), null))
                .getResponse()
                .getStatus())
        .isEqualTo(428);
    MvcResult stale =
        perform(
            put("/api/v1/evidences/" + evidence + "/versions/1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"9\""),
            draftBody(List.of(objA), null));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    MvcResult dup =
        perform(
            put("/api/v1/evidences/" + evidence + "/versions/1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"1\""),
            draftBody(List.of(objA), null));
    assertThat(dup.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(dup, "/evidences/{id}/versions/{v}", PathItem.HttpMethod.PUT);
    JsonNode dupFile = body(dup).get("files").get(0);
    assertThat(dupFile.get("isDuplicateSuspect").asBoolean()).isTrue();
    assertThat(dupFile.get("duplicateOf").get("taskCode").asText()).startsWith("NV-");
    assertThat(body(dup).get("scanStatus").asText()).isEqualTo("SACH");
    MvcResult noReason = submit(evidence, 1, "\"2\"", UUID.randomUUID().toString());
    assertThat(body(noReason).get("code").asText())
        .isEqualTo("PRODUCT-422-REUSE-JUSTIFICATION-REQUIRED");

    String reason = "Báo cáo dùng chung cho hai nhiệm vụ theo chỉ đạo của lãnh đạo đơn vị";
    MvcResult justified =
        perform(
            put("/api/v1/evidences/" + evidence + "/versions/1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"2\""),
            draftBody(List.of(objA), reason));
    assertThat(justified.getResponse().getStatus()).isEqualTo(200);

    // Nộp: nhiệm vụ sang chờ nghiệm thu, EVT-07 một lần dù gửi lại cùng khoá.
    String submitKey = UUID.randomUUID().toString();
    MvcResult submitted = submit(evidence, 1, "\"3\"", submitKey);
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        submitted, "/evidences/{id}/versions/{v}/submit", PathItem.HttpMethod.POST);
    JsonNode result = body(submitted);
    assertThat(result.get("version").get("status").asText()).isEqualTo("DA_NOP");
    assertThat(result.get("version").get("attestedAt").isNull()).isFalse();
    assertThat(result.get("taskStatusChanged").asText()).isEqualTo("CHO_NGHIEM_THU");
    assertThat(result.get("reviewer").get("id").asText()).isEqualTo(ASSIGNER.toString());
    assertThat(body(submit(evidence, 1, "\"3\"", submitKey))).isEqualTo(result);
    assertThat(outbox(EventTopics.EVT_07_MINH_CHUNG_DA_NOP)).isEqualTo(2);
    assertThat(outbox(EventTopics.EVT_21_YEU_CAU_THONG_BAO)).isEqualTo(2);
    assertThat(taskStatus(task)).isEqualTo("CHO_NGHIEM_THU");

    // Đã nộp: không sửa, không tạo phiên bản mới, nộp lại bằng khoá khác 409.
    MvcResult frozen =
        perform(
            put("/api/v1/evidences/" + evidence + "/versions/1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"4\""),
            draftBody(List.of(objA), reason));
    assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
    MvcResult open =
        perform(
            post("/api/v1/evidences/" + evidence + "/versions")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            draftBody(List.of(objA), reason));
    assertThat(body(open).get("code").asText()).isEqualTo("PRODUCT-409-OPEN-VERSION-EXISTS");
    assertThat(
            body(submit(evidence, 1, "\"4\"", UUID.randomUUID().toString())).get("code").asText())
        .isEqualTo("PRODUCT-409-INVALID-STATE");

    MvcResult detail = perform(get("/api/v1/evidences/" + evidence));
    assertResponseConforms(detail, "/evidences/{id}", PathItem.HttpMethod.GET);
    assertThat(body(detail).get("currentStatus").asText()).isEqualTo("DA_NOP");
    assertThat(body(detail).get("versions")).hasSize(1);
    MvcResult mine =
        perform(get("/api/v1/evidences").param("submitter", "me").param("status", "DA_NOP"));
    assertResponseConforms(mine, "/evidences", PathItem.HttpMethod.GET);
    assertThat(body(mine).get("items")).hasSize(2);
  }

  /**
   * CORE-03-06: người nộp tự mở bị 403; người giao mở lần đầu chuyển sang đang nghiệm thu, mở lại
   * không đổi trạng thái; tệp đổi ở SVC-08 sau khi nộp thì chặn, có EVT-21 cảnh báo trong outbox.
   */
  @Test
  void moNghiemThuVaKiemToanVen() throws Exception {
    tenant = TenantId.of(UUID.randomUUID());
    caller = ACTOR;
    callerAcr = null;
    callerAuthTime = null;
    UUID task = task(ACTOR);
    UUID obj = UUID.randomUUID();
    assertThat(stored(UUID.randomUUID(), obj, 1, sha(11))).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(scanned(obj, 1, "CLEAN")).isEqualTo(InboxOutcome.PROCESSED);
    String evidence = evidence(deliverable(task));
    String etag = draft(evidence, List.of(obj), null);
    MvcResult submitted = submit(evidence, 1, etag, UUID.randomUUID().toString());
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    String afterSubmit = submitted.getResponse().getHeader("ETag");

    // Người nộp (chủ trì) không tự nghiệm thu.
    MvcResult self = openReview(evidence, afterSubmit);
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("PRODUCT-403-SELF-REVIEW");

    caller = ASSIGNER;
    MvcResult queue =
        perform(
            get("/api/v1/evidences")
                .param("reviewer", "me")
                .param("status", "DA_NOP,DANG_NGHIEM_THU"));
    assertResponseConforms(queue, "/evidences", PathItem.HttpMethod.GET);
    assertThat(body(queue).get("items")).hasSize(1);
    assertThat(body(queue).get("items").get(0).get("id").asText()).isEqualTo(evidence);
    assertThat(
            perform(
                    post("/api/v1/evidences/" + evidence + "/versions/1/open-review")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(428);

    MvcResult opened = openReview(evidence, afterSubmit);
    assertThat(opened.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        opened, "/evidences/{id}/versions/{v}/open-review", PathItem.HttpMethod.POST);
    assertThat(body(opened).get("status").asText()).isEqualTo("DANG_NGHIEM_THU");
    // SCR-0703 chỉ mở đánh giá khi lần mở có kết quả kiểm toàn vẹn đạt (QA-05 HT-21).
    assertThat(body(opened).get("integrity").get("verified").asBoolean()).isTrue();
    assertThat(body(opened).get("permittedActions").toString()).contains("SCR-0703.xac-nhan");
    String inReview = opened.getResponse().getHeader("ETag");
    MvcResult again = openReview(evidence, inReview);
    assertThat(again.getResponse().getStatus()).isEqualTo(200);
    assertThat(again.getResponse().getHeader("ETag")).isEqualTo(inReview);
    assertThat(body(again).get("status").asText()).isEqualTo("DANG_NGHIEM_THU");

    // Đối tượng bị ghi phiên bản mới ở SVC-08 sau khi nộp: chặn, giữ trạng thái, có cảnh báo.
    long alertsBefore = outbox(EventTopics.EVT_21_YEU_CAU_THONG_BAO);
    assertThat(stored(UUID.randomUUID(), obj, 2, sha(12))).isEqualTo(InboxOutcome.PROCESSED);
    MvcResult tampered = openReview(evidence, inReview);
    assertThat(tampered.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(tampered).get("code").asText()).isEqualTo("PRODUCT-409-INTEGRITY-FAILED");
    assertThat(outbox(EventTopics.EVT_21_YEU_CAU_THONG_BAO)).isEqualTo(alertsBefore + 1);
    assertThat(
            body(perform(get("/api/v1/evidences/" + evidence + "/versions/1")))
                .get("status")
                .asText())
        .isEqualTo("DANG_NGHIEM_THU");

    // Cổng đọc cho MOD-04: chỉ minh chứng đã xác nhận của chính người nộp.
    UUID evidenceId = UUID.fromString(evidence);
    UUID versionId = UUID.fromString(body(again).get("id").asText());
    assertThat(as(() -> evidenceQuery.confirmedEvidence(tenant, ACTOR, List.of(evidenceId))))
        .isEmpty();
    long rowVersion = Long.parseLong(inReview.replace("\"", ""));
    as(
        () ->
            lifecycle.acceptVersion(
                tenant, versionId, rowVersion, new EvidenceActor(ASSIGNER, Set.of("manager"))));
    assertThat(
            as(
                () ->
                    evidenceQuery.confirmedEvidence(
                        tenant, ACTOR, List.of(evidenceId, UUID.randomUUID()))))
        .containsExactly(evidenceId);
    assertThat(as(() -> evidenceQuery.confirmedEvidence(tenant, ASSIGNER, List.of(evidenceId))))
        .isEmpty();
  }

  /**
   * CORE-03-07: thiếu AAL2 hoặc AAL2 quá 15 phút bị chặn; người phối hợp không được nghiệm thu; mức
   * 0,5 thiếu nhận xét 422; xác nhận ghi nhận xét đã mã hoá, EVT-08 một lần dù gửi lại cùng khoá,
   * nhiệm vụ hoàn thành; yêu cầu bổ sung đưa nhiệm vụ về đang thực hiện và báo người nộp.
   */
  @Test
  void nghiemThuNamMuc() throws Exception {
    tenant = TenantId.of(UUID.randomUUID());
    caller = ACTOR;
    callerAcr = null;
    callerAuthTime = null;
    UUID task = task(ACTOR);
    String[] first = submitted(task, 21);
    String evidence = first[0];

    // Người phối hợp của nhiệm vụ không được mở (BR-07-06).
    UUID coTask = task(ACTOR);
    coordinator(coTask, ASSIGNER);
    String[] co = submitted(coTask, 22);

    caller = ASSIGNER;
    MvcResult denied = openReview(co[0], co[1]);
    assertThat(body(denied).get("code").asText()).isEqualTo("PRODUCT-403-SELF-REVIEW");
    MvcResult opened = openReview(evidence, first[1]);
    assertThat(opened.getResponse().getStatus()).isEqualTo(200);
    String inReview = opened.getResponse().getHeader("ETag");

    String acceptBody =
        """
        {"qualityLevel":0.75,"criteriaResults":[{"criterionCode":"TC-1","passed":true}],
         "comment":"Thiếu bảng tổng hợp chi bộ 3"}
        """;
    MvcResult noAal2 =
        decide(evidence, "accept", inReview, UUID.randomUUID().toString(), acceptBody);
    assertThat(noAal2.getResponse().getStatus()).isEqualTo(401);
    callerAcr = "aal2";
    callerAuthTime = Instant.now().minusSeconds(16 * 60).getEpochSecond();
    assertThat(
            decide(evidence, "accept", inReview, UUID.randomUUID().toString(), acceptBody)
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    callerAuthTime = Instant.now().minusSeconds(60).getEpochSecond();
    MvcResult noComment =
        decide(
            evidence,
            "accept",
            inReview,
            UUID.randomUUID().toString(),
            """
            {"qualityLevel":0.5,"criteriaResults":[{"criterionCode":"TC-1","passed":false}]}
            """);
    assertThat(noComment.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(noComment).get("code").asText()).isEqualTo("PRODUCT-422-COMMENT-REQUIRED");

    String key = UUID.randomUUID().toString();
    MvcResult accepted = decide(evidence, "accept", inReview, key, acceptBody);
    assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        accepted, "/evidences/{id}/versions/{v}/accept", PathItem.HttpMethod.POST);
    JsonNode result = body(accepted);
    assertThat(result.get("versionStatus").asText()).isEqualTo("DA_XAC_NHAN");
    assertThat(result.get("acceptance").get("comment").asText())
        .isEqualTo("Thiếu bảng tổng hợp chi bộ 3");
    assertThat(result.get("taskStatusChanged").asText()).isEqualTo("HOAN_THANH");
    assertThat(body(decide(evidence, "accept", inReview, key, acceptBody))).isEqualTo(result);
    assertThat(outbox(EventTopics.EVT_08_MINH_CHUNG_DA_XAC_NHAN)).isEqualTo(1);
    assertThat(taskStatus(task)).isEqualTo("HOAN_THANH");
    // GET phiên bản sau xác nhận mang kết quả đọc lại từ CSDL (mức 0.75 lưu dạng numeric)
    JsonNode reread =
        body(perform(get("/api/v1/evidences/" + evidence + "/versions/1"))).get("acceptance");
    assertThat(reread.get("decision").asText()).isEqualTo("DA_XAC_NHAN");
    assertThat(reread.get("qualityLevel").decimalValue()).isEqualByComparingTo("0.75");
    byte[] cipher =
        in(
            productTm,
            productDataSource,
            jdbc ->
                jdbc.sql(
                        "SELECT comment_enc FROM product.acceptance WHERE tenant_id = ? AND"
                            + " decision = 'DA_XAC_NHAN'")
                    .param(tenant.value())
                    .query(byte[].class)
                    .single());
    assertThat(new String(cipher, StandardCharsets.UTF_8)).doesNotContain("chi bộ");
    assertThat(cipher[0]).isEqualTo((byte) 0x01);
    // Nhóm người nghiệm thu của BR-09-14 (CORE-04-05).
    assertThat(as(() -> evidenceQuery.acceptors(tenant, period, ACTOR))).containsExactly(ASSIGNER);
    assertThat(as(() -> evidenceQuery.acceptors(tenant, period, ASSIGNER))).isEmpty();

    // Yêu cầu bổ sung: nhiệm vụ về đang thực hiện, người nộp được báo. Nhiệm vụ không chỉ định
    // người
    // nghiệm thu: người giao là người nghiệm thu mặc định, guard REWORK của MOD-02 phải nhận
    // (QA-05).
    UUID task2 = task(ACTOR, null);
    caller = ACTOR;
    String[] second = submitted(task2, 23);
    String evidence2 = second[0];
    assertThat(taskStatus(task2)).isEqualTo("CHO_NGHIEM_THU");
    caller = ASSIGNER;
    String review2 = openReview(evidence2, second[1]).getResponse().getHeader("ETag");
    long notices = outbox(EventTopics.EVT_21_YEU_CAU_THONG_BAO);
    MvcResult empty =
        decide(
            evidence2,
            "request-revision",
            review2,
            UUID.randomUUID().toString(),
            "{\"comment\":\" \"}");
    assertThat(body(empty).get("code").asText()).isEqualTo("PRODUCT-422-COMMENT-REQUIRED");
    MvcResult revision =
        decide(
            evidence2,
            "request-revision",
            review2,
            UUID.randomUUID().toString(),
            "{\"comment\":\"Đề nghị bổ sung số liệu chi bộ 3\"}");
    assertThat(revision.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        revision, "/evidences/{id}/versions/{v}/request-revision", PathItem.HttpMethod.POST);
    assertThat(body(revision).get("versionStatus").asText()).isEqualTo("YEU_CAU_BO_SUNG");
    assertThat(body(revision).get("acceptance").get("revisionCount").asInt()).isEqualTo(1);
    assertThat(taskStatus(task2)).isEqualTo("DANG_THUC_HIEN");
    assertThat(outbox(EventTopics.EVT_21_YEU_CAU_THONG_BAO)).isEqualTo(notices + 1);

    // GET phiên bản mang quyết định gần nhất; người nộp đọc được nhận xét để biết phải bổ sung gì
    caller = ACTOR;
    MvcResult seen = perform(get("/api/v1/evidences/" + evidence2 + "/versions/1"));
    assertResponseConforms(seen, "/evidences/{id}/versions/{v}", PathItem.HttpMethod.GET);
    JsonNode seenAcceptance = body(seen).get("acceptance");
    assertThat(seenAcceptance.get("decision").asText()).isEqualTo("YEU_CAU_BO_SUNG");
    assertThat(seenAcceptance.get("comment").asText())
        .isEqualTo("Đề nghị bổ sung số liệu chi bộ 3");
    assertThat(body(seen).get("hasPriorRevisionRequest").asBoolean()).isFalse();
  }

  /**
   * CORE-03-05 · BR-07-09: ô do AI điền chặn nộp tới khi người nộp xác nhận; SVC-14 quá thời hạn
   * thì trả lỗi thử lại được và phiên bản vẫn ở nháp; xác nhận ghi sổ cái ô giữ, ô sửa; tệp C3
   * không mang được ô AI và không bao giờ tạo lời gọi tới SVC-14.
   */
  @Test
  void oDoAiDienCanXacNhanVaKhongGuiTepC3() throws Exception {
    caller = ACTOR;
    UUID task = task(ACTOR);
    UUID obj = UUID.randomUUID();
    assertThat(stored(UUID.randomUUID(), obj, 1, sha(71))).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(scanned(obj, 1, "CLEAN")).isEqualTo(InboxOutcome.PROCESSED);
    String evidence = evidence(deliverable(task));
    UUID suggestion = UUID.randomUUID();
    MvcResult created =
        perform(
            post("/api/v1/evidences/" + evidence + "/versions")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            aiDraftBody(obj, suggestion, "[\"docSummary\"]"));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/evidences/{id}/versions", PathItem.HttpMethod.POST);
    assertThat(body(created).get("aiPrefill").asBoolean()).isTrue();
    assertThat(body(created).get("aiSuggestionId").asText()).isEqualTo(suggestion.toString());
    String etag = created.getResponse().getHeader("ETag");
    String versionId = body(created).get("id").asText();
    AI_CALLS.clear();

    MvcResult unconfirmed = submit(evidence, 1, etag, UUID.randomUUID().toString());
    assertThat(unconfirmed.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(unconfirmed).get("code").asText()).isEqualTo("PRODUCT-422-AI-NOT-CONFIRMED");
    assertThat(AI_CALLS).isEmpty();

    long audits = outbox(EventTopics.SYS_AUDIT_V1);
    aiDown = true;
    try {
      MvcResult down = confirmedSubmit(evidence, etag);
      assertThat(down.getResponse().getStatus()).isEqualTo(503);
      assertThat(down.getResponse().getHeader("Retry-After")).isNotNull();
    } finally {
      aiDown = false;
    }
    MvcResult still = perform(get("/api/v1/evidences/" + evidence + "/versions/1"));
    assertThat(body(still).get("status").asText()).isEqualTo("NHAP");
    assertThat(outbox(EventTopics.SYS_AUDIT_V1)).isEqualTo(audits);

    MvcResult ok = confirmedSubmit(evidence, etag);
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(ok, "/evidences/{id}/versions/{v}/submit", PathItem.HttpMethod.POST);
    assertThat(body(ok).get("version").get("userConfirmedAt").isNull()).isFalse();
    String expectedCall = suggestion + " SUA product.evidence_version:" + versionId;
    assertThat(AI_CALLS).containsExactly(expectedCall, expectedCall);
    assertThat(outbox(EventTopics.SYS_AUDIT_V1)).isEqualTo(audits + 1);

    // Tệp C3: không mang được ô AI, nộp có xác nhận cũng không gọi SVC-14.
    AI_CALLS.clear();
    UUID c3 = UUID.randomUUID();
    assertThat(stored(UUID.randomUUID(), c3, 1, sha(72), true)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(scanned(c3, 1, "CLEAN")).isEqualTo(InboxOutcome.PROCESSED);
    String secret = evidence(deliverable(task(ACTOR)));
    MvcResult refused =
        perform(
            post("/api/v1/evidences/" + secret + "/versions")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            aiDraftBody(c3, UUID.randomUUID(), "[]"));
    assertThat(refused.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(refused).get("errors").get(0).get("field").asText()).isEqualTo("aiPrefill");
    String c3Etag = draft(secret, List.of(c3), null);
    MvcResult c3Submit = confirmedSubmit(secret, c3Etag);
    assertThat(c3Submit.getResponse().getStatus()).isEqualTo(200);
    assertThat(AI_CALLS).isEmpty();
  }

  /**
   * CORE-03-04 · BR-07-13: tìm văn bản của đơn vị, nhận văn bản có chữ ký hợp lệ làm minh chứng,
   * tệp gốc do SVC-13 chuyển sang SVC-08 thành tệp thứ nhất không sửa được, kết quả kiểm chữ ký ghi
   * sổ cái; chữ ký sai bị từ chối theo BR-07-13; SVC-13 không phản hồi trả 503 kèm Retry-After.
   */
  @Test
  void vanBanDaBanHanhLamMinhChung() throws Exception {
    caller = ACTOR;
    MvcResult found = perform(get("/api/v1/evidences/external-docs").param("q", "BTCĐUK"));
    assertThat(found.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(found, "/evidences/external-docs", PathItem.HttpMethod.GET);
    assertThat(body(found).get("items")).hasSize(2);
    assertThat(body(found).get("items").get(0).get("signatureValid").asBoolean()).isTrue();
    assertThat(body(found).get("items").get(1).get("signatureValid").asBoolean()).isFalse();

    UUID task = task(ACTOR);
    String deliverable = deliverable(task);
    MvcResult refused = perform(evidencePost(), externalBody(deliverable, BAD_REF));
    assertThat(refused.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(refused).get("errors").get(0).get("rule").asText()).isEqualTo("BR-07-13");

    MvcResult created = perform(evidencePost(), externalBody(deliverable, GOOD_REF));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    String evidence = body(created).get("id").asText();
    long audits = outbox(EventTopics.SYS_AUDIT_V1);

    // Chữ ký của tệp đã chuyển không còn hợp lệ: từ chối, không mở phiên bản.
    tampered = true;
    try {
      MvcResult bad = perform(versionPost(evidence), draftBody(List.of(), null));
      assertThat(bad.getResponse().getStatus()).isEqualTo(422);
      assertThat(body(bad).get("errors").get(0).get("rule").asText()).isEqualTo("BR-07-13");
    } finally {
      tampered = false;
    }
    integDown = true;
    try {
      MvcResult down = perform(versionPost(evidence), draftBody(List.of(), null));
      assertThat(down.getResponse().getStatus()).isEqualTo(503);
      assertThat(down.getResponse().getHeader("Retry-After")).isNotNull();
      MvcResult searchDown = perform(get("/api/v1/evidences/external-docs"));
      assertThat(searchDown.getResponse().getStatus()).isEqualTo(503);
    } finally {
      integDown = false;
    }
    assertThat(outbox(EventTopics.SYS_AUDIT_V1)).isEqualTo(audits);

    MvcResult opened = perform(versionPost(evidence), draftBody(List.of(), null));
    assertThat(opened.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(opened, "/evidences/{id}/versions", PathItem.HttpMethod.POST);
    JsonNode v = body(opened);
    assertThat(v.get("source").asText()).isEqualTo("VAN_BAN_HE_THONG");
    assertThat(v.get("sourceDocRef").asText()).isEqualTo(GOOD_REF);
    assertThat(v.get("docNumber").asText()).isEqualTo(GOOD_REF);
    assertThat(v.get("docDate").asText()).isEqualTo("2027-01-19");
    assertThat(v.get("files")).hasSize(1);
    UUID original = UUID.fromString(v.get("files").get(0).get("objectId").asText());
    assertThat(TRANSFERRED).containsValue(original);
    assertThat(outbox(EventTopics.SYS_AUDIT_V1)).isEqualTo(audits + 1);

    // Không thay được tệp gốc, không tự gán nguồn văn bản cho nháp thường.
    UUID other = UUID.randomUUID();
    assertThat(stored(UUID.randomUUID(), other, 1, sha(92))).isEqualTo(InboxOutcome.PROCESSED);
    MvcResult replaced =
        perform(
            put("/api/v1/evidences/" + evidence + "/versions/1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", opened.getResponse().getHeader("ETag")),
            draftBody(List.of(other), null));
    assertThat(replaced.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(replaced).get("errors").get(0).get("field").asText())
        .isEqualTo("files[0].objectId");
    MvcResult appended =
        perform(
            put("/api/v1/evidences/" + evidence + "/versions/1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", opened.getResponse().getHeader("ETag")),
            draftBody(List.of(original, other), null)
                .replace("\"TAI_LEN\"", "\"VAN_BAN_HE_THONG\""));
    assertThat(appended.getResponse().getStatus())
        .as(appended.getResponse().getContentAsString())
        .isEqualTo(200);
    assertThat(body(appended).get("source").asText()).isEqualTo("VAN_BAN_HE_THONG");
    assertThat(body(appended).get("files")).hasSize(2);

    String plain = evidence(deliverable(task(ACTOR)));
    MvcResult forged =
        perform(
            versionPost(plain),
            "{\"source\":\"VAN_BAN_HE_THONG\",\"files\":[{\"objectId\":\"" + other + "\"}]}");
    assertThat(forged.getResponse().getStatus()).isEqualTo(422);
  }

  private static MockHttpServletRequestBuilder evidencePost() {
    return post("/api/v1/evidences").header("Idempotency-Key", UUID.randomUUID().toString());
  }

  private static MockHttpServletRequestBuilder versionPost(String evidence) {
    return post("/api/v1/evidences/" + evidence + "/versions")
        .header("Idempotency-Key", UUID.randomUUID().toString());
  }

  private static String externalBody(String deliverable, String ref) {
    return """
        {"deliverableId":"%s","title":"Báo cáo chuyên đề","sourceType":"VAN_BAN","externalDocRef":"%s"}
        """
        .formatted(deliverable, ref);
  }

  private MvcResult confirmedSubmit(String evidence, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/evidences/" + evidence + "/versions/1/submit")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch),
        "{\"attested\":true,\"aiFieldsConfirmed\":true}");
  }

  private static String aiDraftBody(UUID file, UUID suggestion, String edited) {
    return """
        {"source":"OCR_DI_DONG","files":[{"objectId":"%s"}],"aiPrefill":true,
         "aiSuggestionId":"%s","aiFieldsEdited":%s,"docNumber":"47-BC/BTCĐUK",
         "docDate":"2027-01-16","docSummary":"Báo cáo sơ kết công tác quý"}
        """
        .formatted(file, suggestion, edited);
  }

  /** Người nộp (ACTOR) tạo minh chứng một tệp sạch mới và nộp; trả mã minh chứng và ETag. */
  private String[] submitted(UUID task, int shaByte) throws Exception {
    UUID previous = caller;
    caller = ACTOR;
    UUID obj = UUID.randomUUID();
    assertThat(stored(UUID.randomUUID(), obj, 1, sha(shaByte))).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(scanned(obj, 1, "CLEAN")).isEqualTo(InboxOutcome.PROCESSED);
    String evidence = evidence(deliverable(task));
    String etag = draft(evidence, List.of(obj), null);
    MvcResult r = submit(evidence, 1, etag, UUID.randomUUID().toString());
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    caller = previous;
    return new String[] {evidence, r.getResponse().getHeader("ETag")};
  }

  private MvcResult decide(String evidence, String action, String ifMatch, String key, String body)
      throws Exception {
    return perform(
        post("/api/v1/evidences/" + evidence + "/versions/1/" + action)
            .header("Idempotency-Key", key)
            .header("If-Match", ifMatch),
        body);
  }

  private void coordinator(UUID task, UUID person) throws Exception {
    in(
        taskTm,
        taskDataSource,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                        + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, 'PHOI_HOP', DATE '2027-01-01')")
                .params(UUID.randomUUID(), ASSIGNER, ASSIGNER, period, BUCKET, task, person)
                .update());
  }

  private MvcResult openReview(String evidence, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/evidences/" + evidence + "/versions/1/open-review")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch));
  }

  // ---------------------------------------------------------------- dữ liệu

  private UUID task(UUID lead) throws Exception {
    return task(lead, ASSIGNER);
  }

  private UUID task(UUID lead, UUID acceptor) throws Exception {
    UUID id = UUID.randomUUID();
    in(
        taskTm,
        taskDataSource,
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket, code,"
                      + " title, task_group, source, assigner_id, acceptor_id, due_date, unit,"
                      + " planned_quantity, dup_fingerprint, state_machine_version, status) VALUES (?, ?,"
                      + " ?, ?, ?, ?, 'Báo cáo tổng kết công tác quý', 'THUONG_XUYEN', 'KE_HOACH', ?, ?,"
                      + " DATE '2027-03-20', 'báo cáo', 1, ?, 'mac-dinh@2.1.0', 'DANG_THUC_HIEN')")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-" + id.toString().substring(0, 8),
                  ASSIGNER,
                  acceptor,
                  id.toString().getBytes(StandardCharsets.UTF_8))
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id, period_bucket,"
                      + " task_id, assignee_type, person_id, role, valid_from) VALUES (?, ?, ?, ?, ?, ?,"
                      + " 'CA_NHAN', ?, 'CHU_TRI', DATE '2027-01-01')")
              .params(UUID.randomUUID(), ASSIGNER, ASSIGNER, period, BUCKET, id, lead)
              .update();
          return null;
        });
    return id;
  }

  private String deliverable(UUID task) throws Exception {
    return body(perform(
            post("/api/v1/products").header("Idempotency-Key", UUID.randomUUID().toString()),
            deliverableBody(task)))
        .get("id")
        .asText();
  }

  private String evidence(String deliverable) throws Exception {
    return body(perform(
            post("/api/v1/evidences").header("Idempotency-Key", UUID.randomUUID().toString()),
            """
                {"deliverableId":"%s","title":"Báo cáo đã nộp","sourceType":"TAI_LEN"}
                """
                .formatted(deliverable)))
        .get("id")
        .asText();
  }

  /** Tạo phiên bản 1; trả {@code ETag}. */
  private String draft(String evidence, List<UUID> files, String reason) throws Exception {
    MvcResult r =
        perform(
            post("/api/v1/evidences/" + evidence + "/versions")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            draftBody(files, reason));
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    return r.getResponse().getHeader("ETag");
  }

  private MvcResult submit(String evidence, int v, String ifMatch, String key) throws Exception {
    return perform(
        post("/api/v1/evidences/" + evidence + "/versions/" + v + "/submit")
            .header("Idempotency-Key", key)
            .header("If-Match", ifMatch),
        "{\"attested\":true}");
  }

  private static String deliverableBody(UUID task) {
    return """
        {"taskId":"%s","name":"Báo cáo quý","deliverableType":"BAO_CAO","unit":"báo cáo","quantityPlanned":1}
        """
        .formatted(task);
  }

  private static String draftBody(List<UUID> files, String reason) {
    StringBuilder b = new StringBuilder("{\"source\":\"TAI_LEN\",\"files\":[");
    for (int i = 0; i < files.size(); i++) {
      if (i > 0) {
        b.append(',');
      }
      b.append("{\"objectId\":\"").append(files.get(i)).append('"');
      if (reason != null) {
        b.append(",\"reuseJustification\":\"").append(reason).append('"');
      }
      b.append('}');
    }
    return b.append("]}").toString();
  }

  private static byte[] sha(int b) {
    byte[] s = new byte[32];
    Arrays.fill(s, (byte) b);
    return s;
  }

  // ---------------------------------------------------------------- inbox

  private InboxOutcome stored(UUID eventId, UUID objectId, int version, byte[] sha) {
    return stored(eventId, objectId, version, sha, false);
  }

  private InboxOutcome stored(UUID eventId, UUID objectId, int version, byte[] sha, boolean e2ee) {
    EvidenceObjectStored event =
        EvidenceObjectStored.newBuilder()
            .setObjectId(objectId)
            .setVersion(version)
            .setSha256(ByteBuffer.wrap(sha))
            .setSizeBytes(2048)
            .setContentType("application/pdf")
            .setBucketRef("evidence/ab/cd")
            .setE2ee(e2ee)
            .setUploadedBy(ACTOR)
            .setOwnerRef("product:")
            .setStoredAt(Instant.now())
            .build();
    return process(
        EvidenceSubmissionConfiguration.storedEndpoint(listener),
        EventTopics.SYS_EVIDENCE_OBJECT_STORED_V1,
        eventId,
        objectId,
        event);
  }

  private InboxOutcome scanned(UUID objectId, int version, String verdict) {
    ObjectScanned event =
        ObjectScanned.newBuilder()
            .setObjectId(objectId)
            .setVersion(version)
            .setVerdict(verdict)
            .setEngine("clamav")
            .setEngineVersion("1.4")
            .setScannedAt(Instant.now())
            .build();
    return process(
        EvidenceSubmissionConfiguration.scannedEndpoint(listener),
        EventTopics.EVIDENCE_OBJECT_SCANNED_V1,
        UUID.randomUUID(),
        objectId,
        event);
  }

  private InboxOutcome process(
      InboxEndpoint endpoint, String topic, UUID eventId, UUID objectId, SpecificRecord event) {
    InboxMessageProcessor processor =
        EvidenceSubmissionConfiguration.productInboxProcessor(
            productDataSource,
            productTm,
            inboxProperties,
            (t, headers, data) -> event,
            dlq,
            metrics);
    ConsumerRecord<String, byte[]> r =
        new ConsumerRecord<>(topic, 0, 0L, tenant.value() + ":" + objectId, new byte[] {0});
    r.headers().add(new RecordHeader(OutboxHeaders.EVENT_ID, bytes(eventId.toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.TENANT_ID, bytes(tenant.value().toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.AGGREGATE_VERSION, bytes("1")));
    return processor.process(endpoint, r, () -> {});
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private long outbox(String topic) throws Exception {
    return in(
        productTm,
        productDataSource,
        jdbc ->
            jdbc.sql("SELECT count(*) FROM product.outbox_event WHERE tenant_id = ? AND topic = ?")
                .params(tenant.value(), topic)
                .query(Long.class)
                .single());
  }

  private String taskStatus(UUID task) throws Exception {
    return in(
        taskTm,
        taskDataSource,
        jdbc ->
            jdbc.sql("SELECT status FROM task.task WHERE id = ?")
                .param(task)
                .query(String.class)
                .single());
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    UUID who = caller;
    return as(() -> mvc.perform(request.principal(who::toString)).andReturn());
  }

  private MvcResult perform(MockHttpServletRequestBuilder request, String body) throws Exception {
    return perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private <T> T as(Callable<T> work) throws Exception {
    return TenantContext.call(tenant, work);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private <T> T in(PlatformTransactionManager tm, DataSource ds, Function<JdbcClient, T> work)
      throws Exception {
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(JdbcClient.create(ds))));
  }
}
