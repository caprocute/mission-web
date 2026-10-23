package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.application.PeriodLockControlService;
import vn.viettel.okrkpi.config.application.port.LockProjectionRepository;
import vn.viettel.okrkpi.config.domain.LockBatch;
import vn.viettel.okrkpi.config.domain.LockFailure;
import vn.viettel.okrkpi.config.infrastructure.web.PeriodCaller;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobQueryService;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-05 (phần điều khiển saga chốt kỳ) trên PostgreSQL thật: tạm dừng, tiếp
 * tục theo {@code If-Match} của lệnh; lô, hồ sơ lỗi, nhật ký, sơ đồ; chạy lại hồ sơ lỗi (409 khi
 * không có, 202 + {@code /jobs/{id}}); đề nghị huỷ và duyệt huỷ bốn mắt; bù trừ xong thì lệnh
 * {@code DA_HUY}. Phản hồi khớp {@code config.yaml}. Saga SVC-06 lấy bản kiểm thử của {@link
 * PeriodLockApiIT}; lệnh điều khiển xuống saga ghi vào hộp lệnh {@code config.period_lock_command}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import({PeriodLockApiIT.LockSagaConfig.class, PeriodLockControlApiIT.ControlCallers.class})
class PeriodLockControlApiIT extends ContractConformanceTest {

  /**
   * Không gắn {@code @TestConfiguration} (quét thành phần nhặt cả lớp lồng của kiểm thử khác): mọi
   * bean {@link PeriodCaller}, kể cả bean {@code @Primary} của IT khác bị quét vào, được bọc thành
   * người gọi đọc {@link PeriodApiIT.CallerConfig} để thử MFA bước lên và bốn mắt.
   */
  static class ControlCallers {

