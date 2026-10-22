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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.DelegationApi;
import vn.viettel.okrkpi.config.application.RoleBindingService;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-16 trên PostgreSQL thật: cấp vai trò cần bước lên (401), không tự cấp
 * (403), trùng 409; vai trò nhạy cảm chờ duyệt thứ hai, người cấp không tự duyệt, lãnh đạo đơn vị
 * cấp trên duyệt được; từ chối, thu hồi, thu hồi lại 409; danh sách kèm số đếm; uỷ quyền trùng 409,
 * uỷ quyền lại 422, thu hồi; lịch sử và xuất CSV; hết hạn phát một lần; mọi phản hồi khớp hợp đồng
 * {@code config.yaml}; EVT-27 và kiểm toán ghi vào {@code config.outbox_event}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {"mission.outbox.relay.enabled=false", "mission.config.access.jobs.enabled=false"})
@AutoConfigureMockMvc(addFilters = false)
@Import(AccessApiIT.SerializerConfig.class)
@DirtiesContext
class AccessApiIT extends ContractConformanceTest {

  private static final UUID ADMIN = PeriodApiIT.CallerConfig.ACTOR;
  private static final UUID ADMIN2 = UUID.randomUUID();
  private static final UUID LEADER = UUID.randomUUID();
  private static final UUID GRANTEE = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();

