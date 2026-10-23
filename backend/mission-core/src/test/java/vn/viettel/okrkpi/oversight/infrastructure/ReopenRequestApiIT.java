package vn.viettel.okrkpi.oversight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator;
import vn.viettel.okrkpi.oversight.infrastructure.web.OversightCaller;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-07-07 trên PostgreSQL thật: kỳ đang mở bị 409 {@code
 * OVERSIGHT-409-PERIOD-NOT-LOCKED}; toàn kỳ không có quyết định hậu kiểm toàn diện, thời hạn 11
 * ngày làm việc, căn cứ không tồn tại bị 422; chưa bước lên bị 401; lập yêu cầu hồ sơ trong cây đơn
 * vị của người thẩm định (mã {@code MLK-yyyy-001}, {@code CHO_DUYET_1}), gửi lại cùng khoá phát
 * lại, trùng phạm vi 409; xem, danh sách theo phạm vi; tính thử không ghi dòng nào vào {@code
 * appraisal.score_snapshot}, {@code oversight.*}; huỷ cần đúng người lập, {@code If-Match}, lý do;
 * mọi phản hồi khớp hợp đồng {@code oversight.yaml}; tạo và huỷ ghi sổ cái vào {@code
 * oversight.outbox_event}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import(ReopenRequestApiIT.Beans.class)
@DirtiesContext
class ReopenRequestApiIT extends ContractConformanceTest {

  private static final String BASE = "/api/v1/oversight/reopen-requests";
  private static final String REASON =
      "Thi hành kết luận khiếu nại: điều chỉnh hệ số chất lượng nhiệm vụ then chốt số 3 của hồ sơ.";

  /** Người gọi, bộ mô phỏng và bộ tuần tự outbox kiểm thử. */
  static class Beans {
    static volatile UUID user = UUID.randomUUID();
    static volatile List<String> roles = List.of("appraiser");
    static volatile UUID unit;
    static volatile String acr = "aal2";
    static final List<ReopenImpactSimulator.Request> CALLS = new ArrayList<>();

    @Bean
    OversightCaller reopenTestCaller() {
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
      };
    }

    @Bean
    ReopenImpactSimulator reopenTestSimulator() {
      return (tenant, request) -> {
        CALLS.add(request);
        return new ReopenImpactSimulator.Impact(
            "pp-2026.4",
            request.profileIds().stream()
                .map(p -> new ReopenImpactSimulator.Row(p, "TONG_DIEM", "91,2", "92,1"))
                .toList(),
            2);
      };
    }

