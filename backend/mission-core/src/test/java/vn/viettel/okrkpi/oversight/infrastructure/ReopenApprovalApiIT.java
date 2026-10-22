package vn.viettel.okrkpi.oversight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import javax.sql.DataSource;
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
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.oversight.application.port.ApprovalSigner;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenApprovalService;
import vn.viettel.okrkpi.oversight.infrastructure.web.OversightCaller;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-07-08 trên PostgreSQL thật: chọn người duyệt từ {@code
 * config.role_binding} (bỏ người lập; đơn vị chỉ có một người thì nâng lên đơn vị cấp trên), {@code
 * awaitingMyApproval}; người lập tự duyệt 403 {@code OVERSIGHT-403-FOUR-EYES}; thiếu AAL2 401 kèm
 * {@code WWW-Authenticate}; lượt 1 chờ ký, cổng tài liệu ký nội bộ, EVT-18 chuyển {@code
 * CHO_DUYET_2}; người duyệt lượt 1 duyệt tiếp 403; hai lệnh duyệt lượt 2 đồng thời chỉ một yêu cầu
 * ký, hai EVT-18 đồng thời chỉ một EVT-22; kỳ sang {@code MO_LAI_HAU_KIEM} thì {@code
 * DA_THUC_HIEN}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.oversight.reopen-execution.enabled=false"
    })
@AutoConfigureMockMvc(addFilters = false)
@Import(ReopenApprovalApiIT.Beans.class)
@DirtiesContext
class ReopenApprovalApiIT extends ContractConformanceTest {

  private static final String BASE = "/api/v1/oversight/reopen-requests";
  private static final String REASON =
      "Thi hành kết luận khiếu nại: điều chỉnh hệ số chất lượng nhiệm vụ then chốt số 3 của hồ sơ.";

  /** Người gọi, bộ ký số giả và bộ tuần tự outbox kiểm thử. */
  static class Beans {
    static volatile UUID user = UUID.randomUUID();
    static volatile List<String> roles = List.of("appraiser");
    static volatile UUID unit;
    static volatile String acr = "aal2";
    static final List<UUID> SIGNED = new CopyOnWriteArrayList<>();

    @Bean
    OversightCaller approvalTestCaller() {
      return new OversightCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(user);
        }

        @Override
        public List<String> roles() {
          return roles;
        }

        @Override
        public Optional<String> acr() {
          return Optional.ofNullable(acr);
        }

        @Override
        public Optional<UUID> unitId() {
          return Optional.ofNullable(unit);
        }

