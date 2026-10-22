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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import vn.viettel.okrkpi.product.application.evidence.EvidenceActor;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.infrastructure.listener.EvidenceObjectListener;
import vn.viettel.okrkpi.product.infrastructure.persistence.EvidenceSubmissionConfiguration;
import vn.viettel.okrkpi.product.infrastructure.persistence.OutboxEvidenceEvents;
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

  /** Người gọi và outbox giả (không cần registry) chỉ của ngữ cảnh này. */
  static class Beans {

    @Bean
    EvidenceCaller evidenceCaller() {
      return () -> Optional.of(new EvidenceActor(ACTOR, Set.of("member")));
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

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
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
              List.of("deliverable", "evidence", "evidence_version", "evidence_version_file")) {
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

  // ---------------------------------------------------------------- dữ liệu

  private UUID task(UUID lead) throws Exception {
    UUID id = UUID.randomUUID();
    in(
        taskTm,
        taskDataSource,
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket, code,"
                      + " title, task_group, source, assigner_id, due_date, unit,"
                      + " planned_quantity, dup_fingerprint, state_machine_version, status) VALUES (?, ?,"
                      + " ?, ?, ?, ?, 'Báo cáo tổng kết công tác quý', 'THUONG_XUYEN', 'KE_HOACH', ?,"
                      + " DATE '2027-03-20', 'báo cáo', 1, ?, 'mac-dinh@2.1.0', 'DANG_THUC_HIEN')")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-" + id.toString().substring(0, 8),
                  ASSIGNER,
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
    EvidenceObjectStored event =
        EvidenceObjectStored.newBuilder()
            .setObjectId(objectId)
            .setVersion(version)
            .setSha256(ByteBuffer.wrap(sha))
            .setSizeBytes(2048)
            .setContentType("application/pdf")
            .setBucketRef("evidence/ab/cd")
            .setE2ee(false)
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
    return as(() -> mvc.perform(request.principal(ACTOR::toString)).andReturn());
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
