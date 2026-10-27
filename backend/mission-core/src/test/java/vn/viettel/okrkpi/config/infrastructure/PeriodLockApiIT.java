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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.PeriodLockSaga;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-05 trên PostgreSQL thật: lập, duyệt, khởi chạy và xem tiến độ lệnh khoá
 * kỳ khớp {@code config.yaml}; tự duyệt bị chặn, thiếu MFA bước lên bị chặn, gửi lại cùng khoá trả
 * cùng job, khởi chạy lần hai khi saga đang chạy trả 409. Saga SVC-06 thay bằng bản kiểm thử.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
class PeriodLockApiIT extends ContractConformanceTest {

  static final List<String> SAGA_KEYS = new CopyOnWriteArrayList<>();
  static final List<String> NOTICES = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class LockSagaConfig {

    @Bean
    @Primary
    PeriodLockSaga testPeriodLockSaga() {
      return (tenant, periodId, orderId, requestedBy, key) -> {
        if (SAGA_KEYS.contains(key)) {
          throw new PeriodLockSaga.SagaExistsException(null);
        }
        SAGA_KEYS.add(key);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
      };
    }

    @Bean
    @Primary
    LockOrderNotices testLockOrderNotices() {
      return new LockOrderNotices() {
        @Override
        public void requested(PeriodLockOrder order, String periodName, List<UUID> recipients) {
          NOTICES.add("requested");
        }

        @Override
        public void approved(PeriodLockOrder order, String periodName, List<UUID> recipients) {
          NOTICES.add("approved");
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

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID admin = PeriodApiIT.CallerConfig.ACTOR;
  private final UUID leader = UUID.randomUUID();

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

  private static MockHttpServletRequestBuilder postJson(String path, String key, String body) {
    return post(path)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  @Test
  void createApproveStartAndProgressFollowSeparationOfDutiesAndContract() throws Exception {
    as(admin, true);
    MvcResult period =
        perform(
            postJson(
                "/api/v1/periods",
                UUID.randomUUID().toString(),
                """
                {"code":"2028-Q2","name":"Quý II/2028","kind":"QUY","flow":"CA_HAI",\
                "startDate":"2028-04-01","endDate":"2028-06-30"}
                """));
    assertThat(period.getResponse().getStatus()).isEqualTo(201);
    String id = body(period).get("id").asText();
    String base = "/api/v1/periods/" + id;

    // Chưa có lệnh: không có tiến độ.
    assertThat(perform(get(base + "/lock")).getResponse().getStatus()).isEqualTo(404);

    String planned = Instant.now().plus(10, ChronoUnit.DAYS).toString();
    String createBody =
        """
        {"basisDoc":"Thông báo 21-TB/ĐUK","plannedLockAt":"%s"}
        """
            .formatted(planned);
    String createKey = UUID.randomUUID().toString();
    MvcResult created = perform(postJson(base + "/lock-orders", createKey, createBody));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/periods/{id}/lock-orders", PathItem.HttpMethod.POST);
    JsonNode order = body(created);
    String oid = order.get("id").asText();
    assertThat(order.get("status").asText()).isEqualTo("CHO_DUYET");
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(NOTICES).contains("requested");
    // Gửi lại cùng khoá trả cùng lệnh; khoá khác thì đã có lệnh.
    assertThat(body(perform(postJson(base + "/lock-orders", createKey, createBody))).get("id"))
        .isEqualTo(order.get("id"));
    MvcResult dup =
        perform(postJson(base + "/lock-orders", UUID.randomUUID().toString(), createBody));
    assertThat(dup.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(dup).get("code").asText()).isEqualTo("PERIOD-409-ORDER-EXISTS");

    // Kỳ đọc lại mang lệnh hiện hành (SCR-0801 hiện người lập và "chờ phê duyệt", QA-06 HT-23)
    MvcResult withOrder = perform(get("/api/v1/periods/" + id));
    assertResponseConforms(withOrder, "/periods/{id}", PathItem.HttpMethod.GET);
    assertThat(body(withOrder).get("currentLockOrder").get("id").asText()).isEqualTo(oid);
    assertThat(body(withOrder).get("currentLockOrder").get("status").asText())
        .isEqualTo("CHO_DUYET");

    // Tự duyệt bị chặn.
    String approve = base + "/lock-orders/" + oid + "/approve";
    MvcResult self =
        perform(postJson(approve, UUID.randomUUID().toString(), "{}").header("If-Match", "\"0\""));
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("PERIOD-403-SAME-PERSON");

    // Thiếu MFA bước lên bị chặn.
    as(leader, false);
    MvcResult noMfa =
        perform(postJson(approve, UUID.randomUUID().toString(), "{}").header("If-Match", "\"0\""));
    assertThat(noMfa.getResponse().getStatus()).isEqualTo(401);

    as(leader, true);
    MvcResult approved =
        perform(
            postJson(approve, UUID.randomUUID().toString(), "{\"comment\":\"Đồng ý\"}")
                .header("If-Match", "\"0\""));
    assertThat(approved.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        approved, "/periods/{id}/lock-orders/{oid}/approve", PathItem.HttpMethod.POST);
    assertThat(body(approved).get("status").asText()).isEqualTo("DA_DUYET");
    String key = body(approved).get("idempotencyKey").asText();
    assertThat(key).isEqualTo("CK-2028Q2");

    // Người duyệt không được khởi chạy.
    String startBody = "{\"lockOrderId\":\"" + oid + "\"}";
    MvcResult sameApprover =
        perform(postJson(base + "/lock", key, startBody).header("If-Match", "\"1\""));
    assertThat(sameApprover.getResponse().getStatus()).isEqualTo(403);

    as(admin, true);
    MvcResult started =
        perform(postJson(base + "/lock", key, startBody).header("If-Match", "\"1\""));
    assertThat(started.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(started, "/periods/{id}/lock", PathItem.HttpMethod.POST);
    String jobId = body(started).get("id").asText();
    assertThat(started.getResponse().getHeader("Location")).endsWith("/jobs/" + jobId);

    // Gửi lại cùng khoá trả cùng job, không gọi saga lần hai.
    MvcResult replay = perform(postJson(base + "/lock", key, startBody));
    assertThat(replay.getResponse().getStatus()).isEqualTo(202);
    assertThat(body(replay).get("id").asText()).isEqualTo(jobId);
    assertThat(SAGA_KEYS).containsOnlyOnce(key);

    // Khởi chạy lần hai với khoá khác khi saga đang chạy: 409.
    MvcResult again = perform(postJson(base + "/lock", key + "-2", startBody));
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("PERIOD-409-SAGA-RUNNING");

    MvcResult progress = perform(get(base + "/lock"));
    assertThat(progress.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(progress, "/periods/{id}/lock", PathItem.HttpMethod.GET);
    assertThat(body(progress).get("steps")).hasSize(6);
  }
}