        @Override
        public Optional<Long> authTime() {
          return Optional.of(Instant.now().getEpochSecond() - 30);
        }
      };
    }

    @Bean
    ApprovalSigner approvalTestSigner() {
      return (tenant, id, key, authorization) -> {
        UUID sign = UUID.randomUUID();
        SIGNED.add(sign);
        return sign;
      };
    }

    @Bean
    @Primary
    OutboxPayloadSerializer approvalTestSerializer() {
      return (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private ReopenApprovalService approvals;
  @Autowired private ProfileParticipantsApi participants;

  @Autowired
  @Qualifier("oversightDataSource") private DataSource oversightDataSource;

  @Autowired
  @Qualifier("oversightTransactionManager") private PlatformTransactionManager oversightTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @Autowired
  @Qualifier("orgReplicaDataSource") private DataSource replicaDataSource;

  @Autowired
  @Qualifier("orgReplicaTransactionManager") private PlatformTransactionManager replicaTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID locked = UUID.randomUUID();
  private final UUID parent = UUID.randomUUID();
  private final UUID child = UUID.randomUUID();
  private final UUID profile = UUID.randomUUID();
  private final UUID requester = UUID.randomUUID();
  private final UUID decider = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OVERSIGHT;
  }

  @Test
  void bonMatBuocLenKySoVaEvt22() throws Exception {
    seed();
    as(requester, List.of("appraiser"), parent, "aal2");
    MvcResult created = perform(write(post(BASE), draft(), null));
    assertThat(status(created)).as(text(created)).isEqualTo(201);
    JsonNode c = body(created);
    String id = c.get("id").asText();
    assertThat(c.at("/approvers/0/assignee/id").asText()).isEqualTo(decider.toString());
    assertThat(c.at("/approvers/0/assignee/name").asText()).isEqualTo("Trần Văn Duyệt");
    // Đơn vị chỉ có decider giữ second_approver nên lượt 2 lấy người ở đơn vị cấp trên.
    assertThat(c.at("/approvers/1/assignee/id").asText()).isEqualTo(second.toString());

    // Danh sách chờ chính người gọi duyệt.
    as(decider, List.of("decider"), child, "aal2");
    MvcResult mine = perform(get(BASE + "?awaitingMyApproval=true"));
    assertThat(status(mine)).as(text(mine)).isEqualTo(200);
    assertResponseConforms(mine, "/oversight/reopen-requests", PathItem.HttpMethod.GET);
    assertThat(body(mine).get("items")).hasSize(1);
    as(second, List.of("second_approver"), parent, "aal2");
    assertThat(body(perform(get(BASE + "?awaitingMyApproval=true"))).get("items")).isEmpty();
    assertThat(status(perform(get(BASE + "/" + id)))).isEqualTo(200);

    // Người lập tự duyệt: 403 bốn mắt.
    as(requester, List.of("appraiser", "decider"), parent, "aal2");
    MvcResult self = perform(write(post(BASE + "/" + id + "/approvals"), comment(), "\"0\""));
    assertThat(status(self)).as(text(self)).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("OVERSIGHT-403-FOUR-EYES");
    assertResponseConforms(
        self, "/oversight/reopen-requests/{id}/approvals", PathItem.HttpMethod.POST);

    // Thiếu AAL2: 401 kèm WWW-Authenticate.
    as(decider, List.of("decider"), child, "aal1");
    MvcResult aal1 = perform(write(post(BASE + "/" + id + "/approvals"), comment(), "\"0\""));
    assertThat(status(aal1)).as(text(aal1)).isEqualTo(401);
    assertThat(aal1.getResponse().getHeader("WWW-Authenticate")).contains("aal2");

    // Lượt 1: chờ ký, cổng tài liệu ký, EVT-18.
    as(decider, List.of("decider"), child, "aal2");
    MvcResult first = perform(write(post(BASE + "/" + id + "/approvals"), comment(), "\"0\""));
    assertThat(status(first)).as(text(first)).isEqualTo(200);
    assertResponseConforms(
        first, "/oversight/reopen-requests/{id}/approvals", PathItem.HttpMethod.POST);
    assertThat(body(first).get("status").asText()).isEqualTo("CHO_DUYET_1");
    assertThat(first.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(Beans.SIGNED).hasSize(1);
    MvcResult source =
        perform(get("/internal/v1/esign-sources/oversight/" + id + "?purpose=PHE_DUYET_MO_LAI"));
    assertThat(status(source)).as(text(source)).isEqualTo(200);
    assertThat(body(source).at("/signers/0").asText()).isEqualTo(decider.toString());
    assertThat(body(source).at("/documents/0/sha256").asText()).hasSize(64);
    assertThat(signed(UUID.fromString(id), Beans.SIGNED.get(0))).isTrue();
    JsonNode afterFirst = body(perform(get(BASE + "/" + id)));
    assertThat(afterFirst.get("status").asText()).isEqualTo("CHO_DUYET_2");
    assertThat(afterFirst.at("/approvers/0/approval/decision").asText()).isEqualTo("DONG_Y");
    assertThat(afterFirst.at("/approvers/0/approval/mfaAcr").asText()).isEqualTo("aal2");

    // Người duyệt lượt 1 không duyệt tiếp lượt 2.
    as(decider, List.of("decider", "second_approver"), child, "aal2");
    MvcResult twice = perform(write(post(BASE + "/" + id + "/approvals"), comment(), "\"2\""));
    assertThat(status(twice)).as(text(twice)).isEqualTo(403);
    assertThat(body(twice).get("code").asText()).isEqualTo("OVERSIGHT-403-FOUR-EYES");

    // Hai lệnh duyệt lượt 2 đồng thời: một yêu cầu ký; hai EVT-18 đồng thời: một EVT-22.
    as(second, List.of("second_approver"), parent, "aal2");
    List<Integer> codes =
        parallel(
            () -> status(perform(write(post(BASE + "/" + id + "/approvals"), comment(), "\"2\""))),
            () -> status(perform(write(post(BASE + "/" + id + "/approvals"), comment(), "\"2\""))));
    assertThat(codes).contains(200);
    assertThat(codes).allMatch(s -> s == 200 || s == 409);
    assertThat(Beans.SIGNED).hasSize(2);
    UUID sign2 = Beans.SIGNED.get(1);
    List<Boolean> applied =
        parallel(
            () -> signed(UUID.fromString(id), sign2), () -> signed(UUID.fromString(id), sign2));
    assertThat(applied).containsExactlyInAnyOrder(true, false);
    JsonNode approved = body(perform(get(BASE + "/" + id)));
    assertThat(approved.get("status").asText()).isEqualTo("DA_DUYET");
    assertThat(outbox("oversight.period.reopened.v1")).isEqualTo(1);
    assertThat(outbox("notify.message.requested.v1")).isGreaterThanOrEqualTo(3);
    long approvalRows =
        in(
            oversightDataSource,
            oversightTm,
            jdbc ->
                jdbc.sql("SELECT count(*) FROM oversight.reopen_approval")
                    .query(Long.class)
                    .single());
    assertThat(approvalRows).isEqualTo(2);

    // MOD-08 đã đưa kỳ sang MO_LAI_HAU_KIEM: xác nhận thực hiện.
    assertThat(confirm()).isZero();
    in(
        configDataSource,
        configTm,
        jdbc ->
            jdbc.sql("UPDATE config.period SET status = 'MO_LAI_HAU_KIEM' WHERE id = ?")
                .params(locked)
                .update());
    assertThat(confirm()).isEqualTo(1);
    JsonNode executed = body(perform(get(BASE + "/" + id)));
    assertThat(executed.get("status").asText()).isEqualTo("DA_THUC_HIEN");
    assertThat(executed.get("executedAt").isNull()).isFalse();

    // Truy vấn người đã thẩm định chạy được trên schema appraisal (chưa có đề xuất nào).
    assertThat(
            TenantContext.call(
                tenant,
                () -> participants.reviewersOf(tenant, PeriodId.of(locked), List.of(profile))))
        .isEmpty();
    assertThat(
            TenantContext.call(
                tenant,
                () -> participants.reviewersOfUnits(tenant, PeriodId.of(locked), List.of(child))))
        .isEmpty();
    assertThat(
            TenantContext.call(
                tenant,
                () -> participants.reviewersOfUnits(tenant, PeriodId.of(locked), List.of())))
        .isEmpty();
  }

  private boolean signed(UUID id, UUID signRequest) throws Exception {
    return TenantContext.call(
        tenant, () -> approvals.onSigned(tenant, id, signRequest, Instant.now()));
  }

  private int confirm() throws Exception {
    return TenantContext.call(tenant, () -> approvals.confirmExecuted(tenant));
  }

  private long outbox(String topic) throws Exception {
    return in(
        oversightDataSource,
        oversightTm,
        jdbc ->
            jdbc.sql("SELECT count(*) FROM oversight.outbox_event WHERE topic = ?")
                .params(topic)
                .query(Long.class)
                .single());
  }

  @SafeVarargs
  private static <T> List<T> parallel(Callable<T>... tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> t : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return t.call();
                }));
      }
      start.countDown();
      List<T> out = new ArrayList<>();
      for (Future<T> f : futures) {
        out.add(f.get());
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  private String draft() throws Exception {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("table", "product.acceptance");
    item.put("profileId", profile);
    item.put("unitId", child);
    item.put("fields", List.of("quality_level"));
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("periodId", locked);
    d.put("auditKind", "PHAN_ANH");
    d.put("scope", "HO_SO");
    d.put("scopeIds", List.of(profile));
    d.put("scopeDetail", Map.of("items", List.of(item)));
    d.put("openWorkingDays", 3);
    d.put("reason", REASON);
    d.put("basis", Map.of("kind", "QUYET_DINH", "code", "QĐ 12/QĐ-UBKT"));
    return json.writeValueAsString(d);
  }

  private static String comment() {
    return "{\"comment\":\"Đồng ý mở lại trong phạm vi một hồ sơ đã nêu.\"}";
  }

  private void seed() throws Exception {
    in(
        configDataSource,
        configTm,
        jdbc -> {
          UUID actor = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO config.period (id, code, name, kind, flow, start_date, end_date,"
                      + " period_bucket, status, locked_at, created_by, updated_by)"
                      + " VALUES (?, '2026-Q4', 'Quý IV/2026', 'QUY', 'NHA_NUOC', DATE '2026-10-01',"
                      + " DATE '2026-12-31', 20264, 'DA_KHOA', now(), ?, ?)")
              .params(locked, actor, actor)
              .update();
          binding(jdbc, requester, "decider", child);
          binding(jdbc, decider, "decider", child);
          binding(jdbc, decider, "second_approver", child);
          binding(jdbc, second, "second_approver", parent);
          return null;
        });
    in(
        oversightDataSource,
        oversightTm,
        jdbc -> {
          for (String t : List.of("reopen_request", "reopen_approval")) {
            jdbc.sql("SELECT oversight.fn_create_period_partitions(?::regclass, ?)")
                .params("oversight." + t, 20264)
                .query()
                .listOfRows();
          }
          return null;
        });
    in(
        replicaDataSource,
        replicaTm,
        jdbc -> {
          for (UUID u : List.of(parent, child)) {
            jdbc.sql(
                    "INSERT INTO org_replica.org_unit_v (id, valid_from, code, name, unit_type,"
                        + " source_version, source_event_id)"
                        + " VALUES (?, DATE '2020-01-01', ?, 'Đơn vị', 'SO', 1, ?)")
                .params(u, u.toString().substring(0, 8), UUID.randomUUID())
                .update();
            closure(jdbc, u, u, 0);
          }
          closure(jdbc, parent, child, 1);
          jdbc.sql(
                  "INSERT INTO org_replica.person_v (id, full_name, employment_status,"
                      + " source_version, source_event_id) VALUES (?, ?, 'DANG_LAM_VIEC', 1, ?)")
              .params(decider, "Trần Văn Duyệt", UUID.randomUUID())
              .update();
          return null;
        });
  }

  private static void binding(JdbcClient jdbc, UUID person, String role, UUID unit) {
    UUID grantedBy = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO config.role_binding (id, person_id, role_code, org_unit_id, scope,"
                + " valid_from, granted_by, approved_by, created_by, updated_by)"
                + " VALUES (?, ?, ?, ?, 'DON_VI', now() - interval '1 day', ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            person,
            role,
            unit,
            grantedBy,
            UUID.randomUUID(),
            grantedBy,
            grantedBy)
        .update();
  }

  private static void closure(JdbcClient jdbc, UUID ancestor, UUID descendant, int depth) {
    jdbc.sql(
            "INSERT INTO org_replica.unit_closure_v (tree, ancestor_id, descendant_id, depth)"
                + " VALUES ('CHUYEN_MON', ?, ?, ?)")
        .params(ancestor, descendant, depth)
        .update();
  }

  private <T> T in(DataSource ds, PlatformTransactionManager tm, Function<JdbcClient, T> work)
      throws Exception {
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(JdbcClient.create(ds))));
  }

  private static void as(UUID user, List<String> roles, UUID unit, String acr) {
    Beans.user = user;
    Beans.roles = roles;
    Beans.unit = unit;
    Beans.acr = acr;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
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