  /** Bộ tuần tự outbox giả (môi trường kiểm thử không có registry Avro). */
  static class SerializerConfig {
    @Bean
    @Primary
    OutboxPayloadSerializer accessTestSerializer() {
      return (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private RoleBindingService roles;
  @Autowired private DelegationApi delegationApi;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @Autowired
  @Qualifier("orgReplicaDataSource") private DataSource replicaDataSource;

  @Autowired
  @Qualifier("orgReplicaTransactionManager") private PlatformTransactionManager replicaTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID parent = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  @AfterEach
  void resetCaller() {
    PeriodApiIT.CallerConfig.reset();
  }

  @Test
  void roleBindingLifecycleDelegationHistoryAndExpiry() throws Exception {
    seedDirectory();

    // Cấp vai trò cần aal2 trong 15 phút.
    act(ADMIN, List.of("tenant_admin"), false);
    assertThat(status(perform(write(post(BINDINGS), grant(LEADER, "leader", parent)))))
        .isEqualTo(401);
    act(ADMIN, List.of("tenant_admin"), true);
    MvcResult leader = perform(write(post(BINDINGS), grant(LEADER, "leader", parent)));
    assertThat(status(leader)).as(text(leader)).isEqualTo(201);
    assertResponseConforms(leader, "/config/role-bindings", PathItem.HttpMethod.POST);
    assertThat(body(leader).get("status").asText()).isEqualTo("HIEU_LUC");
    assertThat(body(leader).at("/person/name").asText()).isEqualTo("Nguyễn Văn Lãnh");
    assertThat(body(leader).at("/orgUnit/name").asText()).isEqualTo("Sở Nội vụ");
    assertThat(status(perform(write(post(BINDINGS), grant(LEADER, "leader", parent)))))
        .isEqualTo(409);
    assertThat(status(perform(write(post(BINDINGS), grant(ADMIN, "leader", parent)))))
        .isEqualTo(403);

    // Vai trò nhạy cảm chờ duyệt thứ hai; người cấp không tự duyệt; lãnh đạo cấp trên duyệt.
    MvcResult decider = perform(write(post(BINDINGS), grant(GRANTEE, "decider", unit)));
    assertThat(body(decider).get("status").asText()).isEqualTo("CHO_DUYET");
    String deciderId = body(decider).get("id").asText();
    assertThat(status(perform(write(post(BINDINGS + "/" + deciderId + "/approve"), "{}", "\"0\""))))
        .isEqualTo(403);
    act(LEADER, List.of("leader"), true);
    MvcResult approved =
        perform(write(post(BINDINGS + "/" + deciderId + "/approve"), "{}", "\"0\""));
    assertThat(status(approved)).as(text(approved)).isEqualTo(200);
    assertResponseConforms(
        approved, "/config/role-bindings/{id}/approve", PathItem.HttpMethod.POST);
    assertThat(body(approved).get("status").asText()).isEqualTo("HIEU_LUC");
    assertThat(approved.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

    // Từ chối đề nghị khác bởi quản trị thứ hai.
    act(ADMIN, List.of("tenant_admin"), true);
    String secondId =
        body(perform(write(post(BINDINGS), grant(GRANTEE, "second_approver", unit))))
            .get("id")
            .asText();
    act(ADMIN2, List.of("tenant_admin"), true);
    MvcResult rejected =
        perform(write(post(BINDINGS + "/" + secondId + "/reject"), reason(), "\"0\""));
    assertThat(status(rejected)).as(text(rejected)).isEqualTo(200);
    assertResponseConforms(rejected, "/config/role-bindings/{id}/reject", PathItem.HttpMethod.POST);
    assertThat(body(rejected).get("status").asText()).isEqualTo("TU_CHOI");
    assertThat(
            status(
                perform(
                    write(
                        post(BINDINGS + "/" + secondId + "/reject"),
                        "{\"reason\":\"ngắn\"}",
                        "\"1\""))))
        .isEqualTo(422);

    // Thu hồi, thu hồi lại 409; lệch phiên bản 409; thiếu If-Match 428.
    act(ADMIN, List.of("tenant_admin"), true);
    assertThat(status(perform(write(post(BINDINGS + "/" + deciderId + "/revoke"), reason(), null))))
        .isEqualTo(428);
    assertThat(
            status(perform(write(post(BINDINGS + "/" + deciderId + "/revoke"), reason(), "\"0\""))))
        .isEqualTo(409);
    MvcResult revoked =
        perform(write(post(BINDINGS + "/" + deciderId + "/revoke"), reason(), "\"1\""));
    assertThat(status(revoked)).as(text(revoked)).isEqualTo(200);
    assertResponseConforms(revoked, "/config/role-bindings/{id}/revoke", PathItem.HttpMethod.POST);
    assertThat(body(revoked).get("status").asText()).isEqualTo("DA_THU_HOI");
    assertThat(
            status(perform(write(post(BINDINGS + "/" + deciderId + "/revoke"), reason(), "\"2\""))))
        .isEqualTo(409);

    // Danh sách theo đơn vị cấp trên kèm cấp dưới, có số đếm; đọc một bản ghi; không có thì 404.
    MvcResult list =
        perform(
            get(BINDINGS)
                .param("orgUnitId", parent.toString())
                .param("includeSubunits", "true")
                .param("limit", "10"));
    assertThat(status(list)).as(text(list)).isEqualTo(200);
    assertResponseConforms(list, "/config/role-bindings", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items").size()).isEqualTo(2);
    assertThat(body(list).at("/counts/active").asInt()).isEqualTo(1);
    MvcResult one = perform(get(BINDINGS + "/" + deciderId));
    assertThat(status(one)).isEqualTo(200);
    assertResponseConforms(one, "/config/role-bindings/{id}", PathItem.HttpMethod.GET);
    assertThat(status(perform(get(BINDINGS + "/" + UUID.randomUUID())))).isEqualTo(404);
    act(OTHER, List.of("member"), true);
    assertThat(status(perform(get(BINDINGS)))).isEqualTo(403);

    // Uỷ quyền: tạo, trùng 409, uỷ quyền lại 422, thu hồi.
    act(LEADER, List.of("leader"), true);
    MvcResult delegation = perform(write(post(DELEGATIONS), delegate(LEADER, GRANTEE)));
    assertThat(status(delegation)).as(text(delegation)).isEqualTo(201);
    assertResponseConforms(delegation, "/config/delegations", PathItem.HttpMethod.POST);
    assertThat(body(delegation).get("status").asText()).isEqualTo("HIEU_LUC");
    assertThat(status(perform(write(post(DELEGATIONS), delegate(LEADER, GRANTEE))))).isEqualTo(409);
    MvcResult redelegate = perform(write(post(DELEGATIONS), delegate(GRANTEE, OTHER)));
    assertThat(status(redelegate)).as(text(redelegate)).isEqualTo(422);
    Instant now = Instant.now();
    assertThat(
            TenantContext.call(
                tenant,
                () ->
                    delegationApi.activeFor(
                        tenant, DelegationApi.Kind.DUYET_THAY, LEADER, GRANTEE, unit, null, now)))
        .isPresent();
    MvcResult delegations = perform(get(DELEGATIONS).param("orgUnitId", unit.toString()));
    assertThat(status(delegations)).isEqualTo(200);
    assertResponseConforms(delegations, "/config/delegations", PathItem.HttpMethod.GET);
    assertThat(body(delegations).get("items").size()).isEqualTo(1);
    String delegationId = body(delegation).get("id").asText();
    MvcResult endedDelegation =
        perform(write(post(DELEGATIONS + "/" + delegationId + "/revoke"), reason(), "\"0\""));
    assertThat(status(endedDelegation)).as(text(endedDelegation)).isEqualTo(200);
    assertResponseConforms(
        endedDelegation, "/config/delegations/{id}/revoke", PathItem.HttpMethod.POST);
    assertThat(body(endedDelegation).get("status").asText()).isEqualTo("DA_THU_HOI");

    // Lịch sử và xuất CSV.
    act(ADMIN, List.of("tenant_admin"), true);
    MvcResult history =
        perform(get("/api/v1/config/access-history").param("personId", GRANTEE.toString()));
    assertThat(status(history)).as(text(history)).isEqualTo(200);
    assertResponseConforms(history, "/config/access-history", PathItem.HttpMethod.GET);
    // Cấp, duyệt, thu hồi decider; cấp, từ chối second_approver; tạo, thu hồi uỷ quyền.
    assertThat(body(history).get("items").size()).isEqualTo(7);
    assertThat(status(perform(get("/api/v1/config/access-history")))).isEqualTo(422);
    MvcResult csv =
        perform(get("/api/v1/config/access-history/export").param("personId", GRANTEE.toString()));
    assertThat(status(csv)).isEqualTo(200);
    assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
    assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("Thu hồi vai trò")
        .contains("Trần Thị Nhận");

    // Hết hạn phát EVT-27 một lần.
    Instant past = Instant.now().minus(2, ChronoUnit.DAYS);
    MvcResult expired =
        perform(
            write(post(BINDINGS), grant(GRANTEE, "member", unit, past, past.plusSeconds(3600))));
    assertThat(body(expired).get("status").asText()).isEqualTo("HET_HAN");
    assertThat(TenantContext.call(tenant, () -> roles.expireDue(tenant, null, 50))).isEqualTo(1);
    assertThat(TenantContext.call(tenant, () -> roles.expireDue(tenant, null, 50))).isZero();

    // EVT-27 và sự kiện kiểm toán vào outbox của config.
    Integer events =
        TenantContext.call(
            tenant,
            () ->
                new TransactionTemplate(configTm)
                    .execute(
                        s ->
                            JdbcClient.create(configDataSource)
                                .sql("SELECT count(*) FROM config.outbox_event WHERE topic = ?")
                                .param("config.access.changed.v1")
                                .query(Integer.class)
                                .single()));
    // Cấp 4 + duyệt + từ chối + thu hồi + uỷ quyền + thu hồi uỷ quyền + cấp đã hết hạn + hết hạn.
    assertThat(events).isEqualTo(10);
  }

  private static final String BINDINGS = "/api/v1/config/role-bindings";
  private static final String DELEGATIONS = "/api/v1/config/delegations";

  private void seedDirectory() throws Exception {
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(replicaTm)
                .execute(
                    s -> {
                      JdbcClient jdbc = JdbcClient.create(replicaDataSource);
                      unit(jdbc, parent, "Sở Nội vụ");
                      unit(jdbc, unit, "Phòng Tổ chức cán bộ");
                      Consumer<UUID[]> closure =
                          ids ->
                              jdbc.sql(
                                      "INSERT INTO org_replica.unit_closure_v (tree, ancestor_id,"
                                          + " descendant_id, depth) VALUES ('CHUYEN_MON', ?, ?, ?)")
                                  .params(ids[0], ids[1], ids[0].equals(ids[1]) ? 0 : 1)
                                  .update();
                      closure.accept(new UUID[] {parent, parent});
                      closure.accept(new UUID[] {unit, unit});
                      closure.accept(new UUID[] {parent, unit});
                      Map<UUID, String> people = new LinkedHashMap<>();
                      people.put(ADMIN, "Lê Quản Trị");
                      people.put(ADMIN2, "Phạm Quản Trị");
                      people.put(LEADER, "Nguyễn Văn Lãnh");
                      people.put(GRANTEE, "Trần Thị Nhận");
                      people.put(OTHER, "Hoàng Văn Khác");
                      people.forEach(
                          (id, name) ->
                              jdbc.sql(
                                      "INSERT INTO org_replica.person_v (id, full_name,"
                                          + " employment_status, source_version, source_event_id)"
                                          + " VALUES (?, ?, 'DANG_LAM_VIEC', 1, ?)")
                                  .params(id, name, UUID.randomUUID())
                                  .update());
                      return null;
                    }));
  }

  private static void unit(JdbcClient jdbc, UUID id, String name) {
    jdbc.sql(
            "INSERT INTO org_replica.org_unit_v (id, valid_from, code, name, unit_type,"
                + " source_version, source_event_id) VALUES (?, DATE '2020-01-01', ?, ?, 'SO', 1, ?)")
        .params(id, id.toString().substring(0, 8), name, UUID.randomUUID())
        .update();
  }

  private static void act(UUID user, List<String> roles, boolean stepUp) {
    PeriodApiIT.CallerConfig.current = user;
    PeriodApiIT.CallerConfig.roles = roles;
    PeriodApiIT.CallerConfig.acr = stepUp ? "aal2" : "aal1";
    PeriodApiIT.CallerConfig.authTime = Instant.now().getEpochSecond();
  }

  private String grant(UUID person, String role, UUID orgUnit) throws Exception {
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    return grant(person, role, orgUnit, from, from.plus(365, ChronoUnit.DAYS));
  }

  private String grant(UUID person, String role, UUID orgUnit, Instant from, Instant to)
      throws Exception {
    return json.writeValueAsString(
        Map.of(
            "personId",
            person,
            "roleCode",
            role,
            "orgUnitId",
            orgUnit,
            "scope",
            "DON_VI_VA_CAP_DUOI",
            "validFrom",
            from.toString(),
            "validTo",
            to.toString(),
            "reason",
            "Phân công theo quyết định số 12/QĐ"));
  }

  private String delegate(UUID delegator, UUID delegate) throws Exception {
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    return json.writeValueAsString(
        Map.of(
            "kind",
            "DUYET_THAY",
            "delegatorId",
            delegator,
            "delegateId",
            delegate,
            "orgUnitId",
            unit,
            "validFrom",
            from.toString(),
            "validTo",
            from.plus(7, ChronoUnit.DAYS).toString(),
            "reason",
            "Đi công tác từ ngày 20 đến 27"));
  }

  private static String reason() {
    return "{\"reason\":\"Điều chuyển công tác sang đơn vị khác\"}";
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, String body) {
    return write(b, body, null);
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String body, String ifMatch) {
    b.header("Idempotency-Key", UUID.randomUUID().toString());
    if (ifMatch != null) {
      b.header("If-Match", ifMatch);
    }
    return b.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(text(r));
  }

  private static String text(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private static int status(MvcResult r) {
    return r.getResponse().getStatus();
  }
}
