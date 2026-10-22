package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.oas.models.PathItem;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.infrastructure.web.PolicyCaller;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-11 trên PostgreSQL thật, SVC-06 giả bằng máy chủ HTTP (DV-06-08 chưa
 * gộp): sáu thao tác cấu hình luồng duyệt khớp {@code policies.yaml}; lưu chép tham số vào manifest
 * phiên bản; 422 linter giữ quy tắc vi phạm (MSG-15-09); kỳ khoá, phiên bản đóng băng bị 409;
 * SVC-06 lỗi thành 503 kèm {@code Retry-After}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class PolicyWorkflowBindingApiIT extends ContractConformanceTest {

  private static final Map<String, Integer> STATUS = new ConcurrentHashMap<>();
  private static final Map<String, String> BODIES = new ConcurrentHashMap<>();
  private static final Map<String, String> SEEN = new ConcurrentHashMap<>();
  private static final HttpServer SVC06 = start();

  private static HttpServer start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/internal/v1/workflow/",
          ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            SEEN.put(
                key + "#tenant", String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
            SEEN.put(
                key + "#auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            SEEN.put(key + "#ifMatch", String.valueOf(ex.getRequestHeaders().getFirst("If-Match")));
            byte[] b = BODIES.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
            int code = STATUS.getOrDefault(key, 200);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stopServer() {
    SVC06.stop(0);
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
    registry.add(
        "mission.policy.workflow.base-url",
        () -> "http://127.0.0.1:" + SVC06.getAddress().getPort());
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyPackService packs;

  @Autowired
  @Qualifier("policyDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("policyTransactionManager") private PlatformTransactionManager tm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @MockitoBean private PolicyCaller caller;
  @MockitoBean private KeycloakAdminClient keycloak;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private final UUID lockedPeriod = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.POLICIES;
  }

  @BeforeEach
  void stubs() {
    STATUS.clear();
    BODIES.clear();
    SEEN.clear();
    when(caller.currentUserId()).thenReturn(Optional.of(author));
    when(keycloak.serviceToken()).thenReturn("svc-token");
  }

  private void period(UUID id, String code, int bucket, String status, String start)
      throws Exception {
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
                                    + " updated_by, locked_at) VALUES (?, ?, ?, 'QUY', 'NHA_NUOC',"
                                    + " ?::date, (?::date + 89), ?, ?, ?, ?,"
                                    + " CASE WHEN ? = 'DA_KHOA' THEN now() END)")
                            .params(
                                id, code, code, start, start, bucket, status, author, author,
                                status)
                            .update()));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private <T> T inTenant(Function<JdbcClient, T> sql) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> sql.apply(jdbc)));
  }

  private VersionView seed() throws Exception {
    period(period, "2027-Q1", 20271, "MO", "2027-01-01");
    period(lockedPeriod, "2026-Q4", 20264, "DA_KHOA", "2026-10-01");
    Map<String, Object> manifest =
        PolicyPackFixtures.yaml(
            PolicyPackFixtures.pack(PolicyPackFixtures.GOI_NHA_NUOC).resolve("policy.yaml"));
    return TenantContext.call(
        tenant,
        () ->
            packs.importPack(
                tenant,
                author,
                new PackImport(manifest, PackOrigin.GOC_TOAN_CUC, "KHOI_CHINH_QUYEN", null, null)));
  }

  private String base(VersionView root) {
    return "/api/v1/policies/packs/" + root.pack().id() + "/versions/1.4.0/workflow-bindings";
  }

  private String binding(long rowVersion, int soCap) {
    return "{\"id\":\"0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4c01\",\"processKind\":\"DANH_GIA\","
        + "\"templateKey\":\"duyet-ho-so-danh-gia\",\"templateVersionNo\":3,"
        + "\"scope\":{\"flow\":\"NHA_NUOC\",\"subjectGroupId\":null,\"periodType\":\"QUY\","
        + "\"fromPeriodId\":\""
        + period
        + "\"},\"params\":{\"so_cap\":"
        + soCap
        + "},\"status\":\"NHAP\",\"versionNo\":1,"
        + "\"lint\":{\"passed\":true,\"issues\":[]},\"templateTests\":{\"passed\":42,\"total\":42},"
        + "\"rowVersion\":"
        + rowVersion
        + "}";
  }

  private MockHttpServletRequestBuilder save(VersionView root, String etag, int soCap) {
    return save(root, etag, soCap, period);
  }

  private MockHttpServletRequestBuilder save(VersionView root, String etag, int soCap, UUID from) {
    return put(base(root) + "/DANH_GIA")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"templateKey\":\"duyet-ho-so-danh-gia\",\"templateVersionNo\":3,"
                + "\"scope\":{\"flow\":\"NHA_NUOC\",\"periodType\":\"QUY\",\"fromPeriodId\":\""
                + from
                + "\"},\"params\":{\"so_cap\":"
                + soCap
                + "}}");
  }

  private void status(UUID versionId, String status) throws Exception {
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE policy.policy_pack_version SET status = ?, golden_pass_rate = 1"
                        + " WHERE tenant_id = ? AND id = ?")
                .params(status, tenant.value(), versionId)
                .update());
  }

  @Test
  void sauThaoTacKhopHopDongVaLuuChepVaoManifest() throws Exception {
    VersionView root = seed();
    String bindings = "GET /internal/v1/workflow/bindings";
    BODIES.put(bindings, "{\"items\":[" + binding(4, 4) + "]}");
    MvcResult list = perform(get(base(root)));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        list, "/policies/packs/{id}/versions/{v}/workflow-bindings", PathItem.HttpMethod.GET);
    assertThat(SEEN.get(bindings + "#tenant")).isEqualTo(tenant.value().toString());
    assertThat(SEEN.get(bindings + "#auth")).isEqualTo("Bearer svc-token");

    String one = "GET /internal/v1/workflow/bindings/DANH_GIA";
    BODIES.put(one, binding(4, 4));
    MvcResult found = perform(get(base(root) + "/DANH_GIA"));
    assertThat(found.getResponse().getHeader("ETag")).isEqualTo("\"4\"");
    assertResponseConforms(
        found,
        "/policies/packs/{id}/versions/{v}/workflow-bindings/{key}",
        PathItem.HttpMethod.GET);
    STATUS.put("GET /internal/v1/workflow/bindings/KHAC", 404);
    assertThat(perform(get(base(root) + "/KHAC")).getResponse().getStatus()).isEqualTo(404);
    assertThat(
            perform(
                    get(
                        "/api/v1/policies/packs/"
                            + root.pack().id()
                            + "/versions/9.9.9/workflow-bindings"))
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    String putKey = "PUT /internal/v1/workflow/bindings/DANH_GIA";
    BODIES.put(putKey, binding(5, 3));
    MvcResult saved = perform(save(root, "\"4\"", 3));
    assertThat(saved.getResponse().getStatus()).isEqualTo(200);
    assertThat(saved.getResponse().getHeader("ETag")).isEqualTo("\"5\"");
    assertResponseConforms(
        saved,
        "/policies/packs/{id}/versions/{v}/workflow-bindings/{key}",
        PathItem.HttpMethod.PUT);
    assertThat(SEEN.get(putKey + "#ifMatch")).isEqualTo("\"4\"");
    String manifest =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT manifest -> 'workflow_bindings' -> 'DANH_GIA' ->> 'templateKey'"
                            + " FROM policy.policy_pack_version WHERE id = ?")
                    .param(root.version().id())
                    .query(String.class)
                    .single());
    assertThat(manifest).isEqualTo("duyet-ho-so-danh-gia");

    BODIES.put(
        "POST /internal/v1/workflow/bindings/simulate",
        "{\"steps\":[{\"stepKey\":\"TU_DANH_GIA\",\"name\":\"Tự đánh giá\",\"role\":\"member\"}],"
            + "\"lint\":{\"passed\":true,\"issues\":[]},\"templateTests\":{\"passed\":42,\"total\":42},"
            + "\"sampleRuns\":[{\"sample\":\"Chuyên viên\",\"skippedSteps\":[],\"endState\":"
            + "\"KHOA_HO_SO\"}]}");
    MvcResult simulated =
        perform(
            post("/api/v1/policies/workflow-bindings/simulate")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"templateKey\":\"duyet-ho-so-danh-gia\",\"templateVersionNo\":3,"
                        + "\"params\":{\"so_cap\":4}}"));
    assertThat(simulated.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        simulated, "/policies/workflow-bindings/simulate", PathItem.HttpMethod.POST);

    BODIES.put(
        "GET /internal/v1/workflow/templates",
        "{\"items\":[{\"key\":\"duyet-ho-so-danh-gia\",\"name\":\"Duyệt hồ sơ đánh giá\","
            + "\"versionNo\":3,\"status\":\"HIEU_LUC\",\"processKind\":\"DANH_GIA\","
            + "\"testCount\":42}],\"nextCursor\":null}");
    MvcResult templates = perform(get("/api/v1/policies/workflow-templates?processKind=DANH_GIA"));
    assertThat(templates.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(templates, "/policies/workflow-templates", PathItem.HttpMethod.GET);

    BODIES.put(
        "GET /internal/v1/workflow/templates/duyet-ho-so-danh-gia/versions/3",
        "{\"key\":\"duyet-ho-so-danh-gia\",\"versionNo\":3,\"status\":\"HIEU_LUC\","
            + "\"paramSchema\":{\"type\":\"object\"},\"previewSteps\":[]}");
    MvcResult template =
        perform(get("/api/v1/policies/workflow-templates/duyet-ho-so-danh-gia/versions/3"));
    assertThat(template.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        template, "/policies/workflow-templates/{key}/versions/{v}", PathItem.HttpMethod.GET);
  }

  @Test
  void loiLinterKyKhoaPhienBanDongBangVaSvc06Ngung() throws Exception {
    VersionView root = seed();
    String putKey = "PUT /internal/v1/workflow/bindings/DANH_GIA";
    STATUS.put(putKey, 422);
    BODIES.put(
        putKey,
        "{\"detail\":\"Tham số vi phạm linter\",\"issues\":[{\"rule\":\"so-cap-trong-mien\","
            + "\"elementId\":\"cap\",\"message\":\"Số cấp phải từ 2 đến 4\"}]}");
    MvcResult lint = perform(save(root, "\"0\"", 5));
    assertThat(lint.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(lint).get("code").asText()).isEqualTo("POLICY-422-WF-PARAMS");
    assertThat(body(lint).get("msg").asText()).isEqualTo("MSG-15-09");
    assertThat(body(lint).get("errors").get(0).get("rule").asText()).isEqualTo("so-cap-trong-mien");

    STATUS.put(putKey, 409);
    MvcResult conflict = perform(save(root, "\"7\"", 3));
    assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(conflict).get("code").asText()).isEqualTo("POLICY-409-VERSION-CONFLICT");

    STATUS.put(putKey, 503);
    MvcResult down = perform(save(root, "\"0\"", 3));
    assertThat(down.getResponse().getStatus()).isEqualTo(503);
    assertThat(down.getResponse().getHeader("Retry-After")).isNotBlank();

    MvcResult locked = perform(save(root, "\"0\"", 3, lockedPeriod));
    assertThat(locked.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(locked).get("code").asText()).isEqualTo("POLICY-409-PERIOD-LOCKED");

    status(root.version().id(), "DAT_KIEM_THU");
    MvcResult frozen = perform(save(root, "\"0\"", 3));
    assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(frozen).get("code").asText()).isEqualTo("POLICY-409-VERSION-FROZEN");
  }
}
