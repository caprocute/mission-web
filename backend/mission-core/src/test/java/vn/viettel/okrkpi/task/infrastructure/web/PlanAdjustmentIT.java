package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.domain.plan.PlanSchedule;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxPlanNotices;

/**
 * Kiểm thử tích hợp CORE-02-10 trên PostgreSQL thật: tạo điều chỉnh n+1 và so sánh khớp {@code
 * tasks.yaml}; bỏ việc đã có minh chứng xác nhận (MSG-04-11), đổi hạn nhiệm vụ đang thực hiện
 * (MSG-04-12) bị chặn; thiếu lý do bị chặn khi gửi; điều chỉnh lần thứ ba với giới hạn 2 trả 422
 * (MSG-04-07). Kỳ đặt quanh ngày chạy để luôn trước hạn điều chỉnh.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanAdjustmentIT extends ContractConformanceTest {

  static final UUID MANAGER = TestTaskCallers.DEFAULT_PERSON;
  private static final String KEY = "Idempotency-Key";

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

  /** EVT-21 cần Apicurio; kiểm thử này không gửi duyệt thành công. */
  @MockitoBean private OutboxPlanNotices planNotices;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID unit = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private final UUID plan = UUID.randomUUID();
  private final UUID v1 = UUID.randomUUID();
  private final UUID confirmed = UUID.randomUUID();
  private final UUID inProgress = UUID.randomUUID();
  private final UUID untouched = UUID.randomUUID();
  private final LocalDate start = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).withDayOfMonth(1);
  private final LocalDate due = start.plusMonths(1).plusDays(14);
  private int bucket;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.TASKS;
  }

  @BeforeAll
  void seed() throws Exception {
    bucket = PlanSchedule.bucketOf(start);
    in(
        configDataSource,
        configTm,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO config.period (id, code, name, kind, flow, start_date, end_date,"
                        + " period_bucket, status, created_by, updated_by) VALUES (?, 'DC-Q',"
                        + " 'Quý điều chỉnh', 'QUY', 'NHA_NUOC', ?, ?, ?, 'MO', ?, ?)")
                .params(period, start, start.plusMonths(3).minusDays(1), bucket, MANAGER, MANAGER)
                .update());
    inTask(
        jdbc -> {
          for (String table : List.of("plan", "plan_version", "plan_item", "task")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, bucket)
                .query()
                .listOfRows();
          }
          jdbc.sql(
                  "INSERT INTO task.plan (id, created_by, updated_by, period_id, period_bucket,"
                      + " plan_type, owner_unit_id, current_version_id) VALUES (?, ?, ?, ?, ?,"
                      + " 'TAP_THE', ?, ?)")
              .params(plan, MANAGER, MANAGER, period, bucket, unit, v1)
              .update();
          jdbc.sql(
                  "INSERT INTO task.plan_version (id, created_by, updated_by, period_id,"
                      + " period_bucket, plan_id, version_no, status) VALUES (?, ?, ?, ?, ?, ?, 1,"
                      + " 'CHO_DUYET')")
              .params(v1, MANAGER, MANAGER, period, bucket, plan)
              .update();
          Object[][] lines = {
            {confirmed, "Báo cáo chuyên đề"},
            {inProgress, "Thẩm định hồ sơ"},
            {untouched, "Rà soát"}
          };
          for (int i = 0; i < lines.length; i++) {
            jdbc.sql(
                    "INSERT INTO task.plan_item (id, created_by, updated_by, period_id,"
                        + " period_bucket, plan_version_id, item_no, title, task_group,"
                        + " lead_unit_id, planned_quantity, unit, due_date) VALUES (?, ?, ?, ?, ?,"
                        + " ?, ?, ?, 'THEN_CHOT', ?, 1, 'báo cáo', ?)")
                .params(
                    lines[i][0],
                    MANAGER,
                    MANAGER,
                    period,
                    bucket,
                    v1,
                    i + 1,
                    lines[i][1],
                    unit,
                    due)
                .update();
          }
          jdbc.sql(
                  "UPDATE task.plan_version SET status = 'DA_DUYET', is_current = true,"
                      + " approved_by = ?, approved_at = now() WHERE id = ?")
              .params(MANAGER, v1)
              .update();
          task(jdbc, "NV-DC-01", "Báo cáo chuyên đề", "HOAN_THANH", confirmed);
          task(jdbc, "NV-DC-02", "Thẩm định hồ sơ", "DANG_THUC_HIEN", inProgress);
          return null;
        });
  }

  @Test
  void dieuChinhKeHoachKhopHopDongVaGiuKetQua() throws Exception {
    String base = "/api/v1/plans/" + plan;
    MvcResult created = perform(adjust("Bổ sung theo kết luận của Thường trực"));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/plans/{id}/versions", PathItem.HttpMethod.POST);
    assertThat(created.getResponse().getHeader("Location")).isEqualTo(base + "/versions/2");
    JsonNode v2 = body(created);
    assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
    assertThat(v2.get("status").asText()).isEqualTo("NHAP");
    assertThat(v2.get("changeReason").asText()).isEqualTo("Bổ sung theo kết luận của Thường trực");
    assertThat(v2.get("items").findValuesAsText("originItemId"))
        .containsExactly(confirmed.toString(), inProgress.toString(), untouched.toString());
    String etag = created.getResponse().getHeader("ETag");

    // Chỉ một phiên bản điều chỉnh mở tại một thời điểm.
    MvcResult again = perform(adjust("Lần nữa"));
    assertThat(again.getResponse().getStatus()).isEqualTo(409);

    MvcResult same =
        perform(get(base + "/versions/2/diff").param("base", "1").param("mode", "all"));
    assertResponseConforms(same, "/plans/{id}/versions/{v}/diff", PathItem.HttpMethod.GET);
    assertThat(body(same).get("lines").findValuesAsText("change"))
        .containsOnly("KHONG_DOI")
        .hasSize(3);
    assertThat(body(same).get("adjustmentNo").asInt()).isEqualTo(1);
    assertThat(body(same).get("adjustmentsMax").asInt()).isEqualTo(2);

    // BR-04-11: không bỏ việc đã có minh chứng được xác nhận.
    MvcResult removeDone =
        perform(
            save(
                etag,
                List.of(
                    line(inProgress, "Thẩm định hồ sơ", due, null),
                    line(untouched, "Rà soát", due, null)),
                List.of(Map.of("originItemId", confirmed, "changeReason", "Không còn phù hợp"))));
    assertThat(removeDone.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(removeDone).get("detail").asText())
        .isEqualTo("Không bỏ được việc \"Báo cáo chuyên đề\" vì đã có minh chứng được xác nhận.");

    // BR-04-12: không đổi hạn nhiệm vụ đang thực hiện.
    MvcResult moveDue =
        perform(
            save(
                etag,
                List.of(
                    line(confirmed, "Báo cáo chuyên đề", due, null),
                    line(inProgress, "Thẩm định hồ sơ", due.plusDays(7), "Lùi hạn"),
                    line(untouched, "Rà soát", due, null)),
                List.of()));
    assertThat(moveDue.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(moveDue).get("detail").asText())
        .startsWith("Hạn của nhiệm vụ đang thực hiện không đổi qua điều chỉnh kế hoạch.");

    // Thiếu lý do: lưu được nháp, kiểm tra báo chặn, gửi duyệt bị chặn.
    List<Map<String, Object>> noReason =
        List.of(
            line(confirmed, "Báo cáo chuyên đề", due, null),
            line(inProgress, "Thẩm định hồ sơ", due, null),
            line(untouched, "Rà soát quy chế làm việc", due, null),
            line(null, "Sơ kết chuyên đề mới", due, null));
    MvcResult draft = perform(save(etag, noReason, List.of()));
    assertThat(draft.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(draft, "/plans/{id}/versions/{v}", PathItem.HttpMethod.PUT);
    etag = draft.getResponse().getHeader("ETag");
    MvcResult check =
        perform(
            post(base + "/versions/2/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", noReason))));
    assertResponseConforms(check, "/plans/{id}/versions/{v}/check", PathItem.HttpMethod.POST);
    List<Integer> reasonLines = new ArrayList<>();
    body(check)
        .get("checks")
        .forEach(
            c -> {
              if ("BR-04-10".equals(c.get("rule").asText())) {
                reasonLines.add(c.get("lineNo").asInt());
              }
            });
    assertThat(reasonLines).containsExactlyInAnyOrder(3, 4);
    MvcResult submit = perform(post(base + "/versions/2/submit").header("If-Match", etag));
    assertThat(submit.getResponse().getStatus()).isEqualTo(422);

    // Đủ lý do, bỏ dòng chưa có kết quả: so sánh ra 1 thêm, 1 bỏ kèm lý do.
    MvcResult ok =
        perform(
            save(
                etag,
                List.of(
                    line(confirmed, "Báo cáo chuyên đề", due, null),
                    line(inProgress, "Thẩm định hồ sơ", due, null),
                    line(null, "Sơ kết chuyên đề mới", due, "Theo chỉ đạo đột xuất")),
                List.of(
                    Map.of("originItemId", untouched, "changeReason", "Đã gộp vào việc khác"))));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(ok).get("checks").findValuesAsText("rule")).doesNotContain("BR-04-10");
    MvcResult diff = perform(get(base + "/versions/2/diff").param("base", "1"));
    assertResponseConforms(diff, "/plans/{id}/versions/{v}/diff", PathItem.HttpMethod.GET);
    JsonNode d = body(diff);
    assertThat(d.get("added").asInt()).isEqualTo(1);
    assertThat(d.get("modified").asInt()).isZero();
    assertThat(d.get("removed").asInt()).isEqualTo(1);
    assertThat(d.get("lines")).hasSize(2);
    assertThat(d.get("lines").get(1).get("change").asText()).isEqualTo("BO");
    assertThat(d.get("lines").get(1).get("reason").asText()).isEqualTo("Đã gộp vào việc khác");
    assertThat(perform(get(base + "/versions/2/diff").param("base", "5")).getResponse().getStatus())
        .isEqualTo(404);

    // Giới hạn 2 lần mỗi quý: duyệt v2, v3 rồi điều chỉnh lần thứ ba bị chặn.
    approve(2);
    MvcResult v3 = perform(adjust("Điều chỉnh lần hai"));
    assertThat(v3.getResponse().getStatus()).isEqualTo(201);
    approve(3);
    MvcResult third = perform(adjust("Điều chỉnh lần ba"));
    assertThat(third.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(third).get("detail").asText())
        .isEqualTo("Kế hoạch đã điều chỉnh 2 lần trong quý, đạt giới hạn.");
    assertThat(body(third).get("errors").get(0).get("rule").asText()).isEqualTo("BR-04-10");
  }

  /** Duyệt trực tiếp ở CSDL (luồng ký số có kiểm thử riêng ở CORE-02-09). */
  private void approve(int versionNo) throws Exception {
    inTask(
        jdbc -> {
          jdbc.sql("UPDATE task.plan_version SET is_current = false WHERE plan_id = ?")
              .params(plan)
              .update();
          return jdbc.sql(
                  "UPDATE task.plan_version SET status = 'DA_DUYET', is_current = true,"
                      + " approved_by = ?, approved_at = now() WHERE plan_id = ? AND version_no = ?")
              .params(UUID.randomUUID(), plan, versionNo)
              .update();
        });
  }

  private void task(JdbcClient jdbc, String code, String title, String status, UUID item) {
    jdbc.sql(
            "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket, code,"
                + " title, task_group, source, assigner_id, due_date, unit, dup_fingerprint,"
                + " state_machine_version, status, completed_at, plan_item_id) VALUES (?, ?, ?, ?,"
                + " ?, ?, ?, 'THEN_CHOT', 'KE_HOACH', ?, ?, 'báo cáo', ?, 'mac-dinh@2.1.0', ?,"
                + " CASE WHEN ? = 'HOAN_THANH' THEN now() END, ?)")
        .params(
            UUID.randomUUID(),
            MANAGER,
            MANAGER,
            period,
            bucket,
            code,
            title,
            MANAGER,
            due,
            code.getBytes(StandardCharsets.UTF_8),
            status,
            status,
            item)
        .update();
  }

  private Map<String, Object> line(UUID origin, String title, LocalDate dueDate, String reason) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (origin != null) {
      m.put("originItemId", origin);
    }
    m.put("title", title);
    m.put("taskGroup", "THEN_CHOT");
    m.put("leadAssignee", Map.of("kind", "UNIT", "id", unit, "name", "Văn phòng"));
    m.put("productName", title);
    m.put("quantity", 1);
    m.put("unit", "báo cáo");
    m.put("dueDate", dueDate.toString());
    if (reason != null) {
      m.put("changeReason", reason);
    }
    return m;
  }

  private MockHttpServletRequestBuilder adjust(String reason) throws Exception {
    return post("/api/v1/plans/" + plan + "/versions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("changeReason", reason)));
  }

  private MockHttpServletRequestBuilder save(
      String ifMatch, List<Map<String, Object>> items, List<Map<String, Object>> removed)
      throws Exception {
    return put("/api/v1/plans/" + plan + "/versions/2")
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("items", items, "removedItems", removed)));
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
                        .with(
                            r -> {
                              if (r.getHeader(KEY) == null) {
                                r.addHeader(KEY, UUID.randomUUID().toString());
                              }
                              return r;
                            })
                        .principal(MANAGER::toString))
                .andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
