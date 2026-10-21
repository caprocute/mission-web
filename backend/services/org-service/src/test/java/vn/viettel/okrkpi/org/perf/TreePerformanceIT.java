package vn.viettel.okrkpi.org.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * Đo p95 truy vấn cây hiện hành trên tenant 11.111 đơn vị (gốc, 10 tỉnh, 100 sở, 1.000 phòng, 10.000 tổ; NFR-03
 * ≤ 300 ms). Đo qua toàn bộ ngăn xếp MockMvc (xác thực JWT, RLS, CTE, dựng cây, tuần tự hoá JSON).
 */
@AutoConfigureMockMvc(printOnlyOnFailure = false, print = MockMvcPrint.NONE)
class TreePerformanceIT extends IntegrationTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(TreePerformanceIT.class);
  private static final int WARMUP = 30;
  private static final int RUNS = 200;

  @Autowired
  MockMvc mvc;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID root;
  private UUID province;
  private String bearer;
  private int total;

  @BeforeEach
  void seed() {
    tenant = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K1");
    root = UUID.randomUUID();
    List<Object[]> units = new ArrayList<>();
    List<Object[]> rels = new ArrayList<>();
    units.add(unitRow(root, "G", "Trung ương", group));
    List<UUID> level = List.of(root);
    for (int depth = 1; depth <= 4; depth++) {
      List<UUID> next = new ArrayList<>();
      for (UUID parent : level) {
        for (int i = 0; i < 10; i++) {
          UUID id = UUID.randomUUID();
          units.add(unitRow(id, "D" + depth + "-" + units.size(), "Đơn vị " + depth + "." + units.size(), group));
          rels.add(new Object[] {UUID.randomUUID(), UUID.randomUUID(), tenant, parent, id, Fixtures.SYSTEM_ACTOR,
              Fixtures.SYSTEM_ACTOR});
          next.add(id);
        }
      }
      if (depth == 1) {
        province = next.get(0);
      }
      level = next;
    }
    total = units.size();
    fx.admin().batchUpdate("INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, unit_type, "
        + "subject_group_id, flows, level, valid_from, created_by, updated_by) VALUES (?, ?, ?, ?, ?, 'CO_QUAN', ?, "
        + "'{NHA_NUOC}', 'TINH', '2020-01-01', ?, ?)", units);
    fx.admin().batchUpdate("INSERT INTO org.org_unit_relation (version_id, id, tenant_id, tree, parent_unit_id, "
        + "child_unit_id, relation_type, valid_from, created_by, updated_by) VALUES (?, ?, ?, 'CHUYEN_MON', ?, ?, "
        + "'TRUC_THUOC', '2020-01-01', ?, ?)", rels);
    fx.admin().execute("ANALYZE org.org_unit");
    fx.admin().execute("ANALYZE org.org_unit_relation");
    bearer = "Bearer " + TestJwt.token(tenant, UUID.randomUUID(), List.of("tenant_admin"), "aal2", null);
  }

  private Object[] unitRow(UUID id, String code, String name, UUID group) {
    return new Object[] {UUID.randomUUID(), id, tenant, code, name, group, Fixtures.SYSTEM_ACTOR,
        Fixtures.SYSTEM_ACTOR};
  }

  private double[] measure(MockHttpServletRequestBuilder req) throws Exception {
    for (int i = 0; i < WARMUP; i++) {
      mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
    }
    long[] ns = new long[RUNS];
    for (int i = 0; i < RUNS; i++) {
      long t = System.nanoTime();
      mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
      ns[i] = System.nanoTime() - t;
    }
    Arrays.sort(ns);
    return new double[] {ns[RUNS / 2] / 1e6, ns[(int) Math.ceil(RUNS * 0.95) - 1] / 1e6, ns[RUNS - 1] / 1e6};
  }

  @Test
  void p95TruyVanCayHienHanh() throws Exception {
    assertThat(total).isEqualTo(11_111);
    String today = LocalDate.now().toString();
    LOG.warn("P95-CAY bắt đầu đo");
    double[] lazy = measure(get("/api/v1/org/units/tree").param("tree", "CHUYEN_MON").param("asOf", today));
    LOG.warn("P95-CAY tree xong p95={}", lazy[1]);
    double[] fullSubtree = measure(get("/api/v1/org/units/{id}/subtree", root).param("depth", "6"));
    LOG.warn("P95-CAY subtree gốc xong p95={}", fullSubtree[1]);
    double[] provinceSubtree = measure(get("/api/v1/org/units/{id}/subtree", province).param("depth", "6"));
    double[] history = measure(get("/api/v1/org/units/tree").param("tree", "CHUYEN_MON")
        .param("recordedAsOf", java.time.OffsetDateTime.now().toString()));
    String report = String.format(java.util.Locale.ROOT,
        "P95-CAY nodes=%d runs=%d | tree depth3 (1.111 nút): p50=%.1f p95=%.1f max=%.1f ms | "
            + "subtree gốc depth6 (11.110 nút): p50=%.1f p95=%.1f max=%.1f ms | "
            + "subtree tỉnh depth6 (1.110 nút): p50=%.1f p95=%.1f max=%.1f ms | "
            + "tree depth3 recordedAsOf: p50=%.1f p95=%.1f max=%.1f ms",
        total, RUNS, lazy[0], lazy[1], lazy[2], fullSubtree[0], fullSubtree[1], fullSubtree[2], provinceSubtree[0],
        provinceSubtree[1], provinceSubtree[2], history[0], history[1], history[2]);
    LOG.warn(report);
    java.nio.file.Files.writeString(java.nio.file.Path.of("target", "p95-cay.txt"), report + "\n");
    // NFR-03: truy vấn cây hiện hành (nạp lười ba tầng như giao diện SCR-0103) ≤ 300 ms.
    assertThat(lazy[1]).isLessThanOrEqualTo(300.0);
    assertThat(provinceSubtree[1]).isLessThanOrEqualTo(300.0);
  }
}