    @Bean
    static BeanPostProcessor periodCallerOverride() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          return bean instanceof PeriodCaller ? CALLER : bean;
        }
      };
    }
  }

  static final PeriodCaller CALLER =
      new PeriodCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(PeriodApiIT.CallerConfig.current);
        }

        @Override
        public Optional<String> acr() {
          return Optional.ofNullable(PeriodApiIT.CallerConfig.acr);
        }

        @Override
        public Optional<Long> authTime() {
          return Optional.ofNullable(PeriodApiIT.CallerConfig.authTime);
        }

        @Override
        public List<String> roles() {
          return PeriodApiIT.CallerConfig.roles;
        }
      };

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private LockProjectionRepository projections;
  @Autowired private PeriodLockControlService control;
  @Autowired private JobQueryService jobs;

  @Autowired
  @Qualifier("configDataSource") private javax.sql.DataSource dataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID admin = PeriodApiIT.CallerConfig.ACTOR;
  private final UUID leader = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  @AfterEach
  void resetCaller() {
    PeriodApiIT.CallerConfig.reset();
  }

  private void as(UUID user, boolean mfa) {
    PeriodApiIT.CallerConfig.current = user;
    PeriodApiIT.CallerConfig.acr = mfa ? "aal2" : "aal1";
    PeriodApiIT.CallerConfig.authTime = Instant.now().getEpochSecond() - 30;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static MockHttpServletRequestBuilder postJson(String path, String body) {
    return post(path)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private Optional<Job> job(ActorId caller, String jobId) throws Exception {
    return TenantContext.call(tenant, () -> jobs.find(tenant, caller, jobId));
  }

  private void inTenant(Runnable work) throws Exception {
    TenantContext.call(
        tenant,
        () -> {
          new TransactionTemplate(tm).executeWithoutResult(s -> work.run());
          return null;
        });
  }

  /** Lập kỳ, lập lệnh, lãnh đạo duyệt, quản trị khởi chạy; trả đường dẫn kỳ. */
  private String runningSaga(String code, String name, String start, String end) throws Exception {
    as(admin, true);
    MvcResult period =
        perform(
            postJson(
                "/api/v1/periods",
                """
                {"code":"%s","name":"%s","kind":"QUY","flow":"CA_HAI",\
                "startDate":"%s","endDate":"%s"}
                """
                    .formatted(code, name, start, end)));
    assertThat(period.getResponse().getStatus()).isEqualTo(201);
    String base = "/api/v1/periods/" + body(period).get("id").asText();
    String planned = Instant.now().plus(10, ChronoUnit.DAYS).toString();
    MvcResult created =
        perform(
            postJson(
                base + "/lock-orders",
                "{\"basisDoc\":\"Thông báo 22-TB/ĐUK\",\"plannedLockAt\":\"" + planned + "\"}"));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    String oid = body(created).get("id").asText();
    as(leader, true);
    MvcResult approved =
        perform(
            postJson(base + "/lock-orders/" + oid + "/approve", "{}").header("If-Match", "\"0\""));
    assertThat(approved.getResponse().getStatus())
        .as(approved.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(200);
    String key = body(approved).get("idempotencyKey").asText();
    as(admin, true);
    MvcResult started =
        perform(
            post(base + "/lock")
                .header("Idempotency-Key", key)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lockOrderId\":\"" + oid + "\"}"));
    assertThat(started.getResponse().getStatus()).isEqualTo(202);
    return base;
  }

  @Test
  void controlFlowFollowsContractStepUpVersionAndFourEyes() throws Exception {
    as(admin, true);
    MvcResult none =
        perform(
            postJson(
                "/api/v1/periods",
                """
                {"code":"2029-Q1","name":"Quý I/2029","kind":"QUY","flow":"CA_HAI",\
                "startDate":"2029-01-01","endDate":"2029-03-31"}
                """));
    String noSaga = "/api/v1/periods/" + body(none).get("id").asText();
    // Kỳ chưa có saga: 404.
    assertThat(perform(get(noSaga + "/lock/batches")).getResponse().getStatus()).isEqualTo(404);
    assertThat(perform(get(noSaga + "/lock/diagram")).getResponse().getStatus()).isEqualTo(404);
    assertThat(
            perform(postJson(noSaga + "/lock/pause", "{}").header("If-Match", "\"2\""))
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    String base = runningSaga("2029-Q2", "Quý II/2029", "2029-04-01", "2029-06-30");
    MvcResult progress = perform(get(base + "/lock"));
    assertThat(progress.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(progress, "/periods/{id}/lock", PathItem.HttpMethod.GET);
    JsonNode p = body(progress);
    assertThat(p.get("cancellable").asBoolean()).isTrue();
    UUID orderId = UUID.fromString(p.get("lockOrder").get("id").asText());
    assertThat(p.get("lockOrder").get("rowVersion").asInt()).isEqualTo(2);

    // Tạm dừng: thiếu If-Match 428, sai phiên bản 409, thiếu MFA 401, đúng thì 200.
    assertThat(perform(postJson(base + "/lock/pause", "{}")).getResponse().getStatus())
        .isEqualTo(428);
    MvcResult stale = perform(postJson(base + "/lock/pause", "{}").header("If-Match", "\"1\""));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    as(admin, false);
    assertThat(
            perform(postJson(base + "/lock/pause", "{}").header("If-Match", "\"2\""))
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    as(admin, true);
    MvcResult paused =
        perform(
            postJson(base + "/lock/pause", "{\"comment\":\"Rà soát trọng số\"}")
                .header("If-Match", "\"2\""));
    assertThat(paused.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(paused, "/periods/{id}/lock/pause", PathItem.HttpMethod.POST);
    assertThat(paused.getResponse().getHeader("ETag")).isEqualTo("\"3\"");
    assertThat(body(paused).get("warnings").toString()).contains("tạm dừng");
    MvcResult resumed =
        perform(
            post(base + "/lock/resume")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"3\""));
    assertThat(resumed.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(resumed, "/periods/{id}/lock/resume", PathItem.HttpMethod.POST);
    assertThat(resumed.getResponse().getHeader("ETag")).isEqualTo("\"4\"");

    JdbcClient jdbc = JdbcClient.create(dataSource);
    Integer queued =
        TenantContext.call(
            tenant,
            () ->
                new TransactionTemplate(tm)
                    .execute(
                        s ->
                            jdbc.sql(
                                    "SELECT count(*) FROM config.period_lock_command"
                                        + " WHERE lock_order_id = ? AND status = 'CHO'")
                                .param(orderId)
                                .query(Integer.class)
                                .single()));
    assertThat(queued).isEqualTo(2);

    // Chạy lại khi chưa có hồ sơ lỗi: 409.
    MvcResult noFailure = perform(postJson(base + "/lock/retry-failed", "{}"));
    assertThat(noFailure.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(noFailure).get("code").asText()).isEqualTo("PERIOD-409-NO-FAILURE");

    // Bản chiếu lô và hồ sơ lỗi (do bộ nhận phản hồi saga ghi, DV-06-06).
    UUID batch = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    inTenant(
        () -> {
          projections.upsertBatch(
              tenant, orderId, new LockBatch(batch, null, 500, "XONG", 1, Instant.now()));
          projections.upsertBatch(
              tenant, orderId, new LockBatch(UUID.randomUUID(), null, 300, "LOI", 3, null));
          projections.upsertFailure(
              tenant,
              orderId,
              new LockFailure(
                  profile,
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  batch,
                  "Thiếu phân công người chấm",
                  3,
                  Instant.now()));
        });
    MvcResult batches = perform(get(base + "/lock/batches").param("limit", "1"));
    assertThat(batches.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(batches, "/periods/{id}/lock/batches", PathItem.HttpMethod.GET);
    assertThat(body(batches).get("items")).hasSize(1);
    assertThat(body(batches).get("nextCursor").isNull()).isFalse();
    MvcResult nextPage =
        perform(
            get(base + "/lock/batches")
                .param("limit", "1")
                .param("cursor", body(batches).get("nextCursor").asText()));
    assertThat(body(nextPage).get("items")).hasSize(1);
    assertThat(body(perform(get(base + "/lock/batches").param("status", "LOI"))).get("items"))
        .hasSize(1);
    MvcResult failures = perform(get(base + "/lock/failures"));
    assertResponseConforms(failures, "/periods/{id}/lock/failures", PathItem.HttpMethod.GET);
    assertThat(body(failures).get("items").get(0).get("profileId").asText())
        .isEqualTo(profile.toString());
    JsonNode withTotals = body(perform(get(base + "/lock")));
    assertThat(withTotals.get("totals").get("profiles").asInt()).isEqualTo(800);
    assertThat(withTotals.get("totals").get("failed").asInt()).isEqualTo(1);

    MvcResult retry =
        perform(postJson(base + "/lock/retry-failed", "{\"profileIds\":[\"" + profile + "\"]}"));
    assertThat(retry.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(retry, "/periods/{id}/lock/retry-failed", PathItem.HttpMethod.POST);
    String retryJob = body(retry).get("id").asText();
    assertThat(retry.getResponse().getHeader("Location")).endsWith("/jobs/" + retryJob);
    // Nguồn /api/v1/jobs/{id}: chỉ người khởi tạo thấy (JobsController lấy người gọi từ JWT).
    Job retryTracked = job(ActorId.of(admin), retryJob).orElseThrow();
    assertThat(retryTracked.type()).isEqualTo("PERIOD_LOCK_RETRY");
    assertThat(retryTracked.status()).isEqualTo(JobStatus.CHO);
    assertThat(job(ActorId.of(second), retryJob)).isEmpty();

    MvcResult events = perform(get(base + "/lock/events").param("limit", "2"));
    assertThat(events.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(events, "/periods/{id}/lock/events", PathItem.HttpMethod.GET);
    assertThat(body(events).get("items")).hasSize(2);
    assertThat(body(events).get("items").get(0).get("event").asText())
        .startsWith("Chạy lại hồ sơ lỗi");
    MvcResult olderEvents =
        perform(
            get(base + "/lock/events")
                .param("limit", "2")
                .param("cursor", body(events).get("nextCursor").asText()));
    assertThat(body(olderEvents).get("items").get(0).get("event").asText()).startsWith("Tạm dừng");

    MvcResult diagram = perform(get(base + "/lock/diagram"));
    assertThat(diagram.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(diagram, "/periods/{id}/lock/diagram", PathItem.HttpMethod.GET);
    assertThat(body(diagram).get("bpmnXml").asText()).contains("buoc4KySo");

    // Đề nghị huỷ: lý do ngắn 422; đúng thì chờ người duyệt thứ hai.
    MvcResult shortReason =
        perform(
            postJson(base + "/lock/cancel", "{\"reason\":\"ngắn\"}").header("If-Match", "\"4\""));
    assertThat(shortReason.getResponse().getStatus()).isEqualTo(422);
    MvcResult requested =
        perform(
            postJson(
                    base + "/lock/cancel",
                    "{\"reason\":\"Phát hiện sai sót cấu hình trọng số khối 3.\"}")
                .header("If-Match", "\"4\""));
    assertThat(requested.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(requested, "/periods/{id}/lock/cancel", PathItem.HttpMethod.POST);
    JsonNode cancel = body(requested).get("cancelRequest");
    assertThat(cancel.get("requestedBy").get("id").asText()).isEqualTo(admin.toString());
    assertThat(requested.getResponse().getHeader("ETag")).isEqualTo("\"5\"");

    // Bốn mắt: người đề nghị không tự duyệt.
    MvcResult self =
        perform(postJson(base + "/lock/cancel/approve", "{}").header("If-Match", "\"5\""));
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("PERIOD-403-SAME-PERSON");
    as(second, true);
    MvcResult approved =
        perform(
            postJson(base + "/lock/cancel/approve", "{\"comment\":\"Đồng ý huỷ\"}")
                .header("If-Match", "\"5\""));
    assertThat(approved.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(approved, "/periods/{id}/lock/cancel/approve", PathItem.HttpMethod.POST);
    String unlockJob = body(approved).get("id").asText();
    assertThat(job(ActorId.of(second), unlockJob).orElseThrow().type()).isEqualTo("PERIOD_UNLOCK");
    JsonNode compensating = body(perform(get(base + "/lock")));
    assertThat(compensating.get("cancellable").asBoolean()).isFalse();
    assertThat(compensating.get("sagaStatus").asText()).isEqualTo("DANG_BU_TRU");
    MvcResult notRunning =
        perform(postJson(base + "/lock/pause", "{}").header("If-Match", "\"6\""));
    assertThat(notRunning.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(notRunning).get("code").asText()).isEqualTo("PERIOD-409-SAGA-NOT-RUNNING");

    // Saga bù trừ xong: lệnh DA_HUY, tác vụ bù trừ hoàn tất.
    assertThat(TenantContext.call(tenant, () -> control.completeCancel(tenant, orderId, second)))
        .isTrue();
    assertThat(TenantContext.call(tenant, () -> control.completeCancel(tenant, orderId, second)))
        .isFalse();
    assertThat(job(ActorId.of(second), unlockJob).orElseThrow().status())
        .isEqualTo(JobStatus.HOAN_TAT);
  }
}
