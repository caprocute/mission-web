package vn.viettel.okrkpi.dossier.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.infrastructure.persistence.OutboxMeetingNotices;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-06-02 trên PostgreSQL thật: năm thao tác hội nghị kiểm điểm khớp {@code
 * dossiers.yaml}, phạm vi đơn vị và thành phần, gửi dự thảo đếm ngày làm việc theo ví dụ TL-02b và
 * ghi EVT-21 vào outbox, sửa kế hoạch có {@code If-Match}, chặn khi đã họp và khi kỳ khoá.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@Import(DossierMeetingApiIT.NoticeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DossierMeetingApiIT extends ContractConformanceTest {

  static final UUID OFFICER = UUID.fromString("01927a06-0000-7000-8000-0000000000a7");
  static final UUID CHAIR = UUID.fromString("01927a06-0000-7000-8000-0000000000c1");
  static final UUID SECRETARY = UUID.fromString("01927a06-0000-7000-8000-0000000000c2");
  static final UUID MEMBER = UUID.fromString("01927a06-0000-7000-8000-0000000000c4");
  static final UUID OUTSIDER = UUID.fromString("01927a06-0000-7000-8000-0000000000c9");
  static final UUID UNIT = UUID.fromString("01927a06-0000-7000-8000-0000000000f1");
  static final UUID OTHER_UNIT = UUID.fromString("01927a06-0000-7000-8000-0000000000f9");

  static final ThreadLocal<UUID> USER = ThreadLocal.withInitial(() -> OFFICER);
  static final ThreadLocal<UUID> USER_UNIT = ThreadLocal.withInitial(() -> UNIT);

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @MockitoBean private DossierCaller caller;

  /** Cây tổ chức giả: không có phân công nên không chặn theo đơn vị. */
  @MockitoBean private OrgDirectoryApi orgDirectory;

  /**
   * Môi trường kiểm thử không có Apicurio Registry: thay bộ tuần tự Avro của EVT-21 bằng bản giả.
   */
  /** Lớp thường nhập bằng {@code @Import}: {@code @TestConfiguration} lồng bị quét sang IT khác. */
  static class NoticeConfig {

    @Bean
    @Primary
    MeetingNotices testMeetingNotices(
        @Qualifier("dossierDataSource") DataSource dataSource, ObjectMapper json) {
      return new OutboxMeetingNotices(
          new OutboxWriter(
              new JdbcTemplate(dataSource),
              "dossier",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()),
          json);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Autowired
  @Qualifier("dossierDataSource") private DataSource dossierDataSource;

  @Autowired
  @Qualifier("dossierTransactionManager") private PlatformTransactionManager dossierTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID year = UUID.randomUUID();
  private final UUID locked = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.DOSSIERS;
  }

  @BeforeEach
  void caller() {
    USER.remove();
    USER_UNIT.remove();
    when(caller.currentUserId()).thenAnswer(inv -> Optional.of(USER.get()));
    when(caller.currentUnitId()).thenAnswer(inv -> Optional.ofNullable(USER_UNIT.get()));
  }

  @BeforeAll
  void seed() throws Exception {
    insertPeriod(year, "Năm 2026", "2026-01-01", "2026-12-31", 20260, "MO");
    insertPeriod(locked, "Năm 2025", "2025-01-01", "2025-12-31", 20250, "DA_KHOA");
    inTenant(
        jdbc -> {
          for (int bucket : List.of(20250, 20260)) {
            jdbc.sql("SELECT dossier.fn_create_period_partitions(?::regclass, ?)")
                .params("dossier.review_meeting", bucket)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @Test
  void taoXemDanhSachKhopHopDongVaPhatLai() throws Exception {
    UUID subject = UUID.randomUUID();
    ObjectNode req = createBody(year, subject);
    MvcResult created = create(req, "tao-" + subject);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/dossiers/meetings", PathItem.HttpMethod.POST);
    JsonNode body = body(created);
    UUID id = UUID.fromString(body.get("id").asText());
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(created.getResponse().getHeader("Location")).endsWith("/dossiers/meetings/" + id);
    assertThat(body.get("status").asText()).isEqualTo("DU_KIEN");
    assertThat(body.get("title").asText()).isEqualTo("Hội nghị kiểm điểm cuối năm 2026");
    assertThat(body.at("/deadlineCheck/workingDays").isNull()).isTrue();
    assertThat(body.at("/participants/2/role").asText()).isEqualTo("NGUOI_DUOC_KIEM_DIEM");
    assertThat(body.at("/participants/3/status").asText()).isEqualTo("CHUA_MO");
    assertThat(body.path("myRole").isNull() || body.path("myRole").isMissingNode()).isTrue();

    MvcResult replay = create(req, "tao-" + subject);
    assertThat(body(replay).get("id").asText()).isEqualTo(id.toString());
    MvcResult again = create(createBody(year, subject), "tao-lai-" + subject);
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("DOSSIER-409-MEETING-EXISTS");

    USER.set(MEMBER);
    USER_UNIT.set(OTHER_UNIT);
    MvcResult got = perform(get("/api/v1/dossiers/meetings/" + id));
    assertThat(got.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(got, "/dossiers/meetings/{id}", PathItem.HttpMethod.GET);
    assertThat(body(got).get("myRole").asText()).isEqualTo("THANH_VIEN");

    MvcResult page = perform(get("/api/v1/dossiers/meetings?period=" + year + "&limit=1"));
    assertThat(page.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(page, "/dossiers/meetings", PathItem.HttpMethod.GET);
    assertThat(body(page).at("/items/0/myRole").asText()).isEqualTo("THANH_VIEN");

    USER.set(OUTSIDER);
    MvcResult none = perform(get("/api/v1/dossiers/meetings?status=DU_KIEN"));
    assertThat(body(none).get("items")).isEmpty();
    assertThat(perform(get("/api/v1/dossiers/meetings?status=SAI")).getResponse().getStatus())
        .isEqualTo(422);
  }

  @Test
  void nguoiNgoaiDonViBi403() throws Exception {
    USER_UNIT.set(OTHER_UNIT);
    MvcResult denied = create(createBody(year, UUID.randomUUID()), UUID.randomUUID().toString());
    assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(denied).get("code").asText()).isEqualTo("COMMON-403-OUT-OF-SCOPE");

    USER_UNIT.set(UNIT);
    UUID id = createdId(UUID.randomUUID());
    USER.set(OUTSIDER);
    USER_UNIT.set(OTHER_UNIT);
    assertThat(perform(get("/api/v1/dossiers/meetings/" + id)).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(
            perform(get("/api/v1/dossiers/meetings/" + UUID.randomUUID()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void guiDuThaoDemNgayTheoViDuVaGhiEvt21() throws Exception {
    UUID subject = UUID.randomUUID();
    UUID id = createdId(subject);

    USER.set(MEMBER);
    MvcResult notSubject = send(id, "\"0\"");
    assertThat(notSubject.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(notSubject).get("code").asText()).isEqualTo("DOSSIER-403-NOT-SUBJECT");

    USER.set(subject);
    assertThat(
            perform(
                    post("/api/v1/dossiers/meetings/" + id + "/send-draft")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(428);
    MvcResult sent = send(id, "\"0\"");
    assertThat(sent.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(sent, "/dossiers/meetings/{id}/send-draft", PathItem.HttpMethod.POST);
    assertThat(body(sent).get("status").asText()).isEqualTo("DA_GUI_DU_THAO");
    assertThat(body(sent).at("/deadlineCheck/passed").asBoolean()).isTrue();
    assertThat(sent.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    String topics =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT string_agg(topic, ',') FROM dossier.outbox_event"
                            + " WHERE aggregate_id = ?")
                    .param(id)
                    .query(String.class)
                    .single());
    assertThat(topics).isEqualTo("notify.message.requested.v1");

    MvcResult twice = send(id, "\"1\"");
    assertThat(twice.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(twice).get("code").asText()).isEqualTo("DOSSIER-409-INVALID-STATE");

    // Ví dụ TL-02b: gửi 16:20 thứ Sáu 08/01/2027, họp thứ Năm 14/01/2027 là 3 ngày làm việc.
    setDraftSentAt(id, "2027-01-08T16:20:00+07:00");
    JsonNode example = body(perform(get("/api/v1/dossiers/meetings/" + id)));
    assertThat(example.at("/deadlineCheck/workingDays").asInt()).isEqualTo(3);
    assertThat(example.at("/deadlineCheck/passed").asBoolean()).isTrue();
    assertThat(example.at("/deadlineCheck/legalRef").asText()).isEqualTo("QĐ Điều 6");
    assertThat(example.get("draftSentAt").asText()).isEqualTo("2027-01-08T16:20:00+07:00");
  }

  @Test
  void suaKeHoachIfMatchLyDoGiuNgayVaDaHop() throws Exception {
    UUID subject = UUID.randomUUID();
    UUID id = createdId(subject);
    MvcResult moved = update(id, "\"0\"", "{\"location\":\"Hội trường lớn\"}");
    assertThat(moved.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(moved, "/dossiers/meetings/{id}", PathItem.HttpMethod.PATCH);
    assertThat(body(moved).get("location").asText()).isEqualTo("Hội trường lớn");
    MvcResult stale = update(id, "\"0\"", "{\"location\":\"Phòng khác\"}");
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(stale).get("code").asText()).isEqualTo("DOSSIER-409-VERSION-CONFLICT");

    USER.set(subject);
    assertThat(send(id, "\"1\"").getResponse().getStatus()).isEqualTo(200);
    // Gửi muộn: thứ Hai 11/01, chỉ còn 12 và 13/01.
    setDraftSentAt(id, "2027-01-11T16:20:00+07:00");
    String reason = "{\"deadlineOverrideReason\":\"Cấp uỷ thống nhất giữ ngày họp.\"}";
    USER.set(OFFICER);
    MvcResult notChair = update(id, "\"2\"", reason);
    assertThat(body(notChair).get("code").asText()).isEqualTo("DOSSIER-403-NOT-CHAIR");
    USER.set(CHAIR);
    USER_UNIT.set(null);
    MvcResult kept = update(id, "\"2\"", reason);
    assertThat(kept.getResponse().getStatus()).isEqualTo(200);
    JsonNode check = body(kept).get("deadlineCheck");
    assertThat(check.get("workingDays").asInt()).isEqualTo(2);
    assertThat(check.get("passed").asBoolean()).isFalse();
    assertThat(check.get("msg").asText()).isEqualTo("MSG-11-01");
    assertThat(check.get("overrideReason").asText()).isEqualTo("Cấp uỷ thống nhất giữ ngày họp.");

    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE dossier.review_meeting SET status = 'DA_HOP', meeting_at = now()"
                        + " WHERE id = ?")
                .param(id)
                .update());
    MvcResult held = update(id, "\"3\"", "{\"location\":\"Phòng họp số 2\"}");
    assertThat(held.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(held).get("code").asText()).isEqualTo("DOSSIER-409-MEETING-HELD");
    assertThat(update(id, "\"3\"", "{}").getResponse().getStatus()).isEqualTo(422);
    assertThat(update(UUID.randomUUID(), "\"0\"", "{\"location\":\"x\"}").getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void kyDaKhoaKhongLapHoiNghi() throws Exception {
    MvcResult r = create(createBody(locked, UUID.randomUUID()), UUID.randomUUID().toString());
    assertThat(r.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(r).get("code").asText()).isEqualTo("DOSSIER-409-PERIOD-LOCKED");
    ObjectNode invalid =
        createBody(year, UUID.randomUUID()).put("secretaryPersonId", CHAIR.toString());
    MvcResult bad = create(invalid, UUID.randomUUID().toString());
    assertThat(bad.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(bad).get("code").asText()).isEqualTo("DOSSIER-422-VALIDATION");
  }

  private ObjectNode createBody(UUID period, UUID subject) {
    ObjectNode req =
        json.createObjectNode()
            .put("periodId", period.toString())
            .put("orgUnitId", UNIT.toString())
            .put("meetingType", "KIEM_DIEM_NAM")
            .put("scheduledAt", "2027-01-14T08:00:00+07:00")
            .put("location", "Phòng họp tầng 3")
            .put("chairPersonId", CHAIR.toString())
            .put("secretaryPersonId", SECRETARY.toString())
            .put("subjectPersonId", subject.toString());
    req.putArray("participantPersonIds").add(MEMBER.toString());
    return req;
  }

  private UUID createdId(UUID subject) throws Exception {
    MvcResult r = create(createBody(year, subject), UUID.randomUUID().toString());
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    return UUID.fromString(body(r).get("id").asText());
  }

  private MvcResult create(ObjectNode req, String key) throws Exception {
    return perform(
        post("/api/v1/dossiers/meetings")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(req.toString()));
  }

  private MvcResult update(UUID id, String ifMatch, String body) throws Exception {
    return perform(
        patch("/api/v1/dossiers/meetings/" + id)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private MvcResult send(UUID id, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/dossiers/meetings/" + id + "/send-draft")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch));
  }

  private void setDraftSentAt(UUID id, String at) throws Exception {
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE dossier.review_meeting SET draft_sent_at = CAST(? AS timestamptz)"
                        + " WHERE id = ?")
                .params(at, id)
                .update());
  }

  private void insertPeriod(
      UUID id, String name, String start, String end, int bucket, String status) {
    JdbcClient jdbc = JdbcClient.create(configDataSource);
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .executeWithoutResult(
                    s ->
                        jdbc.sql(
                                "INSERT INTO config.period (id, code, name, kind, flow,"
                                    + " start_date, end_date, period_bucket, status, locked_at,"
                                    + " created_by, updated_by) VALUES (?, ?, ?, 'NAM', 'DANG',"
                                    + " ?::date, ?::date, ?, ?, CASE WHEN ? = 'DA_KHOA' THEN now()"
                                    + " END, ?, ?)")
                            .params(
                                id,
                                "N-" + id.toString().substring(0, 8),
                                name,
                                start,
                                end,
                                bucket,
                                status,
                                status,
                                OFFICER,
                                OFFICER)
                            .update()));
  }

  private <T> T inTenant(java.util.function.Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dossierDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(dossierTm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    String user = USER.get().toString();
    return TenantContext.call(tenant, () -> mvc.perform(request.principal(() -> user)).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
