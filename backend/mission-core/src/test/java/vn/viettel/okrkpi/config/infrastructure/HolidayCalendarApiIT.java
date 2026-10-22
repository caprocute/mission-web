package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-04 trên PostgreSQL thật: bốn thao tác lịch nghỉ và {@code
 * getWorkingCalendar} khớp {@code config.yaml}; trùng ngày 409; {@code If-Match} thiếu 428, lệch
 * 409; gửi lại cùng Idempotency-Key trả cùng ngày; {@code WorkingCalendarApi} thấy ngay thay đổi.
 * Người gọi là {@code PeriodApiIT.CallerConfig.ACTOR} (cấu hình kiểm thử lồng được quét vào mọi ngữ
 * cảnh).
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "mission.config.period-opening.enabled=false"})
@AutoConfigureMockMvc(addFilters = false)
class HolidayCalendarApiIT extends ContractConformanceTest {

  private static final String DAYS = "/api/v1/config/holiday-calendars";

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private WorkingCalendarApi workingCalendar;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = PeriodApiIT.CallerConfig.ACTOR;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  @Test
  void crudAndCalendarConformToContract() throws Exception {
    MvcResult cal = perform(get("/api/v1/config/calendar").param("year", "2026"));
    assertThat(cal.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(cal, "/config/calendar", PathItem.HttpMethod.GET);
    String etag = cal.getResponse().getHeader("ETag");
    assertThat(body(cal).get("workingDaysByMonth").get(1).asInt()).isEqualTo(16);
    assertThat(
            perform(
                    get("/api/v1/config/calendar")
                        .param("year", "2026")
                        .header("If-None-Match", etag))
                .getResponse()
                .getStatus())
        .isEqualTo(304);

    String key = UUID.randomUUID().toString();
    String day = input("2026-12-26", "LAM_BU", "Làm bù cho ngày nghỉ 02/01/2027");
    MvcResult created = perform(create(key, day));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/config/holiday-calendars", PathItem.HttpMethod.POST);
    String id = body(created).get("id").asText();
    assertThat(created.getResponse().getHeader("Location")).isEqualTo(DAYS + "/" + id);
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(body(perform(create(key, day))).get("id").asText()).isEqualTo(id);
    MvcResult dup = perform(create(UUID.randomUUID().toString(), day));
    assertThat(dup.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(dup).get("code").asText()).isEqualTo("CONFIG-409-DATE-EXISTS");
    MvcResult invalid =
        perform(create(UUID.randomUUID().toString(), input("2026-12-28", "LAM_BU", "x")));
    assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
    assertThat(
            as(() -> workingCalendar.isWorkingDay(tenant, java.time.LocalDate.parse("2026-12-26"))))
        .isTrue();

    MvcResult page =
        perform(get(DAYS).param("year", "2026").param("limit", "10").param("scope", "TENANT"));
    assertThat(page.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(page, "/config/holiday-calendars", PathItem.HttpMethod.GET);
    assertThat(body(page).get("items")).hasSize(1);
    MvcResult first = perform(get(DAYS).param("year", "2026").param("limit", "10"));
    assertThat(body(first).get("items")).hasSize(10);
    String cursor = body(first).get("nextCursor").asText();
    JsonNode second =
        body(perform(get(DAYS).param("year", "2026").param("limit", "10").param("cursor", cursor)));
    assertThat(second.get("items")).hasSize(5);
    assertThat(second.get("nextCursor").isNull()).isTrue();

    String change = input("2026-12-26", "LAM_BU", "Làm bù dịp Tết Dương lịch");
    assertThat(perform(update(id, null, change)).getResponse().getStatus()).isEqualTo(428);
    MvcResult stale = perform(update(id, "\"7\"", change));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(stale).get("code").asText()).isEqualTo("CONFIG-409-VERSION-CONFLICT");
    MvcResult updated = perform(update(id, "\"0\"", change));
    assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(updated, "/config/holiday-calendars/{id}", PathItem.HttpMethod.PUT);
    assertThat(updated.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(
            perform(update(UUID.randomUUID().toString(), "\"0\"", change))
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    assertThat(perform(remove(id, "\"0\"")).getResponse().getStatus()).isEqualTo(409);
    MvcResult deleted = perform(remove(id, "\"1\""));
    assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
    assertThat(perform(remove(id, "\"1\"")).getResponse().getStatus()).isEqualTo(404);
    assertThat(
            perform(get("/api/v1/config/calendar").param("year", "2026"))
                .getResponse()
                .getHeader("ETag"))
        .isEqualTo(etag);
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return as(() -> mvc.perform(request.principal(actor::toString)).andReturn());
  }

  private <T> T as(Callable<T> work) throws Exception {
    return TenantContext.call(tenant, work);
  }

  private static MockHttpServletRequestBuilder create(String key, String body) {
    return post(DAYS)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static MockHttpServletRequestBuilder update(String id, String ifMatch, String body) {
    MockHttpServletRequestBuilder b =
        put(DAYS + "/" + id)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  private static MockHttpServletRequestBuilder remove(String id, String ifMatch) {
    return delete(DAYS + "/" + id)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("If-Match", ifMatch);
  }

  private static String input(String date, String kind, String name) {
    return """
        {"calendarDate":"%s","dayKind":"%s","name":"%s"}
        """
        .formatted(date, kind, name);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
