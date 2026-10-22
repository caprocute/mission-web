package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-02-05 trên PostgreSQL thật: tám thao tác kế hoạch quý khớp {@code
 * tasks.yaml}; tạo trùng trả 409; lưu phiên bản {@code DA_DUYET} trả 409; sao chép quý trước chỉ
 * lấy dòng thường xuyên và dòng chưa xong; gợi ý trả đúng số KR chưa có dòng; lưu 300 dòng p95 ≤
 * 500 ms.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanApiIT extends ContractConformanceTest {

  static final UUID MANAGER = TestTaskCallers.DEFAULT_PERSON;
  static final int Q4 = 20264;
  static final int Q1 = 20271;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @Autowired
  @Qualifier("okrDataSource") private DataSource okrDataSource;

  @Autowired
  @Qualifier("okrTransactionManager") private PlatformTransactionManager okrTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID unit = UUID.randomUUID();
  private final UUID q4 = UUID.randomUUID();
  private final UUID q1 = UUID.randomUUID();
  private final List<UUID> krs = new ArrayList<>();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.TASKS;
  }

  @BeforeAll
  void seed() throws Exception {
    period(q4, "2026-Q4", "Quý IV năm 2026", "2026-10-01", "2026-12-31", Q4);
    period(q1, "2027-Q1", "Quý I năm 2027", "2027-01-01", "2027-03-31", Q1);
    in(
        okrDataSource,
        okrTm,
        jdbc -> {
          for (String table : List.of("objective", "key_result")) {
            jdbc.sql("SELECT okr.fn_create_period_partitions(?::regclass, ?)")
                .params("okr." + table, Q1)
                .query()
                .listOfRows();
          }
          UUID objective = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type,"
                      + " owner_unit_id, code, title, tier, status, created_by, updated_by)"
                      + " VALUES (?, ?, ?, 'TAP_THE', ?, 'MT-01', 'Mục tiêu năm', 1, 'DA_DUYET',"
                      + " ?, ?)")
              .params(objective, q1, Q1, unit, MANAGER, MANAGER)
              .update();
          for (int i = 1; i <= 3; i++) {
            UUID kr = UUID.randomUUID();
            krs.add(kr);
            jdbc.sql(
                    "INSERT INTO okr.key_result (id, period_id, period_bucket, objective_id, code,"
                        + " title, metric_type, unit, baseline_value, target_value, direction,"
                        + " weight, due_date, verifier_person_id, checkin_frequency, status,"
                        + " created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, 'SO_LUONG', 'việc',"
                        + " 0, 10, 'TANG', 0.3, DATE '2027-03-15', ?, 'TUAN', 'DA_DUYET', ?, ?)")
                .params(
                    kr, q1, Q1, objective, "KR-0" + i, "Kết quả " + i, MANAGER, MANAGER, MANAGER)
                .update();
          }
          return null;
        });
    inTask(
        jdbc -> {
          for (int bucket : List.of(Q4, Q1)) {
            for (String table : List.of("plan", "plan_version", "plan_item", "task")) {
              jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                  .params("task." + table, bucket)
                  .query()
                  .listOfRows();
            }
          }
          // Kế hoạch quý IV đã duyệt: một việc thường xuyên, một then chốt chưa xong (gắn KR 1),
          // một then chốt đã hoàn thành.
          UUID plan = UUID.randomUUID();
          UUID version = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO task.plan (id, created_by, updated_by, period_id, period_bucket,"
                      + " plan_type, owner_unit_id, current_version_id) VALUES (?, ?, ?, ?, ?,"
                      + " 'TAP_THE', ?, ?)")
              .params(plan, MANAGER, MANAGER, q4, Q4, unit, version)
              .update();
          jdbc.sql(
                  "INSERT INTO task.plan_version (id, created_by, updated_by, period_id,"
                      + " period_bucket, plan_id, version_no, status, is_current, approved_by,"
                      + " approved_at) VALUES (?, ?, ?, ?, ?, ?, 1, 'DA_DUYET', true, ?, now())")
              .params(version, MANAGER, MANAGER, q4, Q4, plan, MANAGER)
              .update();
          UUID done = null;
          String[][] lines = {
            {"Giao ban định kỳ", "THUONG_XUYEN"},
            {"Đề án chuyển đổi số", "THEN_CHOT"},
            {"Tổng kết năm", "THEN_CHOT"}
          };
          for (int i = 0; i < lines.length; i++) {
            UUID item = UUID.randomUUID();
            jdbc.sql(
                    "INSERT INTO task.plan_item (id, created_by, updated_by, period_id,"
                        + " period_bucket, plan_version_id, item_no, key_result_id, title,"
                        + " task_group, lead_unit_id, planned_quantity, unit, due_date)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'báo cáo', DATE"
                        + " '2026-11-15')")
                .params(
                    item,
                    MANAGER,
                    MANAGER,
                    q4,
                    Q4,
                    version,
                    i + 1,
                    i == 1 ? krs.get(0) : null,
                    lines[i][0],
                    lines[i][1],
                    unit)
                .update();
            done = item;
          }
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, due_date, unit,"
                      + " dup_fingerprint, state_machine_version, status, completed_at,"
                      + " plan_item_id) VALUES (?, ?, ?, ?, ?, 'NV-TK-01', 'Tổng kết năm',"
                      + " 'THEN_CHOT', 'KE_HOACH', ?, DATE '2026-12-20', 'báo cáo', ?,"
                      + " 'mac-dinh@2.1.0', 'HOAN_THANH', now(), ?)")
              .params(
                  UUID.randomUUID(),
                  MANAGER,
                  MANAGER,
                  q4,
                  Q4,
                  MANAGER,
                  "tk".getBytes(StandardCharsets.UTF_8),
                  done)
              .update();
          return null;
        });
  }

  @Test
  void keHoachQuyKhopHopDong() throws Exception {
    MvcResult created =
        perform(
            post("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("periodId", q1, "unitId", unit))));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/plans", PathItem.HttpMethod.POST);
    String id = body(created).get("id").asText();
    assertThat(created.getResponse().getHeader("Location")).endsWith("/plans/" + id);

    MvcResult duplicate =
        perform(
            post("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("periodId", q1, "unitId", unit))));
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(duplicate).get("code").asText()).isEqualTo("TASK-409-PLAN-EXISTS");

    MvcResult list = perform(get("/api/v1/plans").param("period", q1.toString()));
    assertResponseConforms(list, "/plans", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items")).hasSize(1);
    assertThat(body(list).get("items").get(0).get("previousPeriod").get("id").asText())
        .isEqualTo(q4.toString());

    // Sao chép quý trước: bỏ dòng then chốt đã hoàn thành, dịch hạn 15/11 sang 15/02.
    MvcResult copied =
        perform(post("/api/v1/plans/" + id + "/versions/1/copy-from").header("If-Match", "\"0\""));
    assertThat(copied.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(copied, "/plans/{id}/versions/{v}/copy-from", PathItem.HttpMethod.POST);
    JsonNode items = body(copied).get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("title").asText()).isEqualTo("Giao ban định kỳ");
    assertThat(items.get(1).get("title").asText()).isEqualTo("Đề án chuyển đổi số");
    assertThat(items.get(0).get("dueDate").asText()).isEqualTo("2027-02-15");
    assertThat(copied.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

    // KR 1 đã có dòng (sao chép), còn KR 2 và KR 3.
    MvcResult suggested = perform(get("/api/v1/plans/" + id + "/suggestions"));
    assertResponseConforms(suggested, "/plans/{id}/suggestions", PathItem.HttpMethod.GET);
    JsonNode suggestions = body(suggested).get("items");
    assertThat(suggestions).hasSize(2);
    assertThat(suggestions.get(0).get("keyResultId").asText()).isEqualTo(krs.get(1).toString());

    assertThat(perform(save(id, null, lines(3))).getResponse().getStatus()).isEqualTo(428);
    long[] ms = new long[20];
    MvcResult saved = null;
    for (int i = 0; i < ms.length; i++) {
      long t0 = System.nanoTime();
      saved = perform(save(id, "\"" + (i + 1) + "\"", lines(300)));
      ms[i] = (System.nanoTime() - t0) / 1_000_000;
      assertThat(saved.getResponse().getStatus()).isEqualTo(200);
    }
    Arrays.sort(ms);
    assertThat(ms[(int) Math.ceil(ms.length * 0.95) - 1]).isLessThanOrEqualTo(500);
    assertResponseConforms(saved, "/plans/{id}/versions/{v}", PathItem.HttpMethod.PUT);
    assertThat(body(saved).get("items")).hasSize(300);
    assertThat(body(saved).get("items").get(299).get("lineNo").asInt()).isEqualTo(300);

    MvcResult versions = perform(get("/api/v1/plans/" + id + "/versions"));
    assertResponseConforms(versions, "/plans/{id}/versions", PathItem.HttpMethod.GET);
    assertThat(body(versions).get("items").get(0).get("itemCount").asInt()).isEqualTo(300);
    MvcResult one = perform(get("/api/v1/plans/" + id + "/versions/1"));
    assertResponseConforms(one, "/plans/{id}/versions/{v}", PathItem.HttpMethod.GET);
    assertThat(one.getResponse().getHeader("ETag")).isEqualTo("\"21\"");
    assertThat(perform(get("/api/v1/plans/" + id + "/versions/7")).getResponse().getStatus())
        .isEqualTo(404);

    // Phiên bản đã duyệt bất biến.
    inTask(
        jdbc ->
            jdbc.sql(
                    "UPDATE task.plan_version SET status = 'DA_DUYET', is_current = true,"
                        + " approved_by = ?, approved_at = now() WHERE plan_id = ?")
                .params(MANAGER, UUID.fromString(id))
                .update());
    MvcResult frozen = perform(save(id, "\"21\"", lines(1)));
    assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(frozen).get("code").asText()).isEqualTo("TASK-409-INVALID-TRANSITION");

    MvcResult page1 =
        perform(get("/api/v1/plans/items").param("period", q1.toString()).param("limit", "200"));
    assertResponseConforms(page1, "/plans/items", PathItem.HttpMethod.GET);
    assertThat(body(page1).get("items")).hasSize(200);
    String next = body(page1).get("nextCursor").asText();
    MvcResult page2 =
        perform(
            get("/api/v1/plans/items")
                .param("period", q1.toString())
                .param("limit", "200")
                .param("cursor", next));
    assertThat(body(page2).get("items")).hasSize(100);
    assertThat(body(page2).get("items").get(0).get("lineNo").asInt()).isEqualTo(201);
  }

  private List<Map<String, Object>> lines(int n) {
    List<Map<String, Object>> out = new ArrayList<>(n);
    for (int i = 1; i <= n; i++) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("title", "Công việc " + i);
      line.put("taskGroup", i % 3 == 0 ? "THEN_CHOT" : "THUONG_XUYEN");
      line.put("leadAssignee", Map.of("kind", "UNIT", "id", unit, "name", "Văn phòng"));
      line.put("productName", "Báo cáo " + i);
      line.put("quantity", BigDecimal.ONE);
      line.put("unit", "báo cáo");
      line.put("dueDate", LocalDate.of(2027, 3, 1).toString());
      out.add(line);
    }
    return out;
  }

  private MockHttpServletRequestBuilder save(
      String id, String ifMatch, List<Map<String, Object>> items) throws Exception {
    MockHttpServletRequestBuilder b =
        put("/api/v1/plans/" + id + "/versions/1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items", items)));
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  private void period(UUID id, String code, String name, String start, String end, int bucket)
      throws Exception {
    in(
        configDataSource,
        configTm,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO config.period (id, code, name, kind, flow, start_date, end_date,"
                        + " period_bucket, status, created_by, updated_by) VALUES (?, ?, ?, 'QUY',"
                        + " 'NHA_NUOC', ?::date, ?::date, ?, 'MO', ?, ?)")
                .params(id, code, name, start, end, bucket, MANAGER, MANAGER)
                .update());
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    return in(taskDataSource, taskTm, work);
  }

  private <T> T in(DataSource ds, PlatformTransactionManager tm, Function<JdbcClient, T> work)
      throws Exception {
    JdbcClient jdbc = JdbcClient.create(ds);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            mvc.perform(
                    request
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .principal(MANAGER::toString))
                .andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
