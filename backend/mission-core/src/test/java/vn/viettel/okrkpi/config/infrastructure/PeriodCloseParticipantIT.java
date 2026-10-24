package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.application.PeriodLockProgressService;
import vn.viettel.okrkpi.config.infrastructure.persistence.OutboxPeriodEvents;
import vn.viettel.okrkpi.shared.infrastructure.web.InMemoryJobRegistry;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp QA-06 trên PostgreSQL thật: MOD-08 là bên tham gia saga chốt kỳ qua cổng nội bộ
 * (tiền điều kiện, {@code KHOA_KY}, {@code HOAN_TAT_KY}, bù trừ) và chiếu tiến độ saga sang {@code
 * config.period_lock_step} cùng trạng thái lệnh. Saga SVC-06 thay bằng bản kiểm thử; outbox của kỳ
 * dùng bộ tuần tự giả để không cần Apicurio.
 */
@SpringBootTest(
    classes = {MissionCoreApplication.class, PeriodCloseParticipantIT.Config.class},
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
class PeriodCloseParticipantIT {

  /**
   * Nạp qua {@code classes} (không mang {@code @TestConfiguration}) để không lọt sang ngữ cảnh của
   * kiểm thử khác; saga và thông báo dùng bản kiểm thử của {@link PeriodLockApiIT}.
   */
  static class Config {

    @Bean
    @Primary
    OutboxPeriodEvents participantTestPeriodEvents(
        @Qualifier("configDataSource") DataSource dataSource, ObjectMapper json) {
      return new OutboxPeriodEvents(
          new OutboxWriter(
              new JdbcTemplate(dataSource),
              "config",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()));
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PeriodLockProgressService progress;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID admin = PeriodApiIT.CallerConfig.ACTOR;
  private final UUID leader = UUID.randomUUID();

  @AfterEach
  void resetCaller() {
    PeriodApiIT.CallerConfig.reset();
    InMemoryJobRegistry.clearActorOverride();
  }

  /** {@code /jobs} đọc người gọi từ JWT; MockMvc không có JWT nên đặt người gọi trực tiếp. */
  private MvcResult job(UUID id) throws Exception {
    InMemoryJobRegistry.setActorOverride(ActorId.of(admin));
    return perform(get("/api/v1/jobs/" + id));
  }

  private void as(UUID user) {
    PeriodApiIT.CallerConfig.current = user;
    PeriodApiIT.CallerConfig.acr = "aal2";
    PeriodApiIT.CallerConfig.authTime = Instant.now().getEpochSecond() - 30;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /** Kỳ mở có lệnh khoá đã khởi chạy; trả mã kỳ, mã lệnh và mã saga. */
  private String[] startedLock(String code, String start, String end) throws Exception {
    as(admin);
    String id =
        body(perform(
                json(
                    post("/api/v1/periods"),
                    """
                    {"code":"%s","name":"Kỳ %s","kind":"QUY","flow":"CA_HAI",\
                    "startDate":"%s","endDate":"%s"}
                    """
                        .formatted(code, code, start, end))))
            .get("id")
            .asText();
    String base = "/api/v1/periods/" + id;
    String planned = Instant.now().plus(10, ChronoUnit.DAYS).toString();
    String oid =
        body(perform(
                json(
                    post(base + "/lock-orders"),
                    "{\"basisDoc\":\"Thông báo 21-TB/ĐUK\",\"plannedLockAt\":\"%s\"}"
                        .formatted(planned))))
            .get("id")
            .asText();
    as(leader);
    JsonNode approved =
        body(
            perform(
                json(post(base + "/lock-orders/" + oid + "/approve"), "{}")
                    .header("If-Match", "\"0\"")));
    as(admin);
    MvcResult started =
        perform(
            post(base + "/lock")
                .header("Idempotency-Key", approved.get("idempotencyKey").asText())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lockOrderId\":\"" + oid + "\"}"));
    assertThat(started.getResponse().getStatus()).isEqualTo(202);
    String saga = body(started).get("id").asText();
    return new String[] {id, oid, saga};
  }

  private String status(String periodId) throws Exception {
    return body(perform(get("/api/v1/periods/" + periodId))).get("status").asText();
  }

  @Test
  void sagaLocksPeriodThroughInternalPortAndProgressIsProjected() throws Exception {
    String[] s = startedLock("2029-Q1", "2029-01-01", "2029-03-31");
    String periodId = s[0];
    UUID orderId = UUID.fromString(s[1]);
    UUID sagaId = UUID.fromString(s[2]);
    String close = "/internal/v1/config/periods/" + periodId + "/close";
    String cmd =
        "{\"command\":\"%s\",\"sagaId\":\"" + sagaId + "\",\"lockOrderId\":\"" + orderId + "\"}";

    // Bước 0: tiền điều kiện đạt; saga lạ thì vi phạm.
    MvcResult pre =
        perform(
            json(
                post(close + "/preconditions"),
                "{\"sagaId\":\"" + sagaId + "\",\"lockOrderId\":\"" + orderId + "\"}"));
    assertThat(pre.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(pre).get("violations")).isEmpty();
    JsonNode stranger =
        body(
            perform(
                json(
                    post(close + "/preconditions"),
                    "{\"sagaId\":\""
                        + UUID.randomUUID()
                        + "\",\"lockOrderId\":\""
                        + orderId
                        + "\"}")));
    assertThat(stranger.get("violations")).hasSize(1);

    // Tác vụ PERIOD_LOCK trả lúc khởi chạy đọc được qua /jobs (mã saga).
    MvcResult job0 = job(sagaId);
    assertThat(job0.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(job0).get("status").asText()).isEqualTo("DANG_CHAY");
    assertThat(body(job0).get("type").asText()).isEqualTo("PERIOD_LOCK");

    // KHOA_KY: kỳ sang DANG_KHOA, giao lại không lỗi, trạng thái tác vụ HOAN_TAT.
    UUID job = UUID.randomUUID();
    MvcResult lock = perform(json(put(close + "/jobs/" + job), cmd.formatted("KHOA_KY")));
    assertThat(lock.getResponse().getStatus()).isEqualTo(202);
    assertThat(status(periodId)).isEqualTo("DANG_KHOA");
    assertThat(
            perform(json(put(close + "/jobs/" + job), cmd.formatted("KHOA_KY")))
                .getResponse()
                .getStatus())
        .isEqualTo(202);
    assertThat(
            body(perform(get(close + "/jobs/" + job).param("command", "KHOA_KY")))
                .get("status")
                .asText())
        .isEqualTo("HOAN_TAT");
    assertThat(
            body(perform(get(close + "/jobs/" + job).param("command", "HOAN_TAT_KY")))
                .get("status")
                .asText())
        .isEqualTo("CHUA_CO");

    // Bản chiếu: bước 1 đạt, bước 2 đang chạy rồi đạt qua trả lời.
    Instant at = Instant.now();
    TenantContext.run(
        tenant,
        () -> {
          progress.apply(tenant, sagaId, "BAT_DAU_BUOC", 1, true, admin, at);
          progress.apply(tenant, sagaId, "BUOC_XONG", 1, true, admin, at);
          progress.apply(tenant, sagaId, "CHO_TRA_LOI", 2, true, admin, at);
        });
    JsonNode steps = body(perform(get("/api/v1/periods/" + periodId + "/lock"))).get("steps");
    assertThat(steps.get(0).get("status").asText()).isEqualTo("DAT");
    assertThat(steps.get(1).get("status").asText()).isEqualTo("DANG_CHAY");
    assertThat(steps.get(2).get("status").asText()).isEqualTo("CHO");
    TenantContext.run(
        tenant, () -> progress.apply(tenant, sagaId, "NHAN_TRA_LOI", 2, true, admin, at));
    steps = body(perform(get("/api/v1/periods/" + periodId + "/lock"))).get("steps");
    assertThat(steps.get(1).get("status").asText()).isEqualTo("DAT");
    assertThat(steps.get(1).get("startedAt").isNull()).isFalse();

    // HOAN_TAT_KY: kỳ DA_KHOA; saga hoàn tất thì lệnh HOAN_TAT.
    UUID done = UUID.randomUUID();
    assertThat(
            perform(json(put(close + "/jobs/" + done), cmd.formatted("HOAN_TAT_KY")))
                .getResponse()
                .getStatus())
        .isEqualTo(202);
    assertThat(status(periodId)).isEqualTo("DA_KHOA");
    TenantContext.run(
        tenant, () -> progress.apply(tenant, sagaId, "HOAN_TAT", null, true, admin, at));
    JsonNode lockView = body(perform(get("/api/v1/periods/" + periodId + "/lock")));
    assertThat(lockView.get("lockOrder").get("status").asText()).isEqualTo("HOAN_TAT");
    assertThat(lockView.get("sagaStatus").asText()).isEqualTo("HOAN_TAT");
    assertThat(body(job(sagaId)).get("status").asText()).isEqualTo("HOAN_TAT");
    // Saga lạ không đổi gì; KHOA_KY khi kỳ đã khoá là không làm gì.
    assertThat(
            TenantContext.call(
                tenant,
                () -> progress.apply(tenant, UUID.randomUUID(), "HOAN_TAT", null, true, admin, at)))
        .isFalse();
    assertThat(
            perform(json(put(close + "/jobs/" + UUID.randomUUID()), cmd.formatted("KHOA_KY")))
                .getResponse()
                .getStatus())
        .isEqualTo(202);
  }

  @Test
  void undoReturnsLockingPeriodToOpen() throws Exception {
    String[] s = startedLock("2029-Q2", "2029-04-01", "2029-06-30");
    String close = "/internal/v1/config/periods/" + s[0] + "/close";
    String cmd =
        "{\"command\":\"%s\",\"sagaId\":\"" + s[2] + "\",\"lockOrderId\":\"" + s[1] + "\"%s}";
    perform(json(put(close + "/jobs/" + UUID.randomUUID()), cmd.formatted("KHOA_KY", "")));
    assertThat(status(s[0])).isEqualTo("DANG_KHOA");

    // Bù trừ KHOA_KY đưa kỳ về MO, gọi lại không lỗi; HOAN_TAT_KY khi kỳ đang mở là 409.
    String undo = cmd.formatted("KHOA_KY", ",\"undoKey\":\"" + UUID.randomUUID() + "\"");
    assertThat(perform(json(post(close + "/undo"), undo)).getResponse().getStatus()).isEqualTo(204);
    assertThat(status(s[0])).isEqualTo("MO");
    assertThat(perform(json(post(close + "/undo"), undo)).getResponse().getStatus()).isEqualTo(204);
    MvcResult early =
        perform(json(put(close + "/jobs/" + UUID.randomUUID()), cmd.formatted("HOAN_TAT_KY", "")));
    assertThat(early.getResponse().getStatus()).isEqualTo(409);
    assertThat(status(s[0])).isEqualTo("MO");
  }
}
