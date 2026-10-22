package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import vn.viettel.okrkpi.task.application.direct.TaskDirectionService;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.infrastructure.listener.LeadMovedConsumer;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskAssignedEvents;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskReminderNotices;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Nhắc việc, giao lại và người chủ trì chuyển công tác (CORE-02-25) trên PostgreSQL thật: nhắc lặp
 * cùng {@code Idempotency-Key} chỉ một EVT-21, nhắc lại trong 24 giờ 429, người ngoài phạm vi 403,
 * giao lại cho người nghiệm thu bị chặn đúng {@code rule}, {@code If-Match} lệch 409, giao lại phát
 * EVT-04; EVT-15 kết thúc phân công của người chủ trì có hai nhiệm vụ đang làm thì người giao nhận
 * đúng một thông báo, nhận trùng không báo lại.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskDirectionApiIT.DirectionTestConfig.class)
class TaskDirectionApiIT extends ContractConformanceTest {

  static final UUID BOSS = TestTaskCallers.DEFAULT_PERSON;
  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000c225");
  static final UUID LEAD = UUID.fromString("01927a02-0000-7000-8000-00000000d225");
  static final UUID NEW_LEAD = UUID.fromString("01927a02-0000-7000-8000-00000000e225");
  static final UUID ACCEPTOR = UUID.fromString("01927a02-0000-7000-8000-00000000f225");
  static final UUID OUTSIDER = UUID.fromString("01927a02-0000-7000-8000-00000000a225");
  static final UUID MOVED = UUID.fromString("01927a02-0000-7000-8000-00000000b225");
  static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-000000001225");
  static final UUID OTHER_UNIT = UUID.fromString("01927a02-0000-7000-8000-000000002225");
  static final int BUCKET = 20283;
  static final String TOPIC_NOTICE = "task_reminder";

  /** Outbox với bộ tuần tự giả; bản sao tổ chức cố định. */
  static class DirectionTestConfig {

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
    TaskReminderNotices directionTestNotices(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskReminderNotices(writer(ds, json), json);
    }

    @Bean
    @Primary
    TaskAssignedEvents directionTestAssigned(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskAssignedEvents(writer(ds, json), json);
    }

