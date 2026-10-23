package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.fromplan.LeadConfirmationService;
import vn.viettel.okrkpi.task.application.fromplan.PlanTaskGenerator;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTaskNotices;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.plan.PlanChecker;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.infrastructure.listener.PlanApprovedConsumer;
import vn.vtokr.task.v1.KeHoachDaDuyet;

/**
 * Kiểm thử tích hợp CORE-02-11 trên PostgreSQL thật: EVT-03 sinh nhiệm vụ {@code NHAP} cho từng
 * dòng, nhận hai lần không sinh trùng; điều chỉnh kế hoạch cập nhật nhiệm vụ chưa bắt đầu, huỷ
 * nhiệm vụ của dòng bỏ (EVT-06) và không huỷ khi có minh chứng đã xác nhận; kế hoạch 500 dòng xong
 * dưới 60 giây; hai kế hoạch xử lý song song không trùng mã; xác nhận dòng liên đơn vị. Kafka thay
 * bằng bản ghi inbox tổng hợp như {@code PlanApprovalIT}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.task.plan-approved.consumer.enabled=false",
      "mission.task.plan-approval.consumer.enabled=false"
    })
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanTaskGenerationIT {

  static final UUID AUTHOR = UUID.fromString("01927a02-0000-7000-8000-00000000b211");
  static final UUID HEAD = UUID.fromString("01927a02-0000-7000-8000-00000000b212");
  static final UUID PERSON = UUID.fromString("01927a02-0000-7000-8000-00000000b213");
  static final int BUCKET = 20302;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private PlanTaskGenerator generator;
  @Autowired private LeadConfirmationService confirmations;
  @Autowired private PlanChecker checker;
  @Autowired private PlanRepository plans;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @MockitoBean private PlanTaskNotices notices;
  @MockitoBean private TaskDeliverables deliverables;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private PlanApprovedConsumer consumer;
  private long offset;

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
                        + " period_bucket, status, created_by, updated_by) VALUES (?, '2030-Q2',"
                        + " 'Quý II năm 2030', 'QUY', 'NHA_NUOC', DATE '2030-04-01', DATE"
                        + " '2030-06-30', ?, 'MO', ?, ?)")
                .params(period, BUCKET, AUTHOR, AUTHOR)
                .update());
    inTask(
        jdbc -> {
          for (String table :
              List.of(
                  "plan",
                  "plan_version",
                  "plan_item",
                  "task",
                  "task_assignee",
                  "task_state_log",
                  "task_change_request")) {
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
    consumer =
        new PlanApprovedConsumer(generator, processor, none, new SimpleMeterRegistry(), false);
  }

  @Test
  void sinhCapNhatVaHuyNhiemVuTheoKeHoachDaDuyet() throws Exception {
    UUID unit = UUID.randomUUID();
    UUID plan = plan(unit);
    UUID v1 = version(plan, 1);
    UUID i1 = item(v1, 1, "Báo cáo tháng", unit, null, null, "2030-05-30");
    UUID i2 = item(v1, 2, "Tổng hợp số liệu", null, PERSON, null, "2030-05-30");
    UUID i3 = item(v1, 3, "Hội nghị sơ kết", unit, null, null, "2030-06-20");
    approve(v1, null);

    // EVT-03 nhận hai lần cùng mã rồi một lần mã khác: ba nhiệm vụ NHAP, không trùng.
    UUID event = UUID.randomUUID();
    assertThat(deliver(event, plan, v1, null)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(deliver(event, plan, v1, null)).isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(deliver(UUID.randomUUID(), plan, v1, null)).isEqualTo(InboxOutcome.PROCESSED);
    List<Map<String, Object>> first = tasksOf(List.of(i1, i2, i3));
    assertThat(first).hasSize(3);
    assertThat(first)
        .allSatisfy(
            t -> {
              assertThat(t.get("status")).isEqualTo("NHAP");
              assertThat(t.get("source")).isEqualTo("KE_HOACH");
              assertThat((String) t.get("code")).startsWith("NV-2030-Q2-");
              assertThat(t.get("difficulty")).isNotNull();
              assertThat(t.get("product_name")).isNotNull();
            });
    assertThat(
            count(
                "SELECT count(*) FROM task.task_state_log l JOIN task.task t ON t.id = l.task_id"
                    + " WHERE t.plan_item_id = ANY(?) AND l.transition = 'SINH_TU_KE_HOACH'",
                (Object) new UUID[] {i1, i2, i3}))
        .isEqualTo(3);
    UUID task2 = taskOf(i2);
    assertThat(
            count(
                "SELECT count(*) FROM task.task_assignee WHERE task_id = ? AND role = 'CHU_TRI'"
                    + " AND person_id = ?",
                task2,
                PERSON))
        .isEqualTo(1);
    verify(notices).generated(tenant, plan, v1, AUTHOR, 3, 0, 0);

    // Nhiệm vụ dòng 2 đã giao, dòng 3 đang thực hiện.
    UUID task1 = taskOf(i1);
    UUID task3 = taskOf(i3);
    setStatus(task2, "DA_GIAO");
    setStatus(task3, "DANG_THUC_HIEN");

    // Điều chỉnh: sửa dòng 1, 2; bỏ dòng 3; thêm dòng 4.
    UUID v2 = version(plan, 2);
    UUID j1 = item(v2, 1, "Báo cáo tháng (bổ sung phụ lục)", unit, null, i1, "2030-05-30");
    UUID j2 = item(v2, 2, "Tổng hợp số liệu", null, PERSON, i2, "2030-06-15");
    UUID j4 = item(v2, 3, "Khảo sát cơ sở", unit, null, null, "2030-06-25");
    approve(v2, v1);
    assertThat(deliver(UUID.randomUUID(), plan, v2, v1)).isEqualTo(InboxOutcome.PROCESSED);

    assertThat(row(task1))
        .containsEntry("plan_item_id", j1)
        .containsEntry("title", "Báo cáo tháng (bổ sung phụ lục)")
        .containsEntry("status", "NHAP");
    assertThat(row(task2))
        .containsEntry("plan_item_id", j2)
        .containsEntry("status", "DA_GIAO")
        .containsEntry("due_date", java.sql.Date.valueOf(LocalDate.of(2030, 6, 15)));
    assertThat(row(task3)).containsEntry("status", "HUY");
    assertThat(tasksOf(List.of(j4)))
        .singleElement()
        .satisfies(t -> assertThat(t.get("status")).isEqualTo("NHAP"));
    assertThat(
            count(
                "SELECT count(*) FROM task.task_change_request WHERE task_id = ?"
                    + " AND request_type = 'HUY' AND status = 'DA_DUYET' AND decided_by = ?",
                task3,
                HEAD))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM task.outbox_event WHERE aggregate_id = ? AND topic = ?",
                task3,
                EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI))
        .isEqualTo(1);
    verify(notices).generated(tenant, plan, v2, AUTHOR, 1, 2, 1);
    verify(notices, never()).cancelBlocked(any(), any(), any(), any(), any());

    // Bỏ dòng 2 khi nhiệm vụ đã có minh chứng được xác nhận: không huỷ, báo người lập.
    when(deliverables.of(any(), eq(task2))).thenReturn(List.of(Map.of("status", "DA_XAC_NHAN")));
    UUID v3 = version(plan, 3);
    item(v3, 1, "Báo cáo tháng (bổ sung phụ lục)", unit, null, j1, "2030-05-30");
    item(v3, 2, "Khảo sát cơ sở", unit, null, j4, "2030-06-25");
    approve(v3, v2);
    assertThat(deliver(UUID.randomUUID(), plan, v3, v2)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(row(task2)).containsEntry("status", "DA_GIAO");
    verify(notices)
        .cancelBlocked(tenant, plan, v3, AUTHOR, List.of((String) row(task2).get("code")));
  }

  @Test
  void keHoachNamTramDongVaHaiKeHoachSongSongKhongTrungMa() throws Exception {
    UUID big = plan(UUID.randomUUID());
    UUID vb = version(big, 1);
    items(vb, 500);
    approve(vb, null);
    Instant start = Instant.now();
    assertThat(deliver(UUID.randomUUID(), big, vb, null)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(60));
    assertThat(
            count(
                "SELECT count(*) FROM task.task t JOIN task.plan_item i ON i.id ="
                    + " t.plan_item_id WHERE i.plan_version_id = ?",
                vb))
        .isEqualTo(500);

    UUID a = plan(UUID.randomUUID());
    UUID va = version(a, 1);
    items(va, 60);
    approve(va, null);
    UUID b = plan(UUID.randomUUID());
    UUID vbb = version(b, 1);
    items(vbb, 60);
    approve(vbb, null);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<InboxOutcome> fa = pool.submit(() -> deliver(UUID.randomUUID(), a, va, null));
      Future<InboxOutcome> fb = pool.submit(() -> deliver(UUID.randomUUID(), b, vbb, null));
      assertThat(fa.get()).isEqualTo(InboxOutcome.PROCESSED);
      assertThat(fb.get()).isEqualTo(InboxOutcome.PROCESSED);
    } finally {
      pool.shutdown();
    }
    long total = count("SELECT count(*) FROM task.task WHERE source = 'KE_HOACH'");
    long distinct = count("SELECT count(DISTINCT code) FROM task.task WHERE source = 'KE_HOACH'");
    assertThat(distinct).isEqualTo(total);
  }

  @Test
  void dongLienDonViCanDonViChuTriXacNhan() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID plan = plan(owner);
    UUID v1 = version(plan, 1);
    UUID own = item(v1, 1, "Báo cáo tháng", owner, null, null, "2030-05-30");
    UUID cross = item(v1, 2, "Phối hợp kiểm tra", other, null, null, "2030-05-30");

    assertThat(levels(v1)).containsExactly(PlanChecker.Level.CANH_BAO);
    assertThatThrownBy(() -> decide(own, true, null, List.of(owner)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> decide(cross, true, null, List.of(owner)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> decide(cross, false, " ", List.of(other)))
        .isInstanceOf(BusinessException.class);

    decide(cross, false, "Không thuộc chức năng của đơn vị", List.of(other));
    assertThat(levels(v1)).containsExactly(PlanChecker.Level.CHAN);
    decide(cross, true, null, List.of(other));
    assertThat(levels(v1)).isEmpty();

    // Phiên bản sau thừa hưởng xác nhận của dòng gốc cùng đơn vị chủ trì; đã duyệt thì khoá.
    approve(v1, null);
    assertThatThrownBy(() -> decide(cross, true, null, List.of(other)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("TASK-409-INVALID-STATE"));
    UUID v2 = version(plan, 2);
    item(v2, 1, "Phối hợp kiểm tra", other, null, cross, "2030-05-30");
    item(v2, 2, "Kiểm tra chéo", UUID.randomUUID(), null, null, "2030-05-30");
    assertThat(levels(v2)).containsExactly(PlanChecker.Level.CANH_BAO);
  }

  private void decide(UUID item, boolean accept, String reason, List<UUID> units) throws Exception {
    TenantContext.call(
        tenant,
        () -> {
          confirmations.decide(tenant, item, accept, reason, HEAD, units);
          return null;
        });
  }

  private List<PlanChecker.Level> levels(UUID version) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(taskTm)
                .execute(
                    s ->
                        checker
                            .check(
                                plans.items(tenant, BUCKET, version),
                                new PlanChecker.Context(
                                    LocalDate.of(2030, 4, 1),
                                    LocalDate.of(2030, 6, 30),
                                    List.of(),
                                    PlanChecker.DEFAULT_GROUPS,
                                    List.of()))
                            .stream()
                            .filter(c -> "QT-04".equals(c.rule()))
                            .map(PlanChecker.Check::level)
                            .toList()));
  }

  private UUID plan(UUID unit) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO task.plan (id, created_by, updated_by, period_id, period_bucket,"
                        + " plan_type, owner_unit_id) VALUES (?, ?, ?, ?, ?, 'TAP_THE', ?)")
                .params(id, AUTHOR, AUTHOR, period, BUCKET, unit)
                .update());
    return id;
  }

  private UUID version(UUID plan, int no) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO task.plan_version (id, created_by, updated_by, period_id,"
                        + " period_bucket, plan_id, version_no, status, change_reason) VALUES (?,"
                        + " ?, ?, ?, ?, ?, ?, 'NHAP', ?)")
                .params(id, AUTHOR, AUTHOR, period, BUCKET, plan, no, no == 1 ? null : "Điều chỉnh")
                .update());
    return id;
  }

  private UUID item(
      UUID version, int no, String title, UUID leadUnit, UUID leadPerson, UUID origin, String due)
      throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO task.plan_item (id, created_by, updated_by, period_id,"
                        + " period_bucket, plan_version_id, item_no, title, task_group,"
                        + " lead_unit_id, lead_person_id, planned_quantity, unit, difficulty,"
                        + " standard_score, due_date, origin_item_id) VALUES (?, ?, ?, ?, ?, ?, ?,"
                        + " ?, 'THEN_CHOT', ?, ?, 1, 'báo cáo', 3, 10, ?::date, ?)")
                .params(
                    id,
                    AUTHOR,
                    AUTHOR,
                    period,
                    BUCKET,
                    version,
                    no,
                    title,
                    leadUnit,
                    leadPerson,
                    due,
                    origin)
                .update());
    return id;
  }

  private void items(UUID version, int n) throws Exception {
    inTask(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO task.plan_item (id, created_by, updated_by, period_id,"
                        + " period_bucket, plan_version_id, item_no, title, task_group,"
                        + " lead_unit_id, planned_quantity, unit, difficulty, standard_score,"
                        + " due_date) SELECT gen_random_uuid(), ?, ?, ?, ?, ?, g, 'Công việc ' || g,"
                        + " 'THUONG_XUYEN', gen_random_uuid(), 1, 'báo cáo', 2, 0.1, DATE"
                        + " '2030-06-20' FROM generate_series(1, ?) g")
                .params(AUTHOR, AUTHOR, period, BUCKET, version, n)
                .update());
  }

  /** Duyệt phiên bản; phiên bản cũ bỏ cờ hiện hành trước. */
  private void approve(UUID version, UUID superseded) throws Exception {
    inTask(
        jdbc -> {
          if (superseded != null) {
            jdbc.sql("UPDATE task.plan_version SET is_current = false WHERE id = ?")
                .param(superseded)
                .update();
          }
          return jdbc.sql(
                  "UPDATE task.plan_version SET status = 'DA_DUYET', is_current = true,"
                      + " approved_by = ?, approved_at = now() WHERE id = ?")
              .params(HEAD, version)
              .update();
        });
  }

  private void setStatus(UUID task, String status) throws Exception {
    inTask(
        jdbc ->
            jdbc.sql("UPDATE task.task SET status = ? WHERE id = ?").params(status, task).update());
  }

  private UUID taskOf(UUID item) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT id FROM task.task WHERE plan_item_id = ?")
                .param(item)
                .query(UUID.class)
                .single());
  }

  private List<Map<String, Object>> tasksOf(List<UUID> items) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT * FROM task.task WHERE plan_item_id = ANY(?)")
                .param(items.toArray(UUID[]::new))
                .query()
                .listOfRows());
  }

  private Map<String, Object> row(UUID task) throws Exception {
    return inTask(
        jdbc -> jdbc.sql("SELECT * FROM task.task WHERE id = ?").param(task).query().singleRow());
  }

  private long count(String sql, Object... params) throws Exception {
    return inTask(jdbc -> jdbc.sql(sql).params(params).query(Long.class).single());
  }

  private InboxOutcome deliver(UUID eventId, UUID plan, UUID version, UUID superseded) {
    KeHoachDaDuyet payload =
        KeHoachDaDuyet.newBuilder()
            .setPlanId(plan)
            .setPlanVersionId(version)
            .setVersionNo(1)
            .setPeriodId(period)
            .setOwnerType("TAP_THE")
            .setOwnerId(UUID.randomUUID())
            .setItemCount(0)
            .setApprovedBy(HEAD)
            .setSupersededVersionId(superseded)
            .setAggregateVersion((int) offset + 1)
            .build();
    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(
            EventTopics.EVT_03_KE_HOACH_DA_DUYET,
            0,
            offset++,
            tenant.value() + ":" + plan,
            encode(payload));
    header(record, OutboxHeaders.EVENT_ID, eventId.toString());
    header(record, OutboxHeaders.TENANT_ID, tenant.value().toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, String.valueOf(offset));
    header(record, OutboxHeaders.CE_TYPE, "KeHoachDaDuyet");
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    header(record, OutboxHeaders.ACTOR_ID, HEAD.toString());
    return consumer.processor().process(consumer.endpoint(), record, () -> {});
  }

  private static void header(ConsumerRecord<String, byte[]> record, String key, String value) {
    record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] encode(KeHoachDaDuyet payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<>(KeHoachDaDuyet.class).write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static KeHoachDaDuyet decode(byte[] data) {
    try {
      return new SpecificDatumReader<>(KeHoachDaDuyet.class)
          .read(null, DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(data), null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
}
