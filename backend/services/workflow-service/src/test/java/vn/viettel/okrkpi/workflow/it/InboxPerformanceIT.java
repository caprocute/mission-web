package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.InboxFilter;
import vn.viettel.okrkpi.workflow.infrastructure.persistence.JdbcTaskProjectionStore;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;

/**
 * Đo truy vấn hộp việc trên 1 triệu dòng {@code wf_task_projection} (DV-06-02 mục 3; TL-01 SVC-06
 * "dưới 5 ms"; NFR-03 API đọc p95 ≤ 300 ms). Thời gian CSDL lấy từ {@code EXPLAIN (ANALYZE)} của
 * đúng câu lệnh ứng dụng, chạy bằng {@code svc_flowable} có RLS.
 */
class InboxPerformanceIT extends WorkflowIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(InboxPerformanceIT.class);
  private static final int ROWS = 1_000_000;
  private static final int SAMPLES = 300;

  @Autowired private JdbcTemplate appJdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper json;

  @Test
  void hopViecP95DuoiNamMiliGiayTaiCsdlTrenMotTrieuDong() throws Exception {
    JdbcTemplate owner = WorkflowTestDatabase.owner();
    long t0 = System.nanoTime();
    // 20 tenant, 200.000 người, 5.000 đơn vị; 2/3 việc giao thẳng, 1/3 việc theo nhóm ứng viên.
    owner.execute(
        """
        INSERT INTO flowable.wf_task_projection (task_id, tenant_id, assignee_id, candidate_role,
          candidate_unit_id, business_type, business_id, template_code, title, period_id, due_at,
          created_at, row_version)
        SELECT 'perf-' || g,
               md5('t' || (g % 20))::uuid,
               CASE WHEN g % 3 <> 0 THEN md5('u' || (g % 200000))::uuid END,
               CASE WHEN g % 3 = 0
                    THEN (ARRAY['leader','manager','appraiser','decider','member'])[1 + g % 5] END,
               CASE WHEN g % 3 = 0 THEN md5('unit' || (g % 5000))::uuid END,
               (ARRAY['OKR_OBJECTIVE','PLAN','APPRAISAL_PROFILE'])[1 + g % 3],
               md5('b' || g)::uuid,
               'duyet-okr',
               'Phê duyệt hồ sơ số ' || g,
               md5('p' || (g % 8))::uuid,
               CASE WHEN g % 11 = 0 THEN NULL
                    ELSE now() + ((g % 720) - 100) * interval '1 hour' END,
               now() - (g % 1000) * interval '1 minute',
               1
          FROM generate_series(1, ROWS_N) g
        """
            .replace("ROWS_N", Integer.toString(ROWS)));
    owner.execute("ANALYZE flowable.wf_task_projection");
    log.info("Nạp {} dòng chiếu trong {} ms", ROWS, (System.nanoTime() - t0) / 1_000_000);

    // Người dùng 1234: tenant 14, đơn vị 14 (vai trò ứng viên của đơn vị này là member).
    UUID tenant = uuid("t14");
    UUID user = uuid("u1234");
    UUID unit = uuid("unit14");
    Approver approver = new Approver(tenant, user, Set.of("member", "leader"), unit);
    InboxFilter filter = new InboxFilter(approver, null, null);
    JdbcTaskProjectionStore.SqlQuery query = JdbcTaskProjectionStore.inboxQuery(filter, null, 21);

    List<Double> dbMillis = new ArrayList<>();
    List<Double> clientMillis = new ArrayList<>();
    TenantContext.run(
        TenantId.of(tenant),
        () ->
            new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                    s -> {
                      for (int i = 0; i < SAMPLES; i++) {
                        String plan =
                            appJdbc.queryForObject(
                                "EXPLAIN (ANALYZE, FORMAT JSON) " + query.sql(),
                                String.class,
                                query.args());
                        dbMillis.add(executionTime(plan));
                        long start = System.nanoTime();
                        int rows = appJdbc.queryForList(query.sql(), query.args()).size();
                        clientMillis.add((System.nanoTime() - start) / 1e6);
                        assertThat(rows).isPositive();
                      }
                    }));
    String plan =
        TenantContext.call(
            TenantId.of(tenant),
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        s ->
                            String.join(
                                "\n",
                                appJdbc.queryForList(
                                    "EXPLAIN " + query.sql(), String.class, query.args()))));
    double dbP95 = p95(dbMillis);
    double clientP95 = p95(clientMillis);
    log.info("Kế hoạch truy vấn hộp việc:\n{}", plan);
    log.info(
        "Hộp việc trên {} dòng: CSDL p50={} ms p95={} ms; JDBC p95={} ms",
        ROWS,
        String.format("%.3f", p50(dbMillis)),
        String.format("%.3f", dbP95),
        String.format("%.3f", clientP95));
    assertThat(plan).contains("ix_wf_task_projection__");
    assertThat(dbP95).isLessThan(5.0);

    // Qua API (bảo mật, OPA giả, JSON): NFR-03 đọc p95 ≤ 300 ms.
    List<Double> apiMillis = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      long start = System.nanoTime();
      String body =
          mvc.perform(
                  get("/api/v1/workflow/inbox").with(user(tenant, user, unit, "member", "leader")))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      apiMillis.add((System.nanoTime() - start) / 1e6);
      JsonNode page = json.readTree(body);
      assertThat(page.path("items").size()).isEqualTo(20);
    }
    double apiP95 = p95(apiMillis);
    log.info("API hộp việc p95={} ms", String.format("%.3f", apiP95));
    assertThat(apiP95).isLessThan(300.0);
  }

  private static UUID uuid(String seed) throws Exception {
    byte[] md5 = java.security.MessageDigest.getInstance("MD5").digest(seed.getBytes());
    StringBuilder hex = new StringBuilder();
    for (byte b : md5) {
      hex.append(String.format("%02x", b));
    }
    String h = hex.toString();
    return UUID.fromString(
        h.substring(0, 8)
            + "-"
            + h.substring(8, 12)
            + "-"
            + h.substring(12, 16)
            + "-"
            + h.substring(16, 20)
            + "-"
            + h.substring(20));
  }

  private double executionTime(String planJson) {
    try {
      return json.readTree(planJson).get(0).get("Execution Time").asDouble();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static double p95(List<Double> values) {
    return percentile(values, 0.95);
  }

  private static double p50(List<Double> values) {
    return percentile(values, 0.50);
  }

  private static double percentile(List<Double> values, double p) {
    double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(sorted);
    int index = (int) Math.ceil(p * sorted.length) - 1;
    return sorted[Math.max(0, index)];
  }
}