    @Bean
    @Primary
    OutboxPayloadSerializer reopenTestSerializer() {
      return (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Autowired
  @Qualifier("oversightDataSource") private DataSource oversightDataSource;

  @Autowired
  @Qualifier("oversightTransactionManager") private PlatformTransactionManager oversightTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @Autowired
  @Qualifier("appraisalDataSource") private DataSource appraisalDataSource;

  @Autowired
  @Qualifier("appraisalTransactionManager") private PlatformTransactionManager appraisalTm;

  @Autowired
  @Qualifier("orgReplicaDataSource") private DataSource replicaDataSource;

  @Autowired
  @Qualifier("orgReplicaTransactionManager") private PlatformTransactionManager replicaTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID locked = UUID.randomUUID();
  private final UUID open = UUID.randomUUID();
  private final UUID parent = UUID.randomUUID();
  private final UUID child = UUID.randomUUID();
  private final UUID elsewhere = UUID.randomUUID();
  private final UUID profile = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OVERSIGHT;
  }

  @Test
  void lapXemTinhThuVaHuyYeuCauMoLaiKy() throws Exception {
    seed();
    as(appraiser, List.of("appraiser"), parent, "aal2");

    // Kỳ đang mở: 409.
    MvcResult notLocked = perform(write(post(BASE), draft(open, "HO_SO", 3, decision())));
    assertThat(status(notLocked)).as(text(notLocked)).isEqualTo(409);
    assertThat(body(notLocked).get("code").asText()).isEqualTo("OVERSIGHT-409-PERIOD-NOT-LOCKED");
    assertResponseConforms(notLocked, "/oversight/reopen-requests", PathItem.HttpMethod.POST);

    // Toàn kỳ không có quyết định hậu kiểm toàn diện, 11 ngày làm việc, căn cứ không có: 422.
    as(appraiser, List.of("inspector"), null, "aal2");
    MvcResult whole =
        perform(
            write(
                post(BASE),
                draft(locked, "TOAN_KY", 3, Map.of("kind", "CANH_BAO", "id", UUID.randomUUID()))));
    assertThat(status(whole)).as(text(whole)).isEqualTo(422);
    assertThat(text(whole)).contains("\"scope\"");
    as(appraiser, List.of("appraiser"), parent, "aal2");
    MvcResult tooLong = perform(write(post(BASE), draft(locked, "HO_SO", 11, decision())));
    assertThat(status(tooLong)).isEqualTo(422);
    MvcResult noBasis =
        perform(
            write(
                post(BASE),
                draft(locked, "HO_SO", 3, Map.of("kind", "HAU_KIEM", "id", UUID.randomUUID()))));
    assertThat(status(noBasis)).as(text(noBasis)).isEqualTo(422);

    // Chưa bước lên: 401.
    as(appraiser, List.of("appraiser"), parent, "aal1");
    assertThat(status(perform(write(post(BASE), draft(locked, "HO_SO", 3, decision())))))
        .isEqualTo(401);

    // Lập yêu cầu; gửi lại cùng khoá phát lại cùng phản hồi.
    as(appraiser, List.of("appraiser"), parent, "aal2");
    String key = UUID.randomUUID().toString();
    MvcResult created =
        perform(write(post(BASE), draft(locked, "HO_SO", 3, decision()), null, key));
    assertThat(status(created)).as(text(created)).isEqualTo(201);
    assertResponseConforms(created, "/oversight/reopen-requests", PathItem.HttpMethod.POST);
    JsonNode c = body(created);
    int year = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).getYear();
    assertThat(c.get("code").asText()).isEqualTo("MLK-" + year + "-001");
    assertThat(c.get("status").asText()).isEqualTo("CHO_DUYET_1");
    assertThat(c.get("periodStatus").asText()).isEqualTo("DA_KHOA");
    assertThat(c.at("/requestedBy/name").asText()).isEqualTo("Lê Thu Hà");
    assertThat(c.at("/scopeDetail/items/0/table").asText()).isEqualTo("product.acceptance");
    assertThat(c.get("approvers")).hasSize(2);
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    String id = c.get("id").asText();
    MvcResult replay = perform(write(post(BASE), draft(locked, "HO_SO", 3, decision()), null, key));
    assertThat(status(replay)).isEqualTo(201);
    assertThat(body(replay).get("id").asText()).isEqualTo(id);
    MvcResult overlap = perform(write(post(BASE), draft(locked, "HO_SO", 3, decision())));
    assertThat(status(overlap)).as(text(overlap)).isEqualTo(409);
    assertThat(body(overlap).get("code").asText()).isEqualTo("OVERSIGHT-409-INVALID-STATE");

    // Xem và danh sách; người thẩm định ngoài cây đơn vị không thấy.
    MvcResult got = perform(get(BASE + "/" + id));
    assertThat(status(got)).as(text(got)).isEqualTo(200);
    assertResponseConforms(got, "/oversight/reopen-requests/{id}", PathItem.HttpMethod.GET);
    MvcResult page = perform(get(BASE + "?period=" + locked + "&status=CHO_DUYET_1,CHO_DUYET_2"));
    assertThat(status(page)).as(text(page)).isEqualTo(200);
    assertResponseConforms(page, "/oversight/reopen-requests", PathItem.HttpMethod.GET);
    assertThat(body(page).get("items")).hasSize(1);
    as(UUID.randomUUID(), List.of("appraiser"), elsewhere, "aal2");
    assertThat(status(perform(get(BASE + "/" + id)))).isEqualTo(404);
    assertThat(body(perform(get(BASE))).get("items")).isEmpty();

    // Tính thử: không ghi gì.
    as(appraiser, List.of("appraiser"), parent, "aal2");
    long snapshots = count(appraisalDataSource, appraisalTm, "appraisal.score_snapshot");
    long outbox = count(oversightDataSource, oversightTm, "oversight.outbox_event");
    long keys = count(oversightDataSource, oversightTm, "oversight.idempotency_key");
    MvcResult impact =
        perform(
            write(
                post(BASE + "/" + id + "/simulate"),
                "{\"adjustments\":[{\"targetKey\":\"kpi.task3.quality\",\"oldValue\":\"B\","
                    + "\"newValue\":\"A\",\"reason\":\"Theo kết luận khiếu nại\"}]}"));
    assertThat(status(impact)).as(text(impact)).isEqualTo(200);
    assertResponseConforms(
        impact, "/oversight/reopen-requests/{id}/simulate", PathItem.HttpMethod.POST);
    assertThat(body(impact).get("policyVersion").asText()).isEqualTo("pp-2026.4");
    assertThat(body(impact).at("/rows/0/after").asText()).isEqualTo("92,1");
    assertThat(Beans.CALLS)
        .last()
        .satisfies(r -> assertThat(r.profileIds()).containsExactly(profile));
    assertThat(count(appraisalDataSource, appraisalTm, "appraisal.score_snapshot"))
        .isEqualTo(snapshots);
    assertThat(count(oversightDataSource, oversightTm, "oversight.outbox_event")).isEqualTo(outbox);
    assertThat(count(oversightDataSource, oversightTm, "oversight.idempotency_key"))
        .isEqualTo(keys);

    // Huỷ: người khác 403, thiếu If-Match 428, lý do ngắn 422, đúng người lập 200, huỷ lại 409.
    as(UUID.randomUUID(), List.of("appraiser"), parent, "aal2");
    assertThat(status(perform(write(post(BASE + "/" + id + "/cancel"), cancel(), "\"0\""))))
        .isEqualTo(403);
    as(appraiser, List.of("appraiser"), parent, "aal2");
    assertThat(status(perform(write(post(BASE + "/" + id + "/cancel"), cancel(), null))))
        .isEqualTo(428);
    assertThat(
            status(
                perform(
                    write(post(BASE + "/" + id + "/cancel"), "{\"reason\":\"ngắn\"}", "\"0\""))))
        .isEqualTo(422);
    MvcResult cancelled = perform(write(post(BASE + "/" + id + "/cancel"), cancel(), "\"0\""));
    assertThat(status(cancelled)).as(text(cancelled)).isEqualTo(200);
    assertResponseConforms(
        cancelled, "/oversight/reopen-requests/{id}/cancel", PathItem.HttpMethod.POST);
    assertThat(body(cancelled).get("status").asText()).isEqualTo("DA_DONG");
    assertThat(cancelled.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    MvcResult again = perform(write(post(BASE + "/" + id + "/cancel"), cancel(), "\"1\""));
    assertThat(status(again)).isEqualTo(409);

    // Sổ cái: một sự kiện tạo, một sự kiện huỷ.
    long audits =
        in(
            oversightDataSource,
            oversightTm,
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM oversight.outbox_event"
                            + " WHERE topic = 'sys.audit.v1' AND aggregate_type = 'OversightAudit'")
                    .query(Long.class)
                    .single());
    assertThat(audits).isEqualTo(2);
  }