    @Bean
    @Primary
    OrgDirectoryApi directionTestOrgDirectory() {
      return new OrgDirectoryApi() {
        @Override
        public List<AssignmentView> assignmentsOf(TenantId tenant, UUID personId, LocalDate on) {
          if (MOVED.equals(personId)) {
            return List.of();
          }
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
          return List.of(BOSS);
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
  @Autowired private TaskDirectionService directions;

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
  private LeadMovedConsumer consumer;
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
                                    + " updated_by) VALUES (?, 'Q3-2029-CD', 'Quý III năm 2029',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2029-07-01', DATE '2029-09-30',"
                                    + " ?, 'MO', ?, ?)")
                            .params(period, BUCKET, BOSS, BOSS)
                            .update()));
    inTask(
        jdbc -> {
          for (String table : List.of("task", "task_assignee", "task_state_log")) {
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
    consumer = new LeadMovedConsumer(directions, processor, none, false);
  }

  @AfterEach
  void resetCaller() {
    TestTaskCallers.reset();
  }

  @Test
  void nhacMotLanTrong24GioVaNguoiNgoaiPhamVi403() throws Exception {
    UUID task = task("DANG_THUC_HIEN", LEAD);
    as(BOSS);
    String key = UUID.randomUUID().toString();
    assertThat(status(remind(task, key, "Đề nghị khẩn trương"))).isEqualTo(202);
    assertThat(status(remind(task, key, "Đề nghị khẩn trương"))).isEqualTo(202);
    assertThat(events(task)).isEqualTo(1);
    MvcResult again = perform(remind(task, UUID.randomUUID().toString(), "Nhắc lần hai"));
    assertThat(again.getResponse().getStatus()).isEqualTo(429);
    assertThat(body(again).get("code").asText()).isEqualTo("TASK-429-REMINDED");
    assertThat(events(task)).isEqualTo(1);

    as(OUTSIDER);
    assertThat(status(remind(task, UUID.randomUUID().toString(), null))).isEqualTo(403);
    assertThat(status(reassign(task, "\"0\"", NEW_LEAD))).isEqualTo(403);
  }

  @Test
  void giaoLaiChanNguoiNghiemThuLechPhienBanVaPhatEvt04() throws Exception {
    UUID task = task("DANG_THUC_HIEN", LEAD);
    as(BOSS);
    MvcResult acceptor = perform(reassign(task, "\"0\"", ACCEPTOR));
    assertThat(acceptor.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(acceptor).get("errors").get(0).get("rule").asText()).isEqualTo("BR-05-02");
    assertThat(status(reassign(task, "\"7\"", NEW_LEAD))).isEqualTo(409);

    MvcResult ok = perform(reassign(task, "\"0\"", NEW_LEAD));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(ok.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(leads(task)).containsExactly(NEW_LEAD);
    // EVT-04 kèm EVT-21 tới chủ trì mới (MSG-05-14) và EVT-21 báo chủ trì cũ (MSG-16-03).
    assertThat(events(task)).isEqualTo(3);
    assertThat(body(ok).get("stateLog").toString()).contains("GIAO_LAI");
  }

  @Test
  void chuTriChuyenCongTacBaoNguoiGiaoMotLan() throws Exception {
    UUID first = task("DANG_THUC_HIEN", MOVED);
    UUID second = task("DA_GIAO", MOVED);
    UUID event = UUID.randomUUID();
    assertThat(deliver(event)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(events(first) + events(second)).isEqualTo(1);
    assertThat(marks(first) + marks(second)).isEqualTo(2);
    assertThat(deliver(event)).isNotEqualTo(InboxOutcome.PROCESSED);
    assertThat(events(first) + events(second)).isEqualTo(1);
  }

  private InboxOutcome deliver(UUID eventId) {
    UUID assignment = UUID.randomUUID();
    PhanCongDaThayDoi payload =
        PhanCongDaThayDoi.newBuilder()
            .setAssignmentId(assignment)
            .setPersonId(MOVED)
            .setUnitId(UNIT)
            .setPositionId(null)
            .setJobTitleId(UUID.randomUUID())
            .setKind("CHINH_THUC")
            .setIsPrimary(true)
            .setIsHead(false)
            .setValidFrom(LocalDate.of(2029, 1, 1))
            .setValidTo(LocalDate.of(2029, 8, 1))
            .setSourceVersion(2L)
            .setAggregateVersion(2)
            .build();
    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(
            EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
            0,
            offset++,
            tenant.value() + ":" + assignment,
            encode(payload));
    header(record, OutboxHeaders.EVENT_ID, eventId.toString());
    header(record, OutboxHeaders.TENANT_ID, tenant.value().toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, "2");
    header(record, OutboxHeaders.CE_TYPE, "PhanCongDaThayDoi");
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    return consumer.processor().process(consumer.endpoint(), record, () -> {});
  }

  private static void header(ConsumerRecord<String, byte[]> record, String key, String value) {
    record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] encode(PhanCongDaThayDoi payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<>(PhanCongDaThayDoi.class).write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static PhanCongDaThayDoi decode(byte[] data) {
    try {
      return new SpecificDatumReader<>(PhanCongDaThayDoi.class)
          .read(null, DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(data), null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void as(UUID person) {
    TestTaskCallers.current = new TaskActor(person, Set.of("leader"));
  }

  private MockHttpServletRequestBuilder remind(UUID task, String key, String note)
      throws Exception {
    return post("/api/v1/tasks/" + task + "/reminders")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(note == null ? Map.of() : Map.of("note", note)));
  }

  private MockHttpServletRequestBuilder reassign(UUID task, String ifMatch, UUID lead)
      throws Exception {
    return post("/api/v1/tasks/" + task + "/reassign")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            json.writeValueAsString(Map.of("leadPersonId", lead, "reason", "Điều chỉnh nhân sự")));
  }

  private long events(UUID task) throws Exception {
    return this.<Long>inTask(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM task.outbox_event WHERE aggregate_id = ?")
                .param(task)
                .query(Long.class)
                .single());
  }

  private long marks(UUID task) throws Exception {
    return this.<Long>inTask(
        jdbc ->
            jdbc.sql(
                    "SELECT count(*) FROM task.task_state_log WHERE task_id = ?"
                        + " AND transition = 'CAN_GIAO_LAI'")
                .param(task)
                .query(Long.class)
                .single());
  }

  private List<UUID> leads(UUID task) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql(
                    "SELECT person_id FROM task.task_assignee WHERE task_id = ? AND role = 'CHU_TRI'"
                        + " AND removed_at IS NULL")
                .param(task)
                .query(UUID.class)
                .list());
  }

  private UUID task(String status, UUID lead) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, acceptor_id, org_unit_id,"
                      + " due_date, unit, dup_fingerprint, state_machine_version, status)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'Báo cáo chuyên đề', 'THUONG_XUYEN',"
                      + " 'KE_HOACH', ?, ?, ?, DATE '2029-09-15', 'báo cáo', ?, 'mac-dinh@2.1.0',"
                      + " ?)")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-2029-" + id.toString().substring(0, 8),
                  ASSIGNER,
                  ACCEPTOR,
                  UNIT,
                  id.toString().getBytes(StandardCharsets.UTF_8),
                  status)
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                      + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, 'CHU_TRI', DATE '2029-07-01')")
              .params(UUID.randomUUID(), ASSIGNER, ASSIGNER, period, BUCKET, id, lead)
              .update();
          return null;
        });
    return id;
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(taskDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(taskTm).execute(s -> work.apply(jdbc)));
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return perform(request).getResponse().getStatus();
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
