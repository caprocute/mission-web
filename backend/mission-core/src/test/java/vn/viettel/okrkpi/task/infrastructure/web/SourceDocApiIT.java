package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
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
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.application.document.IncomingDocService;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Extraction;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Fields;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Suggestion;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocContents;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Content;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Paragraph;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.plan.PlanSchedule;
import vn.viettel.okrkpi.task.infrastructure.listener.IncomingDocConsumer;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskAssignedEvents;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskReminderNotices;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;

/**
 * Văn bản đến và nhiệm vụ từ văn bản (CORE-02-16) trên PostgreSQL thật: EVT-24 nhận hai lần chỉ một
 * văn bản; văn bản SVC-14 không trích được vẫn đọc được và tạo thủ công được; xác nhận cùng một
 * đoạn lần hai 409; gộp vào nhiệm vụ cũ; giao theo lô chặn nhiệm vụ chưa xác nhận bằng MSG-05-08;
 * đơn vị khác 404; bỏ qua có lý do, bỏ qua lần hai 409.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SourceDocApiIT.SourceDocTestConfig.class)
class SourceDocApiIT extends ContractConformanceTest {

  static final UUID BOSS = TestTaskCallers.DEFAULT_PERSON;
  static final UUID OUTSIDER = UUID.fromString("01927a02-0000-7000-8000-00000000a216");
  static final UUID HEAD = UUID.fromString("01927a02-0000-7000-8000-00000000b216");
  static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-000000001216");
  static final UUID OTHER_UNIT = UUID.fromString("01927a02-0000-7000-8000-000000002216");
  static final UUID CO_UNIT = UUID.fromString("01927a02-0000-7000-8000-000000003216");
  static final LocalDate QUARTER_START = quarterStart();
  static final int BUCKET = PlanSchedule.bucketOf(QUARTER_START);

  /** Kết quả trích của SVC-14 giả theo mã văn bản; văn bản vắng mặt coi như đang trích. */
  static final Map<UUID, Extraction> EXTRACTIONS = new ConcurrentHashMap<>();

  static final Map<UUID, Content> CONTENTS = new ConcurrentHashMap<>();

  static LocalDate quarterStart() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    return today.withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
  }

  /** Outbox với bộ tuần tự giả; bản sao tổ chức cố định; SVC-13, SVC-14 giả. */
  static class SourceDocTestConfig {

    private static OutboxWriter writer(DataSource ds, ObjectMapper json) {
      return new OutboxWriter(
          new JdbcTemplate(ds),
          "task",
          (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
          json,
          Clock.systemUtc());
    }

    @Bean
    @Primary
    TaskReminderNotices sourceDocTestNotices(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskReminderNotices(writer(ds, json), json);
    }

    @Bean
    @Primary
    TaskAssignedEvents sourceDocTestAssigned(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskAssignedEvents(writer(ds, json), json);
    }

    @Bean
    IncomingDocContents sourceDocTestContents() {
      return (tenant, id) -> Optional.ofNullable(CONTENTS.get(id));
    }

    @Bean
    DocExtractions sourceDocTestExtractions() {
      return new DocExtractions() {
        @Override
        public Optional<Extraction> find(TenantId tenant, UUID docId, String authorization) {
          return Optional.ofNullable(EXTRACTIONS.get(docId));
        }

        @Override
        public void accept(
            TenantId tenant,
            UUID suggestionId,
            boolean edited,
            List<String> editedFields,
            String targetRef,
            String authorization,
            String idempotencyKey) {}
      };
    }

    @Bean
    @Primary
    OrgDirectoryApi sourceDocTestOrgDirectory() {
      return new OrgDirectoryApi() {
        @Override
        public List<AssignmentView> assignmentsOf(TenantId tenant, UUID personId, LocalDate on) {
          UUID unit = OUTSIDER.equals(personId) ? OTHER_UNIT : UNIT;
          return List.of(
              new AssignmentView(
                  UUID.randomUUID(),
                  personId,
                  unit,
                  null,
                  null,
                  "CHINH",
                  "CHINH",
                  false,
                  on,
                  null));
        }

        @Override
        public List<UUID> headsOf(TenantId tenant, UUID orgUnitId, LocalDate on) {
          return List.of(HEAD);
        }

        @Override
        public Optional<UUID> parentOf(TenantId tenant, OrgTree tree, UUID orgUnitId) {
          return Optional.empty();
        }

        @Override
        public List<UUID> descendantsOf(
            TenantId tenant, OrgTree tree, UUID orgUnitId, int maxDepth) {
          return List.of();
        }

        @Override
        public List<UUID> ancestorsOf(TenantId tenant, OrgTree tree, UUID orgUnitId) {
          return List.of();
        }

        @Override
        public boolean isDescendant(TenantId tenant, OrgTree tree, UUID ancestor, UUID candidate) {
          return ancestor.equals(candidate);
        }
      };
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private IncomingDocService incomingDocs;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private IncomingDocConsumer consumer;
  private long offset;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.TASKS;
  }

  @BeforeAll
  @SuppressWarnings({"unchecked", "rawtypes"})
  void seed() throws Exception {
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .execute(
                    s ->
                        JdbcClient.create(configDataSource)
                            .sql(
                                "INSERT INTO config.period (id, code, name, kind, flow,"
                                    + " start_date, end_date, period_bucket, status, created_by,"
                                    + " updated_by) VALUES (?, 'Q-VB', 'Quý hiện hành', 'QUY',"
                                    + " 'NHA_NUOC', ?, ?, ?, 'MO', ?, ?)")
                            .params(
                                period,
                                QUARTER_START,
                                QUARTER_START.plusMonths(3).minusDays(1),
                                BUCKET,
                                BOSS,
                                BOSS)
                            .update()));
    inTask(
        jdbc -> {
          for (String table :
              List.of(
                  "task", "task_assignee", "task_state_log", "task_progress", "task_source_doc")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
    KafkaOperations<String, byte[]> kafka = mock(KafkaOperations.class);
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new JdbcTemplate(taskDataSource),
            taskTm,
            "task",
            (topic, headers, data) -> decode(data),
            new InboxRetryDlqPublisher(kafka),
            new InboxMetrics(new SimpleMeterRegistry()),
            new InboxProperties.Retry(null, null, null, null),
            Clock.systemUtc());
    ObjectProvider none =
        new StaticListableBeanFactory()
            .getBeanProvider(ConcurrentKafkaListenerContainerFactory.class);
    consumer = new IncomingDocConsumer(incomingDocs, processor, none, false);
  }

  @AfterEach
  void resetCaller() {
    TestTaskCallers.reset();
  }

  @Test
  void nhanHaiLanMotVanBanXacNhanTrungDoan409VaGop() throws Exception {
    UUID doc = UUID.randomUUID();
    CONTENTS.put(doc, content("C1"));
    UUID s2 = UUID.randomUUID();
    UUID s3 = UUID.randomUUID();
    EXTRACTIONS.put(doc, new Extraction("XONG", List.of(suggestion(s2, 2), suggestion(s3, 3))));
    UUID event = UUID.randomUUID();
    assertThat(deliver(event, doc, 1)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(deliver(event, doc, 1)).isNotEqualTo(InboxOutcome.PROCESSED);
    deliver(UUID.randomUUID(), doc, 1);
    assertThat(docs(doc)).isEqualTo(1);

    MvcResult list = perform(get("/api/v1/tasks/source-docs").param("status", "CHO_XU_LY"));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(list).get("items").toString()).contains(doc.toString());

    MvcResult detail = perform(get("/api/v1/tasks/source-docs/" + doc));
    assertThat(detail.getResponse().getStatus()).isEqualTo(200);
    JsonNode d = body(detail);
    assertThat(d.get("pendingCount").asInt()).isEqualTo(2);
    assertThat(d.get("paragraphs").get(0).get("assignment").asBoolean()).isFalse();
    assertThat(d.get("paragraphs").get(1).get("assignment").asBoolean()).isTrue();

    MvcResult created = perform(fromDocument(doc, s2, 2, null));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    JsonNode task = body(created);
    assertThat(task.get("decision").asText()).isEqualTo("DA_XAC_NHAN");
    assertThat(task.get("taskStatus").asText()).isEqualTo("NHAP");
    String taskId = task.get("taskId").asText();
    assertThat(sourceOf(UUID.fromString(taskId))).isEqualTo("VAN_BAN");

    MvcResult again = perform(fromDocument(doc, s2, 2, "Vẫn tạo"));
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("TASK-409-DUPLICATE-PARAGRAPH");

    MvcResult merged =
        perform(
            write(post("/api/v1/tasks/" + taskId + "/source-docs"))
                .content(
                    json.writeValueAsString(
                        Map.of("sourceDocId", doc, "suggestionId", s3, "paragraphNo", 3))));
    assertThat(merged.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(merged).get("decision").asText()).isEqualTo("DA_GOP");

    JsonNode after = body(perform(get("/api/v1/tasks/source-docs/" + doc)));
    assertThat(after.get("status").asText()).isEqualTo("DA_XU_LY");
    assertThat(after.get("pendingCount").asInt()).isZero();

    as(OUTSIDER);
    assertThat(perform(get("/api/v1/tasks/source-docs/" + doc)).getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void khongTrichDuocVanDocVaTaoThuCongGiaoLoChanChuaXacNhan() throws Exception {
    UUID doc = UUID.randomUUID();
    CONTENTS.put(doc, content("C1"));
    EXTRACTIONS.put(doc, new Extraction("KHONG_TRICH_DUOC", List.of()));
    assertThat(deliver(UUID.randomUUID(), doc, 1)).isEqualTo(InboxOutcome.PROCESSED);

    JsonNode d = body(perform(get("/api/v1/tasks/source-docs/" + doc)));
    assertThat(d.get("extraction").asText()).isEqualTo("KHONG_TRICH_DUOC");
    assertThat(d.get("paragraphs")).hasSize(3);

    MvcResult created =
        perform(
            fromDocument(
                doc,
                UUID.randomUUID(),
                2,
                null,
                "Tổ chức hội nghị tập huấn an toàn thông tin cho cán bộ"));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    UUID confirmed = UUID.fromString(body(created).get("taskId").asText());

    UUID unconfirmed = draftTask();
    MvcResult batch =
        perform(
            write(post("/api/v1/tasks/assign-batch"))
                .content(
                    json.writeValueAsString(Map.of("taskIds", List.of(confirmed, unconfirmed)))));
    assertThat(batch.getResponse().getStatus()).isEqualTo(200);
    JsonNode items = body(batch).get("results");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("taskId").asText()).isEqualTo(confirmed.toString());
    // Thẻ trích chưa có số lượng, tiêu chuẩn, minh chứng: phải bổ sung (BR-05-03) mới giao được.
    assertThat(items.get(0).get("assigned").asBoolean()).isFalse();
    assertThat(items.get(0).get("message").asText())
        .contains("chưa đủ điều kiện để giao: Nhiệm vụ phải có");
    assertThat(items.get(1).get("assigned").asBoolean()).isFalse();
    assertThat(items.get(1).get("message").asText())
        .contains("chưa được xác nhận nên chưa giao được");
  }

  @Test
  void boQuaCoLyDoVaBoQuaLanHai409() throws Exception {
    UUID doc = UUID.randomUUID();
    CONTENTS.put(doc, content("C1"));
    assertThat(deliver(UUID.randomUUID(), doc, 1)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(body(perform(get("/api/v1/tasks/source-docs/" + doc))).get("extraction").asText())
        .isEqualTo("DANG_TRICH");

    MockHttpServletRequestBuilder dismiss =
        write(post("/api/v1/tasks/source-docs/" + doc + "/dismiss"))
            .content(json.writeValueAsString(Map.of("reason", "Văn bản để biết")));
    MvcResult ok = perform(dismiss);
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(ok).get("status").asText()).isEqualTo("DA_BO_QUA");
    MvcResult twice =
        perform(
            write(post("/api/v1/tasks/source-docs/" + doc + "/dismiss"))
                .content(json.writeValueAsString(Map.of("reason", "Lần hai"))));
    assertThat(twice.getResponse().getStatus()).isEqualTo(409);
  }

  // ───────── Dữ liệu ─────────

  static Content content(String classification) {
    return new Content(
        UNIT,
        "EdXML",
        "ỦY BAN NHÂN DÂN TỈNH",
        "Về việc triển khai chương trình chuyển đổi số",
        "Căn cứ Nghị quyết của Tỉnh ủy",
        List.of(
            new Paragraph(1, "Thực hiện chỉ đạo của Tỉnh ủy, Ủy ban nhân dân tỉnh yêu cầu:"),
            new Paragraph(2, "Giao Sở Thông tin và Truyền thông chủ trì xây dựng kế hoạch."),
            new Paragraph(3, "Sở Thông tin và Truyền thông tổng hợp báo cáo kết quả.")),
        "Sở Thông tin và Truyền thông",
        classification);
  }

  static Fields fields(String title) {
    return new Fields(
        title,
        UNIT,
        "Sở Thông tin và Truyền thông",
        List.of(),
        List.of(),
        QUARTER_START.plusMonths(2),
        "Kế hoạch được phê duyệt",
        null,
        null,
        null,
        null);
  }

  static Suggestion suggestion(UUID id, int paragraph) {
    Fields f = fields("Xây dựng kế hoạch đoạn " + paragraph);
    return new Suggestion(
        id, paragraph, new BigDecimal("0.92"), "CHO_XAC_NHAN", f, f, List.of(), null);
  }

  private MockHttpServletRequestBuilder fromDocument(
      UUID doc, UUID suggestion, int paragraph, String reason) {
    return fromDocument(
        doc, suggestion, paragraph, reason, "Xây dựng kế hoạch chuyển đổi số đoạn " + paragraph);
  }

  private MockHttpServletRequestBuilder fromDocument(
      UUID doc, UUID suggestion, int paragraph, String reason, String title) {
    ObjectNode f = json.createObjectNode();
    f.put("title", title);
    f.put("leadUnitId", UNIT.toString());
    f.put("leadUnitName", "Sở Thông tin và Truyền thông");
    f.putArray("coUnits")
        .addObject()
        .put("kind", "UNIT")
        .put("id", CO_UNIT.toString())
        .put("name", "");
    f.put("dueDate", QUARTER_START.plusMonths(2).toString());
    f.put("productName", "Kế hoạch được phê duyệt");
    ObjectNode req = json.createObjectNode();
    req.put("sourceDocId", doc.toString());
    req.put("suggestionId", suggestion.toString());
    req.put("paragraphNo", paragraph);
    req.set("fields", f);
    req.putArray("editedFields").add("dueDate");
    if (reason != null) {
      req.put("similarReason", reason);
    }
    return write(post("/api/v1/tasks/from-document")).content(req.toString());
  }

  /** Nhiệm vụ nháp thường (không từ văn bản). */
  private UUID draftTask() throws Exception {
    ObjectNode d = json.createObjectNode();
    d.put("status", "NHAP");
    d.put("periodId", period.toString());
    d.put("unitName", "");
    d.put("assignerId", BOSS.toString());
    d.put("assignerName", "");
    d.put("source", "DOT_XUAT");
    d.put("title", "Rà soát quy chế");
    d.put("taskGroup", "THUONG_XUYEN");
    d.put("priority", "TRUNG_BINH");
    d.put("leadPersonId", HEAD.toString());
    d.putArray("coAssignees");
    d.putArray("milestones");
    d.put("dueDate", QUARTER_START.plusMonths(2).toString());
    d.put("productName", "Báo cáo rà soát");
    d.put("basis", "Chỉ đạo của thủ trưởng");
    MvcResult r = perform(write(post("/api/v1/tasks")).content(d.toString()));
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    return UUID.fromString(body(r).get("id").asText());
  }

  private InboxOutcome deliver(UUID eventId, UUID doc, int version) {
    VanBanChiDaoDaNhan payload =
        VanBanChiDaoDaNhan.newBuilder()
            .setDocumentId(doc)
            .setConnectorCode("VOFFICE")
            .setExternalId("EXT-" + doc.toString().substring(0, 8))
            .setDocNumber("12/CT-UBND")
            .setIssuedDate(LocalDate.of(2026, 10, 5))
            .setIssuer("Ủy ban nhân dân tỉnh")
            .setSummary("Về việc triển khai chương trình chuyển đổi số")
            .setObjectKey("docs/" + doc + ".xml")
            .setAggregateVersion(version)
            .build();
    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(
            EventTopics.EVT_24_VAN_BAN_CHI_DAO_DA_NHAN,
            0,
            offset++,
            tenant.value() + ":" + doc,
            encode(payload));
    header(record, OutboxHeaders.EVENT_ID, eventId.toString());
    header(record, OutboxHeaders.TENANT_ID, tenant.value().toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, String.valueOf(version));
    header(record, OutboxHeaders.CE_TYPE, "VanBanChiDaoDaNhan");
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    return consumer.processor().process(consumer.endpoint(), record, () -> {});
  }

  private static void header(ConsumerRecord<String, byte[]> record, String key, String value) {
    record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] encode(VanBanChiDaoDaNhan payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<>(VanBanChiDaoDaNhan.class).write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static VanBanChiDaoDaNhan decode(byte[] data) {
    try {
      return new SpecificDatumReader<>(VanBanChiDaoDaNhan.class)
          .read(null, DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(data), null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void as(UUID person) {
    TestTaskCallers.current = new TaskActor(person, Set.of("manager"));
  }

  private long docs(UUID doc) throws Exception {
    return this.<Long>inTask(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM task.incoming_doc WHERE id = ?")
                .param(doc)
                .query(Long.class)
                .single());
  }

  private String sourceOf(UUID task) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT source FROM task.task WHERE id = ?")
                .param(task)
                .query(String.class)
                .single());
  }

  private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
    return b.header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON);
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(taskDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(taskTm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            mvc.perform(request.principal(TestTaskCallers.current.personId()::toString))
                .andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
