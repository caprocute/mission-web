package vn.viettel.okrkpi.workflow.infrastructure.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.HistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.saga.SagaBlueprint;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandler;
import vn.viettel.okrkpi.workflow.application.saga.StepInvocation;
import vn.viettel.okrkpi.workflow.application.saga.StepResult;
import vn.viettel.okrkpi.workflow.domain.saga.RetryPolicy;
import vn.viettel.okrkpi.workflow.domain.saga.SagaDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Khung saga DV-06-05 trên PostgreSQL thật và engine Flowable thật: saga 3 bước chạy hết, bước 2
 * lỗi thì thử lại rồi bù trừ bước 1, worker chết giữa chừng thì worker khác chạy tiếp đúng bước,
 * gọi trùng mã tương quan không tạo saga mới, khởi động ≤ 200 ms. Định nghĩa saga ở đây chỉ để kiểm
 * thử; các bước thật của CHOT_KY thuộc DV-06-06.
 */
@SpringBootTest(
    properties = {
      "flowable.async-executor-activate=true",
      "flowable.process.async.executor.default-async-job-acquire-wait-time=100ms",
      "flowable.process.async.executor.reset-expired-jobs-interval=200ms",
      "okrkpi.saga.worker.enabled=false",
      "okrkpi.saga.period-close.enabled=false",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
class SagaFrameworkIT {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  private static final UUID CLIENT = UUID.fromString("0192f1a2-0000-7000-8000-00000000c001");
  private static final String SCENARIO = "kichBan";

  /** Số lần chạy theo "sagaId:bước" để kiểm tra bước không chạy lặp. */
  static final Map<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();

  static final List<String> COMPENSATIONS = new ArrayList<>();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_wf_saga")
          .withUsername("postgres")
          .withPassword("postgres");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("flowable.database-schema-update", () -> "false");
    registry.add("flowable.check-process-definitions", () -> "false");
    registry.add("okrkpi.inbox.listener-auto-startup", () -> "false");
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private SagaService sagas;
  @Autowired private FlowableSagaEngine engine;
  @Autowired private JdbcClient jdbc;
  @Autowired private HistoryService history;

  private SagaWorker worker;

  @BeforeEach
  void setUp() {
    worker = new SagaWorker(engine, sagas, 16, Duration.ofSeconds(1));
  }

  @TestConfiguration
  static class TestSaga {

    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    SagaBlueprint periodClose() {
      RetryPolicy fast = new RetryPolicy(3, Duration.ofMillis(50), 1.0, Duration.ofMillis(50));
      return new SagaBlueprint() {
        @Override
        public SagaDefinition definition() {
          return new SagaDefinition(
              SagaType.CHOT_KY,
              List.of(
                  new SagaStepDefinition(
                      "DAT_DANG_KHOA", "LENH", null, Duration.ofMinutes(1), fast, "BO_KHOA", false),
                  new SagaStepDefinition(
                      "CHAY_TINH_DIEM", "LENH", null, Duration.ofMinutes(1), fast, null, false),
                  new SagaStepDefinition(
                      "NIEM_PHONG", "LENH", null, Duration.ofMinutes(1), fast, null, true)),
              true);
        }

        @Override
        public String exclusiveKey(Map<String, Object> parameters) {
          return (String) parameters.get("periodId");
        }
      };
    }

    @Bean
    SagaStepHandler step1() {
      return new CountingHandler("DAT_DANG_KHOA");
    }

    @Bean
    SagaStepHandler step2() {
      return new CountingHandler("CHAY_TINH_DIEM");
    }

    @Bean
    SagaStepHandler step3() {
      return new CountingHandler("NIEM_PHONG");
    }
  }

  record CountingHandler(String stepCode) implements SagaStepHandler {
    @Override
    public SagaType sagaType() {
      return SagaType.CHOT_KY;
    }

    @Override
    public StepResult execute(StepInvocation invocation) {
      RUNS.computeIfAbsent(invocation.sagaId() + ":" + stepCode, k -> new AtomicInteger())
          .incrementAndGet();
      if ("LOI_BUOC_2".equals(invocation.parameters().get(SCENARIO)) && invocation.stepNo() == 2) {
        throw new IllegalStateException("Dịch vụ tính điểm không phản hồi");
      }
      return StepResult.completed();
    }

    @Override
    public void compensate(StepInvocation invocation) {
      synchronized (COMPENSATIONS) {
        COMPENSATIONS.add(invocation.sagaId() + ":" + stepCode);
      }
    }
  }

  private MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request) {
    return request.with(
        jwt()
            .jwt(j -> j.subject(CLIENT.toString()).claim("tenant_id", TENANT.toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_service_client")));
  }

  private String startBody(UUID periodId, UUID lockOrderId) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "periodId",
            periodId,
            "lockOrderId",
            lockOrderId,
            "unitIds",
            List.of(),
            "requestedBy",
            UUID.randomUUID()));
  }

  private SagaInstance startDirect(Map<String, Object> parameters) throws Exception {
    return TenantContext.call(
        TenantId.of(TENANT),
        () ->
            sagas
                .start(
                    new SagaService.StartCommand(
                        TENANT, SagaType.CHOT_KY, UUID.randomUUID(), parameters, CLIENT))
                .saga());
  }

  private SagaInstance load(UUID id) throws Exception {
    return TenantContext.call(TenantId.of(TENANT), () -> sagas.find(id).orElseThrow());
  }

  /** Chạy worker cho tới khi saga kết thúc. */
  private SagaInstance drive(UUID id) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      worker.poll();
      worker.reconcile();
      SagaInstance saga = load(id);
      if (saga.status().isTerminal()) {
        return saga;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Saga " + id + " không kết thúc: " + load(id).status());
  }

  private int runs(UUID sagaId, String step) {
    AtomicInteger n = RUNS.get(sagaId + ":" + step);
    return n == null ? 0 : n.get();
  }

  private void awaitProcessEnded(UUID sagaId) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      long ended =
          history
              .createHistoricProcessInstanceQuery()
              .processInstanceBusinessKey(SagaService.businessKey(sagaId))
              .finished()
              .count();
      if (ended == 1) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Tiến trình điều phối của saga " + sagaId + " chưa kết thúc");
  }

  @Test
  void threeStepSagaStartedByApiCompletesAndIsTrackedAsJob() throws Exception {
    String location =
        mvc.perform(
                asClient(post("/api/v1/workflow/sagas/period-close"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(startBody(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("PERIOD_LOCK"))
            .andExpect(jsonPath("$.status").value("DANG_CHAY"))
            .andReturn()
            .getResponse()
            .getHeader("Location");
    assertThat(location).startsWith("/api/v1/jobs/");
    UUID id = UUID.fromString(location.substring("/api/v1/jobs/".length()));

    SagaInstance done = drive(id);
    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(done.pointOfNoReturnPassed()).isTrue();
    assertThat(done.steps()).allMatch(s -> s.status() == SagaStepStatus.XONG);
    assertThat(runs(id, "DAT_DANG_KHOA")).isEqualTo(1);
    assertThat(runs(id, "NIEM_PHONG")).isEqualTo(1);
    awaitProcessEnded(id);

    mvc.perform(asClient(get(location)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HOAN_TAT"))
        .andExpect(jsonPath("$.progress").value(100));

    Integer ledger =
        jdbc.sql(
                "SELECT count(*) FROM flowable.outbox_event WHERE topic = 'sys.audit.v1'"
                    + " AND event_key LIKE ?")
            .param("%")
            .query(Integer.class)
            .single();
    assertThat(ledger).isGreaterThanOrEqualTo(8);
  }

  @Test
  void duplicateCorrelationReturnsSameSagaAndOtherOrderForSamePeriodIsRejected() throws Exception {
    UUID period = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    String first =
        mvc.perform(
                asClient(post("/api/v1/workflow/sagas/period-close"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(startBody(period, order)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    String second =
        mvc.perform(
                asClient(post("/api/v1/workflow/sagas/period-close"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(startBody(period, order)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    assertThat(second).isEqualTo(first);
    Integer count =
        jdbc.sql("SELECT count(*) FROM flowable.saga_instance WHERE correlation_id = ?")
            .param(order)
            .query(Integer.class)
            .single();
    assertThat(count).isEqualTo(1);

    mvc.perform(
            asClient(post("/api/v1/workflow/sagas/period-close"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(startBody(period, UUID.randomUUID())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORKFLOW-409-SAGA-EXISTS"));
  }

  @Test
  void callerWithoutServiceRoleIsForbidden() throws Exception {
    mvc.perform(
            post("/api/v1/workflow/sagas/period-close")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(CLIENT.toString()).claim("tenant_id", TENANT.toString())))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(startBody(UUID.randomUUID(), UUID.randomUUID())))
        .andExpect(status().isForbidden());
  }

  @Test
  void failingStepIsRetriedThenEarlierStepsAreCompensated() throws Exception {
    SagaInstance saga =
        startDirect(Map.of("periodId", UUID.randomUUID().toString(), SCENARIO, "LOI_BUOC_2"));
    SagaInstance undone = drive(saga.id());
    assertThat(undone.status()).isEqualTo(SagaStatus.DA_BU_TRU);
    assertThat(undone.step(1).status()).isEqualTo(SagaStepStatus.DA_BU_TRU);
    assertThat(undone.step(2).status()).isEqualTo(SagaStepStatus.LOI);
    assertThat(undone.step(2).attempts()).isEqualTo(3);
    assertThat(undone.step(2).lastError()).contains("không phản hồi");
    assertThat(undone.step(3).status()).isEqualTo(SagaStepStatus.CHO);
    assertThat(runs(saga.id(), "CHAY_TINH_DIEM")).isEqualTo(3);
    assertThat(COMPENSATIONS).containsOnlyOnce(saga.id() + ":DAT_DANG_KHOA");
    awaitProcessEnded(saga.id());
  }

  @Test
  void workerDyingMidSagaIsTakenOverAtTheRightStep() throws Exception {
    SagaInstance saga = startDirect(Map.of("periodId", UUID.randomUUID().toString()));
    // Worker thứ nhất chạy bước 1 rồi nhận lượt của bước 2 và "chết" (không bao giờ trả lượt).
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (load(saga.id()).currentStepNo() < 2 && System.nanoTime() < deadline) {
      worker.poll();
      Thread.sleep(20);
    }
    assertThat(load(saga.id()).currentStepNo()).isEqualTo(2);
    int stolen = 0;
    while (stolen == 0 && System.nanoTime() < deadline) {
      stolen = engine.acquire(16, Duration.ofSeconds(1)).size();
      Thread.sleep(20);
    }
    assertThat(stolen).isPositive();
    // Lượt đang bị khoá: worker khác chưa nhận được.
    worker.poll();
    assertThat(runs(saga.id(), "CHAY_TINH_DIEM")).isZero();

    SagaInstance done = drive(saga.id());
    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(runs(saga.id(), "DAT_DANG_KHOA")).isEqualTo(1);
    assertThat(runs(saga.id(), "CHAY_TINH_DIEM")).isEqualTo(1);
    assertThat(runs(saga.id(), "NIEM_PHONG")).isEqualTo(1);
  }

  @Test
  void startRespondsWithin200Milliseconds() throws Exception {
    startDirect(Map.of("periodId", UUID.randomUUID().toString()));
    List<Long> millis = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      long t0 = System.nanoTime();
      startDirect(Map.of("periodId", UUID.randomUUID().toString()));
      millis.add(Duration.ofNanos(System.nanoTime() - t0).toMillis());
    }
    millis.sort(Long::compare);
    long p95 = millis.get(18);
    System.out.println("Thời gian khởi động saga p95 (ms): " + p95 + ", tất cả: " + millis);
    assertThat(p95).isLessThanOrEqualTo(200);
  }
}
