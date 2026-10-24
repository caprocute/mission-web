package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;
import vn.viettel.okrkpi.org.application.port.IntegrationGatewayPort;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;
import vn.viettel.okrkpi.org.application.sync.DiscrepancyService;
import vn.viettel.okrkpi.org.application.sync.ResolveResult;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;
import vn.viettel.okrkpi.org.support.AesGcmTestFieldEncryptor;
import vn.viettel.okrkpi.org.support.ContractValidator;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * DV-04-07 · QT-01, SCR-0104: nhận lô đồng bộ từ integration-gateway, sinh chênh lệch đủ ba mức, xử lý hàng loạt,
 * ánh xạ, giao người xử lý, lần chạy và lịch đồng bộ. Mọi lời gọi API công khai được đối chiếu hợp đồng
 * {@code org.yaml}.
 */
@AutoConfigureMockMvc
@Import(OrgSyncApiIT.Crypto.class)
class OrgSyncApiIT extends IntegrationTestBase {

  static final String NATIONAL_A = "001090012345";
  static final String NATIONAL_B = "001090054321";
  static final String NATIONAL_C = "001090077777";

  @TestConfiguration
  static class Crypto {
    @Bean
    @Primary
    FieldEncryptor testFieldEncryptor() {
      return new AesGcmTestFieldEncryptor();
    }
  }

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;
  @Autowired
  DiscrepancyService discrepancyService;
  @MockitoBean
  IntegrationGatewayPort gateway;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID adminId;
  private UUID unitA;
  private UUID unitB;
  private UUID unitC;
  private UUID title;

