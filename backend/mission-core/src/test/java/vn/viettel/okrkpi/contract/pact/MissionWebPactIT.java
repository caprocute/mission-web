package vn.viettel.okrkpi.contract.pact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.okr.infrastructure.web.OkrCaller;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressEvents;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskProgressEvents;
import vn.viettel.okrkpi.task.infrastructure.web.TestTaskCallers;

/**
 * QA-02 · ADR-32: mission-core (SVC-05) xác minh hợp đồng mà web (SVC-17) công bố ở {@code
 * frontend/pact/pacts/mission-web-mission-core.json} trên PostgreSQL thật. Mỗi tương tác chạy trong
 * một tenant mới để trạng thái của tương tác trước (ví dụ tiến độ vừa cập nhật) không ảnh hưởng
 * tương tác sau. Kiểm thử âm tính đổi tên một trường mà web đọc thì xác minh phải đỏ.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@Import(MissionWebPactIT.OutboxConfig.class)
class MissionWebPactIT {

  static final Path PACT = Path.of("../../frontend/pact/pacts/mission-web-mission-core.json");

  /** Người gọi của mọi tương tác: người mặc định của MOD-02, cũng là chủ KR ở MOD-01. */
  static final UUID ME = TestTaskCallers.DEFAULT_PERSON;

  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000c5ff");

  /**
   * Sự kiện tiến độ ghi outbox thật với bộ tuần tự giả (không có Apicurio), như {@code
   * TaskProgressApiIT}; lớp thường kèm {@code @Import} để không quét sang ngữ cảnh khác.
   */
  static class OutboxConfig {

    @Bean
    @Primary
    TaskProgressEvents pactTaskProgressEvents(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskProgressEvents(
          new OutboxWriter(
              new JdbcTemplate(ds),
              "task",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()));
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  /** Thay bean {@code testOkrCaller} của {@code OkrReadApiIT} (quét vào mọi ngữ cảnh). */
  @MockitoBean(name = "testOkrCaller")
  private OkrCaller okrCaller;

  @Autowired private MockMvc mvc;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("okrDataSource") private DataSource okrDataSource;

  @Autowired
  @Qualifier("okrTransactionManager") private PlatformTransactionManager okrTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @BeforeEach
  void caller() {
    when(okrCaller.currentUserId()).thenReturn(Optional.of(ME));
    when(okrCaller.currentUnitId()).thenReturn(Optional.empty());
  }

  @Test
  void webDungHopDongVoiMissionCore() {
    Pact pact = Pact.load(PACT);
    assertThat(pact.consumer()).isEqualTo("mission-web");
    assertThat(pact.provider()).isEqualTo("mission-core");
    assertThat(pact.interactions()).isNotEmpty();
    assertThat(verifier().failures(pact)).isEmpty();
  }

  @Test
  void doiTenTruongWebDocThiXacMinhDo() {
    ObjectNode raw = Pact.load(PACT).copyRaw();
    for (JsonNode i : raw.path("interactions")) {
      if (i.path("description").asText().equals("danh sách việc của tôi trong kỳ hiện hành")) {
        ObjectNode item = (ObjectNode) i.at("/response/body/items/0");
        item.set("titleText", item.remove("title"));
      }
    }
    assertThat(verifier().failures(Pact.of(raw)))
        .anySatisfy(e -> assertThat(e).contains("$.items[0].titleText: thiếu"));
  }

  private MockMvcPactVerifier verifier() {
    return new MockMvcPactVerifier(mvc, ME.toString(), this::state);
  }

  private Callable<MvcResult> state(Pact.State s, Callable<MvcResult> request) throws Exception {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    switch (s.name()) {
      case "cán bộ chủ trì một nhiệm vụ đang thực hiện trong kỳ hiện hành" ->
          taskInProgress(tenant, currentPeriod(tenant), uuid(s, "taskId"));
      case "cán bộ sở hữu một kết quả then chốt đã duyệt trong kỳ hiện hành" ->
          approvedKeyResult(tenant, currentPeriod(tenant), uuid(s, "keyResultId"));
      default -> throw new IllegalArgumentException("Chưa có trạng thái: " + s.name());
    }
    return () -> TenantContext.call(tenant, request);
  }

  // ---------------------------------------------------------------- MOD-02

  private void taskInProgress(TenantId tenant, Period period, UUID task) throws Exception {
    int bucket = period.bucket();
    in(
        tenant,
        taskDataSource,
        taskTm,
        jdbc -> {
          for (String table :
              List.of("task", "task_assignee", "task_state_log", "task_progress", "task_blocker")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, bucket)
                .query()
                .listOfRows();
          }
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, row_version, period_id,"
                      + " period_bucket, code, title, task_group, source, assigner_id, due_date,"
                      + " unit, dup_fingerprint, state_machine_version, status, is_overdue) VALUES"
                      + " (?, ?, ?, 1, ?, ?, 'NV-PACT-01', 'Báo cáo chuyên đề', 'THUONG_XUYEN',"
                      + " 'KE_HOACH', ?, ?, 'báo cáo', ?, 'mac-dinh@2.1.0', 'DANG_THUC_HIEN',"
                      + " false)")
              .params(
                  task,
                  ASSIGNER,
                  ASSIGNER,
                  period.id(),
                  bucket,
                  ASSIGNER,
                  Date.valueOf(period.end()),
                  task.toString().getBytes(StandardCharsets.UTF_8))
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                      + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, 'CHU_TRI', ?)")
              .params(
                  UUID.randomUUID(),
                  ASSIGNER,
                  ASSIGNER,
                  period.id(),
                  bucket,
                  task,
                  ME,
                  Date.valueOf(period.start()))
              .update();
          return null;
        });
  }

  // ---------------------------------------------------------------- MOD-01

  private void approvedKeyResult(TenantId tenant, Period period, UUID keyResult) throws Exception {
    int bucket = period.bucket();
    UUID objective = UUID.randomUUID();
    in(
        tenant,
        okrDataSource,
        okrTm,
        jdbc -> {
          for (String table :
              List.of(
                  "objective", "objective_basis", "objective_review", "key_result", "kr_checkin")) {
            jdbc.sql("SELECT okr.fn_create_period_partitions(?::regclass, ?)")
                .params("okr." + table, bucket)
                .query()
                .listOfRows();
          }
          jdbc.sql(
                  "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type,"
                      + " owner_unit_id, code, title, tier, status, created_by, updated_by)"
                      + " VALUES (?, ?, ?, 'TAP_THE', ?, 'O1', 'Mục tiêu kiểm thử hợp đồng', 1,"
                      + " 'DA_DUYET', ?, ?)")
              .params(objective, period.id(), bucket, UUID.randomUUID(), ME, ME)
              .update();
          jdbc.sql(
                  "INSERT INTO okr.key_result (id, period_id, period_bucket, objective_id, code,"
                      + " title, metric_type, unit, baseline_value, target_value, direction,"
                      + " weight, due_date, verifier_person_id, checkin_frequency, status,"
                      + " created_by, updated_by) VALUES (?, ?, ?, ?, 'KR 1.1', 'Kết quả kiểm"
                      + " thử', 'SO_LUONG', 'chi bộ', 0, 10, 'TANG', 1, ?, ?, 'TUAN', 'DA_DUYET',"
                      + " ?, ?)")
              .params(
                  keyResult,
                  period.id(),
                  bucket,
                  objective,
                  Date.valueOf(period.end()),
                  ASSIGNER,
                  ME,
                  ME)
              .update();
          return null;
        });
  }

  // ---------------------------------------------------------------- tiện ích

  record Period(UUID id, int bucket, LocalDate start, LocalDate end) {}

  /**
   * Kỳ quý đang mở chứa hôm nay (mã kỳ mới mỗi lần, vì khoá của {@code config.period} chỉ là {@code
   * id}): màn hình mở lần đầu không ghi kỳ nên máy chủ lọc theo kỳ hiện hành.
   */
  private Period currentPeriod(TenantId tenant) throws Exception {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    int quarter = (today.getMonthValue() - 1) / 3 + 1;
    LocalDate start = LocalDate.of(today.getYear(), (quarter - 1) * 3 + 1, 1);
    Period p =
        new Period(
            UUID.randomUUID(),
            today.getYear() * 10 + quarter,
            start,
            start.plusMonths(3).minusDays(1));
    String code = "Q" + quarter + "-" + today.getYear() + "-PACT";
    String name = "Quý " + quarter + " năm " + today.getYear();
    String startText = p.start().toString();
    String endText = p.end().toString();
    int bucket = p.bucket();
    UUID id = p.id();
    in(
        tenant,
        configDataSource,
        configTm,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO config.period (id, code, name, kind, flow, start_date, end_date,"
                        + " period_bucket, status, created_by, updated_by) VALUES (?, ?, ?, 'QUY',"
                        + " 'NHA_NUOC', ?::date, ?::date, ?, 'MO', ?, ?)")
                .params(id, code, name, startText, endText, bucket, ME, ME)
                .update());
    return p;
  }

  private static <T> T in(
      TenantId tenant, DataSource ds, PlatformTransactionManager tm, Function<JdbcClient, T> work)
      throws Exception {
    JdbcClient jdbc = JdbcClient.create(ds);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(jdbc)));
  }

  private static UUID uuid(Pact.State s, String param) {
    return UUID.fromString(s.params().path(param).asText());
  }
}
