package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.models.PathItem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.web.InMemoryJobRegistry;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.api.PlanVersionApprovedEvent;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService;
import vn.viettel.okrkpi.task.application.plan.port.PlanSignatures;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.infrastructure.listener.PlanSignatureConsumer;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxPlanApprovals;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxPlanNotices;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;

/**
 * Kiểm thử tích hợp CORE-02-09 trên PostgreSQL thật: người lập tự duyệt trả 403; phiên không xác
 * thực bước lên hoặc quá 15 phút trả 401; duyệt hợp lệ trả 202 kèm job; EVT-18 băm lệch không
 * duyệt; EVT-18 nhận hai lần chỉ duyệt một lần và phát một EVT-03; duyệt phiên bản 2 thì chỉ phiên
 * bản 2 hiện hành. SVC-10 và Kafka thay bằng cổng giả và bản ghi inbox tổng hợp như {@code
 * OrgReplicaConsumerIT}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.task.plan-approval.consumer.enabled=false"
    })
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanApprovalIT extends ContractConformanceTest {

  static final UUID AUTHOR = TestTaskCallers.DEFAULT_PERSON;
  static final UUID HEAD = UUID.fromString("01927a02-0000-7000-8000-00000000b209");
  static final int BUCKET = 20272;
  static final String TOKEN = "token-nguoi-duyet";

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PlanApprovalService approvals;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @Autowired
  @Qualifier("orgReplicaDataSource") private DataSource orgReplicaDataSource;

  @Autowired
  @Qualifier("orgReplicaTransactionManager") private PlatformTransactionManager orgReplicaTm;

  @MockitoBean private PlanSignatures signatures;
  @MockitoBean private OutboxPlanApprovals approvalEvents;
  @MockitoBean private OutboxPlanNotices planNotices;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID unit = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private PlanSignatureConsumer consumer;
  private long offset;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.TASKS;
  }

  @BeforeAll
  @SuppressWarnings({"unchecked", "rawtypes"})
  void seed() throws Exception {
    EventContracts.trustContractPackages();
    in(
        configDataSource,
        configTm,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO config.period (id, code, name, kind, flow, start_date, end_date,"
                        + " period_bucket, status, created_by, updated_by) VALUES (?, '2027-Q2',"
                        + " 'Quý II năm 2027', 'QUY', 'NHA_NUOC', DATE '2027-04-01', DATE"
                        + " '2027-06-30', ?, 'MO', ?, ?)")
                .params(period, BUCKET, AUTHOR, AUTHOR)
                .update());
    in(
        orgReplicaDataSource,
        orgReplicaTm,
        jdbc -> {
          for (UUID person : List.of(AUTHOR, HEAD)) {
            jdbc.sql(
                    "INSERT INTO org_replica.assignment_v (id, valid_from, person_id, org_unit_id,"
                        + " job_title_id, kind, branch, is_head, source_version, source_event_id)"
                        + " VALUES (?, DATE '2020-01-01', ?, ?, ?, 'BIEN_CHE', 'CHINH', true, 1,"
                        + " ?)")
                .params(UUID.randomUUID(), person, unit, UUID.randomUUID(), UUID.randomUUID())
                .update();
          }
          return null;
        });
    inTask(
        jdbc -> {
          for (String table : List.of("plan", "plan_version", "plan_item", "task")) {
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
    consumer = new PlanSignatureConsumer(approvals, processor, none, false);
  }

  @AfterEach
  void resetCaller() {
    TestTaskCallers.reset();
    InMemoryJobRegistry.clearActorOverride();
  }

  @Test
  void duyetBonMatXacThucBuocLenVaKySoMotLan() throws Exception {
    String id = submittedPlan();
    UUID v1 = versionId(id, 1);
    byte[] hash = contentHash(v1);

    // Người lập tự duyệt: 403 dù đã xác thực bước lên.
    as(AUTHOR, "aal2", Instant.now());
    MvcResult self = perform(approve(id, 1, "\"2\""));
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("TASK-403-SAME-AUTHOR");

    // Lãnh đạo đơn vị chưa xác thực bước lên hoặc đã quá 15 phút: 401.
    as(HEAD, "aal1", Instant.now());
    assertThat(perform(approve(id, 1, "\"2\"")).getResponse().getStatus()).isEqualTo(401);
    as(HEAD, "aal2", Instant.now().minus(16, ChronoUnit.MINUTES));
    assertThat(perform(approve(id, 1, "\"2\"")).getResponse().getStatus()).isEqualTo(401);

    // Duyệt hợp lệ: 202 kèm job, yêu cầu ký tạo trên SVC-10 với token người duyệt.
    UUID signRequest = UUID.randomUUID();
    when(signatures.request(any())).thenReturn(signRequest);
    as(HEAD, "aal2", Instant.now());
    MvcResult started = perform(approve(id, 1, "\"2\""));
    assertThat(started.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(started, "/plans/{id}/versions/{v}/approve", PathItem.HttpMethod.POST);
    assertThat(started.getResponse().getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/api/v1/jobs/" + signRequest);
    ArgumentCaptor<PlanSignatures.Request> request =
        ArgumentCaptor.forClass(PlanSignatures.Request.class);
    verify(signatures).request(request.capture());
    assertThat(request.getValue().versionId()).isEqualTo(v1);
    assertThat(request.getValue().approver()).isEqualTo(HEAD);
    assertThat(request.getValue().callerToken()).isEqualTo(TOKEN);
    assertThat(request.getValue().signMethod()).isEqualTo("MYSIGN");

    // Job theo dõi được bởi chính người duyệt; nguồn ký trả băm đã khoá và người được ký.
    InMemoryJobRegistry.setActorOverride(new ActorId(HEAD));
    MvcResult job = perform(get("/api/v1/jobs/" + signRequest));
    assertThat(job.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(job).get("resultLocation").asText())
        .isEqualTo("/api/v1/esign/requests/" + signRequest);
    MvcResult source =
        perform(get("/internal/v1/esign-sources/task/" + v1).param("purpose", "QUYET_DINH"));
    assertThat(source.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(source).get("ready").asBoolean()).isTrue();
    assertThat(body(source).get("signers").get(0).asText()).isEqualTo(HEAD.toString());
    assertThat(body(source).get("documents").get(0).get("sha256").asText())
        .isEqualTo(HexFormat.of().formatHex(hash));

    // Băm lệch: không duyệt.
    assertThat(deliver(UUID.randomUUID(), signRequest, v1, new byte[32]))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(status(v1)).isEqualTo("CHO_DUYET");

    // EVT-18 đúng nhận hai lần (cùng mã sự kiện, rồi mã khác): duyệt một lần, một EVT-03.
    UUID event = UUID.randomUUID();
    assertThat(deliver(event, signRequest, v1, hash)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(deliver(event, signRequest, v1, hash)).isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(deliver(UUID.randomUUID(), signRequest, v1, hash)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(status(v1)).isEqualTo("DA_DUYET");
    ArgumentCaptor<PlanVersionApprovedEvent> approved =
        ArgumentCaptor.forClass(PlanVersionApprovedEvent.class);
    verify(approvalEvents, times(1)).approved(approved.capture());
    assertThat(approved.getValue().versionId()).isEqualTo(v1);
    assertThat(approved.getValue().approvedBy()).isEqualTo(HEAD);
    assertThat(approved.getValue().signRequestId()).isEqualTo(signRequest);
    assertThat(approved.getValue().supersededVersionId()).isNull();
    verify(planNotices).approved(eq(tenant), any(), eq("Quý II năm 2027"), eq(HEAD));

    // Phiên bản 2 được duyệt thì chỉ phiên bản 2 hiện hành, EVT-03 ghi phiên bản bị thay thế.
    UUID v2 = UUID.randomUUID();
    inTask(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO task.plan_version (id, created_by, updated_by, period_id,"
                        + " period_bucket, plan_id, version_no, status, change_reason) VALUES (?,"
                        + " ?, ?, ?, ?, ?, 2, 'NHAP', 'Bổ sung nhiệm vụ')")
                .params(v2, AUTHOR, AUTHOR, period, BUCKET, UUID.fromString(id))
                .update());
    TestTaskCallers.reset();
    // Điều chỉnh (CORE-02-10): dòng mới ghi lý do, dòng của phiên bản 1 bỏ kèm lý do.
    List<Map<String, Object>> adjusted = lines(3);
    adjusted.forEach(l -> l.put("changeReason", "Thay theo chỉ đạo mới"));
    List<Map<String, Object>> removed =
        inTask(
            jdbc ->
                jdbc
                    .sql("SELECT id FROM task.plan_item WHERE plan_version_id = ?")
                    .params(v1)
                    .query(UUID.class)
                    .list()
                    .stream()
                    .map(
                        item ->
                            Map.<String, Object>of(
                                "originItemId", item, "changeReason", "Không còn phù hợp"))
                    .toList());
    MockHttpServletRequestBuilder saveV2 =
        put("/api/v1/plans/" + id + "/versions/2")
            .header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items", adjusted, "removedItems", removed)));
    assertThat(perform(saveV2).getResponse().getStatus()).isEqualTo(200);
    score(v2);
    assertThat(perform(submit(id, 2, "\"1\"")).getResponse().getStatus()).isEqualTo(200);
    UUID signRequest2 = UUID.randomUUID();
    when(signatures.request(any())).thenReturn(signRequest2);
    as(HEAD, "aal2", Instant.now());
    assertThat(perform(approve(id, 2, "\"2\"")).getResponse().getStatus()).isEqualTo(202);
    assertThat(deliver(UUID.randomUUID(), signRequest2, v2, contentHash(v2)))
        .isEqualTo(InboxOutcome.PROCESSED);
    List<UUID> current =
        inTask(
            jdbc ->
                jdbc.sql("SELECT id FROM task.plan_version WHERE plan_id = ? AND is_current")
                    .params(UUID.fromString(id))
                    .query(UUID.class)
                    .list());
    assertThat(current).containsExactly(v2);
    verify(approvalEvents, times(2)).approved(approved.capture());
    assertThat(approved.getValue().versionId()).isEqualTo(v2);
    assertThat(approved.getValue().supersededVersionId()).isEqualTo(v1);
  }

  /** Kế hoạch mới do người lập soạn, có điểm chuẩn cho dòng ngoài danh mục, đã gửi duyệt. */
  private String submittedPlan() throws Exception {
    MvcResult created =
        perform(
            post("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("periodId", period, "unitId", unit))));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    String id = body(created).get("id").asText();
    assertThat(perform(save(id, 1, "\"0\"", lines(2))).getResponse().getStatus()).isEqualTo(200);
    score(versionId(id, 1));
    MvcResult submitted = perform(submit(id, 1, "\"1\""));
    assertThat(submitted.getResponse().getStatus())
        .as(submitted.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(200);
    return id;
  }

  private void score(UUID version) throws Exception {
    inTask(
        jdbc ->
            jdbc.sql(
                    "UPDATE task.plan_item SET difficulty = 3, standard_score = CASE WHEN"
                        + " task_group = 'THUONG_XUYEN' THEN 30 ELSE 70 / (SELECT count(*) FROM"
                        + " task.plan_item x WHERE x.plan_version_id = ? AND x.task_group ="
                        + " 'THEN_CHOT') END WHERE plan_version_id = ?")
                .params(version, version)
                .update());
  }

  private UUID versionId(String plan, int no) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT id FROM task.plan_version WHERE plan_id = ? AND version_no = ?")
                .params(UUID.fromString(plan), no)
                .query(UUID.class)
                .single());
  }

  private byte[] contentHash(UUID version) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT content_hash FROM task.plan_version WHERE id = ?")
                .params(version)
                .query(byte[].class)
                .single());
  }

  private String status(UUID version) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT status FROM task.plan_version WHERE id = ?")
                .params(version)
                .query(String.class)
                .single());
  }

  private static void as(UUID person, String acr, Instant authTime) {
    TestTaskCallers.current = new TaskActor(person, Set.of("leader"));
    TestTaskCallers.acr = acr;
    TestTaskCallers.authTime = authTime.getEpochSecond();
  }

  private InboxOutcome deliver(UUID eventId, UUID signRequest, UUID version, byte[] hash) {
    KySoDaHoanTat payload =
        KySoDaHoanTat.newBuilder()
            .setSignRequestId(signRequest)
            .setPurpose("QUYET_DINH")
            .setSourceModule("task")
            .setSourceRefId(version)
            .setItems(List.of(new SignedItem(ByteBuffer.wrap(hash), null)))
            .setCertSha256(ByteBuffer.wrap(new byte[32]))
            .setSignedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
            .setAggregateVersion(1)
            .build();
    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(
            EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
            0,
            offset++,
            tenant.value() + ":" + signRequest,
            encode(payload));
    header(record, OutboxHeaders.EVENT_ID, eventId.toString());
    header(record, OutboxHeaders.TENANT_ID, tenant.value().toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, "1");
    header(record, OutboxHeaders.CE_TYPE, "KySoDaHoanTat");
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    header(record, OutboxHeaders.ACTOR_ID, HEAD.toString());
    return consumer.processor().process(consumer.endpoint(), record, () -> {});
  }

  private static void header(ConsumerRecord<String, byte[]> record, String key, String value) {
    record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] encode(KySoDaHoanTat payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<>(KySoDaHoanTat.class).write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static KySoDaHoanTat decode(byte[] data) {
    try {
      return new SpecificDatumReader<>(KySoDaHoanTat.class)
          .read(null, DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(data), null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private MockHttpServletRequestBuilder approve(String id, int v, String ifMatch) throws Exception {
    return post("/api/v1/plans/" + id + "/versions/" + v + "/approve")
        .header("If-Match", ifMatch)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("signMethod", "MYSIGN")));
  }

  private MockHttpServletRequestBuilder submit(String id, int v, String ifMatch) {
    return post("/api/v1/plans/" + id + "/versions/" + v + "/submit").header("If-Match", ifMatch);
  }

  private MockHttpServletRequestBuilder save(
      String id, int v, String ifMatch, List<Map<String, Object>> items) throws Exception {
    return put("/api/v1/plans/" + id + "/versions/" + v)
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("items", items)));
  }

  private List<Map<String, Object>> lines(int n) {
    List<Map<String, Object>> out = new ArrayList<>(n);
    for (int i = 1; i <= n; i++) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("title", "Công việc " + i);
      line.put("taskGroup", i == 1 ? "THUONG_XUYEN" : "THEN_CHOT");
      line.put("leadAssignee", Map.of("kind", "UNIT", "id", unit, "name", "Văn phòng"));
      line.put("productName", "Báo cáo " + i);
      line.put("quantity", BigDecimal.ONE);
      line.put("unit", "báo cáo");
      line.put("dueDate", LocalDate.of(2027, 6, 1).toString());
      out.add(line);
    }
    return out;
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    return in(taskDataSource, taskTm, work);
  }

  private <T> T in(DataSource ds, PlatformTransactionManager tm, Function<JdbcClient, T> work)
      throws Exception {
    JdbcClient jdbc = JdbcClient.create(ds);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    UUID person = TestTaskCallers.current.personId();
    return TenantContext.call(
        tenant,
        () ->
            mvc.perform(
                    request
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .principal(person::toString))
                .andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