  @BeforeEach
  void setUp() {
    reset(gateway);
    tenant = UUID.randomUUID();
    adminId = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K3");
    unitA = UUID.randomUUID();
    unitB = UUID.randomUUID();
    unitC = UUID.randomUUID();
    LocalDate base = LocalDate.of(2020, 1, 1);
    fx.unit(tenant, unitA, "CUC-A", "Cục Công nghệ thông tin", "CO_QUAN", group, base, null);
    fx.unit(tenant, unitB, "CUC-B", "Cục Pháp chế", "CO_QUAN", group, base, null);
    fx.unit(tenant, unitC, "CUC-C", "Cục Tổ chức cán bộ", "CO_QUAN", group, base, null);
    title = fx.jobTitle(tenant);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  // ---------- trợ giúp ----------

  private ResultActions api(MockHttpServletRequestBuilder req) throws Exception {
    String token = "Bearer " + TestJwt.token(tenant, adminId, List.of("tenant_admin"), "aal2", null);
    ResultActions r = mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token));
    MvcResult result = r.andReturn();
    ValidationReport report = ContractValidator.validate(result);
    assertThat(report.getMessages()).as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
        + " " + result.getResponse().getContentAsString()).isEmpty();
    return r;
  }

  /** Đọc tác vụ {@code /api/v1/jobs/{id}} ({@code jobs.yaml}, ngoài hợp đồng org mà bộ kiểm hợp đồng nạp). */
  private ResultActions job(String location) throws Exception {
    String token = "Bearer " + TestJwt.token(tenant, adminId, List.of("tenant_admin"), "aal2", null);
    return mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, token));
  }

  private ResultActions internal(MockHttpServletRequestBuilder req, String role) throws Exception {
    String token = "Bearer " + TestJwt.token(tenant, UUID.randomUUID(), List.of(role), "aal1", null);
    return mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token));
  }

  private static Map<String, Object> pos(UUID unit, UUID title, String kind, boolean head, String from) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orgUnitId", unit);
    m.put("jobTitleId", title);
    m.put("kind", kind);
    m.put("head", head);
    m.put("validFrom", from);
    return m;
  }

  private static Map<String, Object> record(String ext, String name, String nid, String birth, String status,
      boolean party, List<Map<String, Object>> posts) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("externalId", ext);
    m.put("fullName", name);
    m.put("nationalId", nid);
    m.put("birthDate", birth);
    m.put("employmentStatus", status);
    m.put("partyMember", party);
    m.put("posts", posts);
    return m;
  }

  private JsonNode batch(UUID runId, List<Map<String, Object>> records, boolean last) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", tenant);
    body.put("connector", "CBCCVC");
    body.put("records", records);
    body.put("last", last);
    body.put("deadLetters", 0);
    String out = internal(post("/internal/v1/org/sync-runs/" + runId + "/batches")
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), "svc_integration_gateway")
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return json.readTree(out);
  }

  /** Lần 1 tạo ba cán bộ; lần 2 đổi dữ liệu nguồn để sinh đủ chênh lệch CAO, VỪA, THẤP và một bản ghi không khớp. */
  private void seedDiscrepancies() throws Exception {
    JsonNode first = batch(UUID.randomUUID(), List.of(
        record("HR-1", "Trần Thị Bình", NATIONAL_A, "1990-05-01", "DANG_CONG_TAC", true,
            List.of(pos(unitA, title, "CHINH", false, "2025-01-01"))),
        record("HR-2", "Lê Văn Cường", NATIONAL_B, "1985-03-02", "DANG_CONG_TAC", false,
            List.of(pos(unitA, title, "CHINH", false, "2025-01-01"))),
        record("HR-3", "Phạm Thu Dung", NATIONAL_C, "1992-07-03", "DANG_CONG_TAC", false,
            List.of(pos(unitB, title, "CHINH", false, "2025-01-01")))), true);
    assertThat(first.get("errors").asInt()).isZero();
    assertThat(first.get("changes").asInt()).isEqualTo(3);

    JsonNode second = batch(UUID.randomUUID(), List.of(
        // Đơn vị chính khác: CAO, nhận theo nguồn.
        record("HR-1", "Trần Thị Bình", NATIONAL_A, "1990-05-01", "DANG_CONG_TAC", true,
            List.of(pos(unitB, title, "CHINH", false, "2026-06-01"))),
        // Nghỉ hưu: VỪA, đề xuất kết thúc phân công.
        record("HR-2", "Lê Văn Cường", NATIONAL_B, "1985-03-02", "NGHI_HUU", false,
            List.of(pos(unitA, title, "CHINH", false, "2025-01-01"))),
        // Thêm kiêm nhiệm không phải cấp trưởng: THẤP.
        record("HR-3", "Phạm Thu Dung", NATIONAL_C, "1992-07-03", "DANG_CONG_TAC", false,
            List.of(pos(unitB, title, "CHINH", false, "2025-01-01"),
                pos(unitC, title, "KIEM_NHIEM", false, "2026-02-01"))),
        // Trùng số định danh với HR-1 nhưng khác ngày sinh: không khớp, CAO.
        record("HR-9", "Trần Thị Bình", NATIONAL_A, "1991-01-01", "DANG_CONG_TAC", false, List.of())), true);
    assertThat(second.get("errors").asInt()).isZero();
    assertThat(second.get("discrepancies").asInt()).isEqualTo(4);
  }

  private JsonNode list(String query) throws Exception {
    return json.readTree(api(get("/api/v1/org/discrepancies" + query)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
  }

  private JsonNode find(JsonNode page, String field) {
    for (JsonNode n : page.get("items")) {
      if (field.equals(n.get("field").asText())) {
        return n;
      }
    }
    throw new AssertionError("Không thấy chênh lệch " + field);
  }

  // ---------- kiểm thử ----------

  @Test
  void ingest_producesAllSeveritiesWithMaskedValues_andNoPlaintextC2() throws Exception {
    seedDiscrepancies();

    JsonNode page = list("?unresolved=true");
    assertThat(page.get("items")).hasSize(4);
    assertThat(page.at("/counts/CAO").asInt()).isEqualTo(2);
    assertThat(page.at("/counts/VUA").asInt()).isEqualTo(1);
    assertThat(page.at("/counts/THAP").asInt()).isEqualTo(1);
    assertThat(page.at("/countsByField/person_match").asInt()).isEqualTo(1);
    assertThat(find(page, "employment_status").get("suggestedAction").asText()).isEqualTo("KET_THUC_PHAN_CONG");
    assertThat(find(page, "org_unit_id").get("suggestedAction").asText()).isEqualTo("LAY_THEO_NGUON");

    String body = page.toString();
    for (String secret : List.of(NATIONAL_A, NATIONAL_B, NATIONAL_C, "1990-05-01", "1991-01-01")) {
      assertThat(body).doesNotContain(secret);
    }
    assertThat(find(page, "org_unit_id").get("maskedNationalId").asText()).endsWith("****");

    // Bảng chỉ giữ bản mã và chỉ mục mù: không cột văn bản nào chứa số định danh hay ngày sinh.
    String dump = String.join("|", fx.admin().queryForList(
        "SELECT p::text FROM org.person p WHERE tenant_id = ? UNION ALL "
            + "SELECT d::text FROM org.sync_discrepancy d WHERE tenant_id = ?", String.class, tenant, tenant));
    assertThat(dump).doesNotContain(NATIONAL_A).doesNotContain("1990-05-01");

    // BR-01-10: mission-core hỏi số chênh lệch mức cao chưa xử lý qua cổng nội bộ.
    internal(get("/internal/v1/org/tenants/" + tenant + "/discrepancies/high-count"), "svc_integration_gateway")
        .andExpect(status().isOk()).andExpect(jsonPath("$.open").value(2));
    internal(get("/internal/v1/org/tenants/" + tenant + "/discrepancies/high-count"), "tenant_admin")
        .andExpect(status().isForbidden());
  }

  @Test
  void resolve_takesSourceValue_isIdempotent_andRejectsOverLimitBatch() throws Exception {
    seedDiscrepancies();
    JsonNode page = list("?unresolved=true");
    String unitRow = find(page, "org_unit_id").get("id").asText();
    String statusRow = find(page, "employment_status").get("id").asText();
    String mismatchRow = find(page, "person_match").get("id").asText();

    String body = json.writeValueAsString(Map.of("ids", List.of(unitRow, statusRow, mismatchRow),
        "resolution", "NHAN_NGUON"));
    String key = UUID.randomUUID().toString();
    String first = api(post("/api/v1/org/discrepancies/resolve").header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode r = json.readTree(first);
    assertThat(r.get("assignmentsUpdated").asInt()).isGreaterThanOrEqualTo(2);
    Map<String, Integer> byId = new LinkedHashMap<>();
    r.get("results").forEach(n -> byId.put(n.get("id").asText(), n.get("status").asInt()));
    assertThat(byId).containsEntry(unitRow, 200).containsEntry(statusRow, 200).containsEntry(mismatchRow, 422);

    // Phân công chính của HR-1 đã sang đơn vị B; HR-2 nghỉ hưu nên phân công chính bị kết thúc.
    Integer atB = fx.admin().queryForObject("SELECT count(*) FROM org.assignment WHERE tenant_id = ? "
        + "AND org_unit_id = ? AND kind = 'CHINH' AND recorded_to = 'infinity' AND valid_from = '2026-06-01'",
        Integer.class, tenant, unitB);
    assertThat(atB).isEqualTo(1);

    String replay = api(post("/api/v1/org/discrepancies/resolve").header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(replay)).isEqualTo(r);

    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i < 201; i++) {
      tooMany.add(UUID.randomUUID().toString());
    }
    mvc.perform(post("/api/v1/org/discrepancies/resolve")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(tenant, adminId, List.of("tenant_admin"),
            "aal2", null))
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("ids", tooMany, "resolution", "BO_QUA"))))
        .andExpect(status().isUnprocessableEntity());

    assertThat(list("?unresolved=true").get("items")).hasSize(2);
  }

  @Test
  void resolve_thousandItemsInOneCommand() throws Exception {
    List<Map<String, Object>> created = new ArrayList<>();
    List<Map<String, Object>> changed = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      String nid = String.format("0790%08d", i);
      created.add(record("K-" + i, "Cán bộ số " + i, nid, "1980-01-01", "DANG_CONG_TAC", false, List.of()));
      changed.add(record("K-" + i, "Cán bộ số " + i, nid, "1980-01-01", "DANG_CONG_TAC", true, List.of()));
    }
    batch(UUID.randomUUID(), created, true);
    JsonNode second = batch(UUID.randomUUID(), changed, true);
    assertThat(second.get("discrepancies").asInt()).isEqualTo(1000);

    List<UUID> ids = fx.admin().queryForList("SELECT id FROM org.sync_discrepancy WHERE tenant_id = ?",
        UUID.class, tenant);
    Actor actor = new Actor(tenant, adminId, Set.of("tenant_admin"), "aal2", Instant.now(), null, null);
    TenantContextHolder.set(tenant);
    ResolveResult result;
    try {
      result = discrepancyService.resolve(actor, new IdempotencyRequest(UUID.randomUUID().toString(), new byte[] {1}), ids,
          "GIU_HIEN_TAI", null, "Giữ dữ liệu nội bộ");
    } finally {
      TenantContextHolder.clear();
    }
    assertThat(result.results()).hasSize(1000).allSatisfy(i -> assertThat(i.status()).isEqualTo(200));

    // Nguồn không đổi: lần đồng bộ sau không sinh lại chênh lệch đã giữ nội bộ.
    assertThat(batch(UUID.randomUUID(), changed, true).get("discrepancies").asInt()).isZero();
  }

  @Test
  void map_callsGatewayAndReportsXrefConflict() throws Exception {
    seedDiscrepancies();
    JsonNode page = list("?field=person_match");
    String row = page.get("items").get(0).get("id").asText();
    UUID target = UUID.fromString(find(list("?field=org_unit_id"), "org_unit_id").get("personId").asText());

    doThrow(new OrgConflictException("ORG-409-XREF-EXISTS", "Mã nguồn đã được ánh xạ tới cán bộ khác."))
        .when(gateway).mapXref(eq(tenant), eq("CBCCVC"), eq("HR-9"), any());
    api(post("/api/v1/org/discrepancies/" + row + "/map").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("personId", target))))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORG-409-XREF-EXISTS"));

    reset(gateway);
    api(post("/api/v1/org/discrepancies/" + row + "/map").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("personId", target))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.resolution").value("SUA_TAY"))
        .andExpect(jsonPath("$.personId").value(target.toString()));
    verify(gateway).mapXref(tenant, "CBCCVC", "HR-9", target);
  }

  @Test
  void assign_notifiesAssignee() throws Exception {
    seedDiscrepancies();
    String row = find(list(""), "concurrent_assignment").get("id").asText();
    UUID assignee = fx.person(tenant, "Nguyễn Thị Hoa");
    int before = fx.outboxCount(tenant, "notify.message.requested.v1");
    api(post("/api/v1/org/discrepancies/" + row + "/assign").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("assigneeId", assignee, "note", "Đề nghị kiểm tra quyết định"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.assigneeName").value("Nguyễn Thị Hoa"));
    assertThat(fx.outboxCount(tenant, "notify.message.requested.v1")).isEqualTo(before + 1);
  }

  @Test
  void syncJob_allSources_reportsDoneAndRejectedSources() throws Exception {
    doThrow(new IntegrationUnavailableException("cổng tích hợp từ chối: Tenant chưa cấu hình kết nối", null))
        .when(gateway).requestRun(eq(tenant), any(), eq("SCIM"));
    String location = api(post("/api/v1/org/sync-runs").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isAccepted()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
    job(location).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DANG_CHAY"))
        .andExpect(jsonPath("$.progress").value(25));

    JsonNode runs = json.readTree(api(get("/api/v1/org/sync-runs?limit=10")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString()).get("items");
    for (JsonNode r : runs) {
      if (!"LOI".equals(r.get("status").asText())) {
        batch(UUID.fromString(r.get("id").asText()), List.of(), true);
      }
    }
    job(location).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HOAN_TAT"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Đã đồng bộ 3/4 nguồn")));
  }

  @Test
  void syncRuns_startConflictRetryAndList() throws Exception {
    String body = json.writeValueAsString(Map.of("connector", "HRM"));
    String location = api(post("/api/v1/org/sync-runs").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isAccepted()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
    assertThat(location).startsWith("/api/v1/jobs/org-");
    verify(gateway).requestRun(eq(tenant), any(), eq("HRM"));
    job(location).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("ORG_SYNC"))
        .andExpect(jsonPath("$.status").value("DANG_CHAY"));

    api(post("/api/v1/org/sync-runs").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());

    UUID closed = UUID.randomUUID();
    batch(closed, List.of(), true);
    api(post("/api/v1/org/sync-runs/" + closed + "/retry").header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isAccepted());
    verify(gateway).retryDeadLetters(eq(tenant), eq(closed), any(), eq("CBCCVC"));

    JsonNode runs = json.readTree(api(get("/api/v1/org/sync-runs?limit=10")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    assertThat(runs.get("items")).hasSize(3);

    // Lô gửi vào lần chạy đã đóng bị từ chối.
    Map<String, Object> late = new LinkedHashMap<>();
    late.put("tenantId", tenant);
    late.put("connector", "CBCCVC");
    late.put("records", List.of());
    late.put("last", true);
    internal(post("/internal/v1/org/sync-runs/" + closed + "/batches").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(late)), "svc_integration_gateway").andExpect(status().isConflict());
  }

  @Test
  void schedules_useEtagAndIfMatch() throws Exception {
    MvcResult first = api(get("/api/v1/org/sync-schedules")).andExpect(status().isOk()).andReturn();
    String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
    assertThat(etag).isNotBlank();

    String body = json.writeValueAsString(Map.of("items", List.of(
        Map.of("connector", "CBCCVC", "enabled", true, "cron", "0 2 * * *"),
        Map.of("connector", "HRM", "enabled", false, "cron", "30 1 * * 1"))));
    String next = api(put("/api/v1/org/sync-schedules").header("If-Match", etag)
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
        .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
        .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    assertThat(next).isNotEqualTo(etag);

    api(put("/api/v1/org/sync-schedules").header("If-Match", etag)
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
        .content(body)).andExpect(status().isConflict());
    api(put("/api/v1/org/sync-schedules").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPreconditionRequired());
  }

  // ---------- DV-02-04: cổng nội bộ tra người theo số định danh cho môi giới VNeID ----------
  // Đặt trong lớp này để dùng chung ngữ cảnh Spring (thêm lớp kiểm thử có ngữ cảnh riêng làm cạn kết nối CSDL).

  static final String MATCH_PATH = "/internal/v1/org/tenants/%s/persons/national-id-match";

  private JsonNode matchNationalId(UUID inTenant, String nationalId) throws Exception {
    String out = internal(post(MATCH_PATH.formatted(inTenant)).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("nationalId", nationalId))), "svc_iam")
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(out).doesNotContain(NATIONAL_A);
    return json.readTree(out);
  }

  /** Khớp bằng chỉ mục mù của tenant; khoảng trắng không làm lệch; tenant khác không thấy người; không lộ số định danh. */
  @Test
  void nationalIdMatch_findsExactlyOnePersonByBlindIndex() throws Exception {
    batch(UUID.randomUUID(), List.of(record("HR-1", "Trần Thị Bình", NATIONAL_A, "1990-05-01", "DANG_CONG_TAC", false,
        List.of(pos(unitA, title, "CHINH", false, "2025-01-01")))), true);
    UUID person = fx.admin().queryForObject("SELECT DISTINCT id FROM org.person WHERE tenant_id = ?", UUID.class,
        tenant);

    JsonNode found = matchNationalId(tenant, " 0010 9001 2345 ");
    assertThat(found.get("matches").asInt()).isEqualTo(1);
    assertThat(found.get("personId").asText()).isEqualTo(person.toString());

    JsonNode none = matchNationalId(tenant, "079200000000");
    assertThat(none.get("matches").asInt()).isZero();
    assertThat(none.path("personId").isMissingNode() || none.path("personId").isNull()).isTrue();

    assertThat(matchNationalId(UUID.randomUUID(), NATIONAL_A).get("matches").asInt()).isZero();
  }

  /** Chỉ vai trò svc_iam gọi được; svc_iam không mở được cổng nội bộ khác; số định danh rỗng trả 400. */
  @Test
  void nationalIdMatch_onlyForIamServiceRole_andRejectsBlank() throws Exception {
    String body = json.writeValueAsString(Map.of("nationalId", NATIONAL_A));
    internal(post(MATCH_PATH.formatted(tenant)).contentType(MediaType.APPLICATION_JSON).content(body),
        "svc_integration_gateway").andExpect(status().isForbidden());
    internal(post(MATCH_PATH.formatted(tenant)).contentType(MediaType.APPLICATION_JSON).content(body), "tenant_admin")
        .andExpect(status().isForbidden());
    internal(post("/internal/v1/org/sync-runs/" + UUID.randomUUID() + "/batches")
        .contentType(MediaType.APPLICATION_JSON).content("{}"), "svc_iam").andExpect(status().isForbidden());
    internal(post(MATCH_PATH.formatted(tenant)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"nationalId\":\" \"}"), "svc_iam").andExpect(status().isBadRequest());
    internal(post(MATCH_PATH.formatted(tenant)).contentType(MediaType.APPLICATION_JSON).content(body), "svc_iam")
        .andExpect(status().isOk()).andExpect(jsonPath("$.matches").value(0));
  }
}