  private Map<String, Object> decision() {
    return Map.of("kind", "QUYET_DINH", "code", "QĐ 12/QĐ-UBKT");
  }

  private String draft(UUID period, String scope, int days, Map<String, Object> basis)
      throws Exception {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("table", "product.acceptance");
    item.put("profileId", profile);
    item.put("unitId", child);
    item.put("fields", List.of("quality_level"));
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("periodId", period);
    d.put("auditKind", "PHAN_ANH");
    d.put("scope", scope);
    d.put("scopeIds", "TOAN_KY".equals(scope) ? List.of() : List.of(profile));
    d.put("scopeDetail", Map.of("items", List.of(item)));
    d.put("openWorkingDays", days);
    d.put("reason", REASON);
    d.put("basis", basis);
    return json.writeValueAsString(d);
  }

  private static String cancel() {
    return "{\"reason\":\"Đã có quyết định xử lý khác, không cần mở lại.\"}";
  }

  private void seed() throws Exception {
    in(
        configDataSource,
        configTm,
        jdbc -> {
          period(
              jdbc, locked, "2026-Q4", "Quý IV/2026", "2026-10-01", "2026-12-31", 20264, "DA_KHOA");
          period(jdbc, open, "2027-Q1", "Quý I/2027", "2027-01-01", "2027-03-31", 20271, "MO");
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
          for (UUID u : List.of(parent, child, elsewhere)) {
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
              .params(appraiser, "Lê Thu Hà", UUID.randomUUID())
              .update();
          return null;
        });
  }

  private static void closure(JdbcClient jdbc, UUID ancestor, UUID descendant, int depth) {
    jdbc.sql(
            "INSERT INTO org_replica.unit_closure_v (tree, ancestor_id, descendant_id, depth)"
                + " VALUES ('CHUYEN_MON', ?, ?, ?)")
        .params(ancestor, descendant, depth)
        .update();
  }

  private static void period(
      JdbcClient jdbc,
      UUID id,
      String code,
      String name,
      String start,
      String end,
      int bucket,
      String status) {
    UUID actor = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO config.period (id, code, name, kind, flow, start_date, end_date,"
                + " period_bucket, status, locked_at, created_by, updated_by)"
                + " VALUES (?, ?, ?, 'QUY', 'NHA_NUOC', ?::date, ?::date, ?, ?,"
                + " CASE WHEN ? = 'DA_KHOA' THEN now() END, ?, ?)")
        .params(id, code, name, start, end, bucket, status, status, actor, actor)
        .update();
  }

  private long count(DataSource ds, PlatformTransactionManager tm, String table) throws Exception {
    return in(ds, tm, jdbc -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
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

  private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, String body) {
    return write(b, body, null, UUID.randomUUID().toString());
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String body, String ifMatch) {
    return write(b, body, ifMatch, UUID.randomUUID().toString());
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String body, String ifMatch, String key) {
    b.header("Idempotency-Key", key);
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
