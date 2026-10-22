package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.org.application.orgimport.ImportColumns;
import vn.viettel.okrkpi.org.support.ContractValidator;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;
import vn.viettel.okrkpi.org.support.XlsxFixture;

/**
 * DV-04-05 · QT-01, SCR-0103 "Nhập từ Excel": 202 kèm {@code Location: /api/v1/jobs/{id}}, nhập 1.000 đơn vị và phát
 * EVT-14, tệp lỗi cho báo cáo đúng dòng và cột, gọi lại cùng {@code Idempotency-Key} không nhập hai lần.
 */
@AutoConfigureMockMvc(printOnlyOnFailure = false, print = MockMvcPrint.NONE)
class OrgImportApiIT extends IntegrationTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(OrgImportApiIT.class);

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID adminId;
  private UUID existingRoot;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    adminId = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K3");
    existingRoot = UUID.randomUUID();
    fx.unit(tenant, existingRoot, "BO-X", "Bộ X", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
  }

  private String token() {
    return "Bearer " + TestJwt.token(tenant, adminId, List.of("tenant_admin"), "aal2", null);
  }

  private ResultActions upload(byte[] content, String key) throws Exception {
    MockHttpServletRequestBuilder req = multipart("/api/v1/org/imports")
        .file(new MockMultipartFile("file", "cay-to-chuc.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content))
        .param("effectiveFrom", "2026-01-01")
        .header("Idempotency-Key", key)
        .header(HttpHeaders.AUTHORIZATION, token());
    ResultActions r = mvc.perform(req);
    MvcResult result = r.andReturn();
    ValidationReport report = ContractValidator.validate(result);
    // Bộ kiểm hợp đồng không đọc được thân multipart của MockMvc (báo thiếu thân, coi trường biểu mẫu là tham số truy
    // vấn): chỉ đối chiếu phản hồi, phần yêu cầu đã khớp chữ ký sinh từ hợp đồng.
    assertThat(report.getMessages().stream().filter(m -> !m.getKey().startsWith("validation.request")).toList())
        .as(result.getResponse().getContentAsString()).isEmpty();
    return r;
  }

  private JsonNode waitJob(String location) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    while (true) {
      String body = mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, token()))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      JsonNode job = json.readTree(body);
      String s = job.get("status").asText();
      if ("HOAN_TAT".equals(s) || "THAT_BAI".equals(s)) {
        return job;
      }
      assertThat(System.nanoTime()).as("tác vụ nhập chưa kết thúc").isLessThan(deadline);
      Thread.sleep(100);
    }
  }

  private static List<String> row(String code, String name, String type, String parent, String group,
      String date) {
    return List.of(code, name, "", type, parent, group, "", "TINH", date);
  }

  private int units() {
    Integer n = fx.admin().queryForObject("SELECT count(DISTINCT id) FROM org.org_unit WHERE tenant_id = ?",
        Integer.class, tenant);
    return n == null ? 0 : n;
  }

  @Test
  void imports1000UnitsAndPublishesEvt14() throws Exception {
    List<List<String>> rows = new ArrayList<>();
    rows.add(ImportColumns.ALL);
    // 10 cục dưới đơn vị đã có trong cây, mỗi cục 9 phòng, mỗi phòng 10 tổ: 10 + 90 + 900 = 1.000 đơn vị.
    for (int a = 0; a < 10; a++) {
      String cuc = "CUC-" + a;
      rows.add(row(cuc, "Cục số " + a, "Cơ quan", "BO-X", "K3", ""));
      for (int b = 0; b < 9; b++) {
        String phong = cuc + "-P" + b;
        rows.add(row(phong, "Phòng " + b + " thuộc cục " + a, "Phòng, ban", cuc, "K3", ""));
        for (int c = 0; c < 10; c++) {
          rows.add(row(phong + "-T" + c, "Tổ " + c, "Phòng, ban", phong, "Khối K3", "15/01/2026"));
        }
      }
    }
    byte[] file = XlsxFixture.of(rows);
    int outboxBefore = fx.outboxCount(tenant, "org.unit.changed.v1");
    long heapBefore = heapUsed();
    long start = System.nanoTime();
    MvcResult accepted = upload(file, UUID.randomUUID().toString()).andExpect(status().isAccepted())
        .andExpect(jsonPath("$.type").value("IMPORT")).andReturn();
    long acceptMs = (System.nanoTime() - start) / 1_000_000;
    String location = accepted.getResponse().getHeader(HttpHeaders.LOCATION);
    assertThat(location).startsWith("/api/v1/jobs/");

    JsonNode job = waitJob(location);
    long totalMs = (System.nanoTime() - start) / 1_000_000;
    LOG.info("org-import perf units=1000 fileBytes={} acceptMs={} totalMs={} heapPeakDeltaMB={}", file.length,
        acceptMs, totalMs, (peakHeap() - heapBefore) / (1024 * 1024));
    assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("HOAN_TAT");
    assertThat(job.get("resultLocation").asText()).isEqualTo("/api/v1/org/tree");
    assertThat(units()).isEqualTo(1001);
    assertThat(fx.outboxCount(tenant, "org.unit.changed.v1") - outboxBefore).isEqualTo(1000);
    Integer linked = fx.admin().queryForObject("SELECT count(*) FROM org.org_unit_relation r "
        + "JOIN org.org_unit p ON p.id = r.parent_unit_id AND p.tenant_id = r.tenant_id "
        + "WHERE r.tenant_id = ? AND p.code = 'BO-X'", Integer.class, tenant);
    assertThat(linked).isEqualTo(10);
    Integer content = fx.admin().queryForObject("SELECT count(*) FROM org.import_job WHERE tenant_id = ? "
        + "AND file_content IS NOT NULL", Integer.class, tenant);
    assertThat(content).as("nội dung tệp được xoá khi tác vụ kết thúc").isZero();
  }

  @Test
  void invalidFileReportsErrorsByRowAndColumn_andImportsNothing() throws Exception {
    List<List<String>> rows = new ArrayList<>();
    rows.add(ImportColumns.ALL);
    rows.add(row("A", "Đơn vị A", "Cơ quan", "B", "K3", ""));          // dòng 2: vòng với dòng 3
    rows.add(row("B", "Đơn vị B", "Cơ quan", "A", "K3", ""));          // dòng 3: vòng với dòng 2
    rows.add(row("C", "Đơn vị C", "Cơ quan", "", "K3", ""));           // dòng 4: hợp lệ
    rows.add(row("C", "Đơn vị C trùng", "Cơ quan", "", "K3", ""));     // dòng 5: trùng mã dòng 4
    rows.add(row("D", "Đơn vị D", "Cơ quan", "KHONG-CO", "K3", ""));   // dòng 6: không có cấp trên
    rows.add(row("E", "Đơn vị E", "Cơ quan", "", "K99", ""));          // dòng 7: khối không hợp lệ
    rows.add(row("F", "Đơn vị F", "Loại lạ", "", "K3", "31/02/2026")); // dòng 8: loại và ngày sai
    rows.add(row("BO-X", "Trùng cây", "Cơ quan", "", "K3", ""));       // dòng 9: mã đã có trong cây

    String location = upload(XlsxFixture.of(rows), UUID.randomUUID().toString())
        .andExpect(status().isAccepted()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
    JsonNode job = waitJob(location);
    assertThat(job.get("status").asText()).isEqualTo("THAT_BAI");
    List<String> fields = new ArrayList<>();
    job.at("/problem/errors").forEach(e -> fields.add(e.get("field").asText()));
    assertThat(fields).contains("rows.5.code", "rows.7.subjectGroup", "rows.8.unitType", "rows.8.validFrom",
        "rows.9.code");
    assertThat(job.at("/problem/errors/0/message").asText()).startsWith("Dòng ");
    assertThat(units()).isEqualTo(1);

    // Sửa lỗi theo từng dòng thì lỗi cấp trên và vòng lặp mới hiện (bước kiểm thứ hai).
    List<List<String>> second = new ArrayList<>();
    second.add(ImportColumns.ALL);
    second.add(row("A", "Đơn vị A", "Cơ quan", "B", "K3", ""));
    second.add(row("B", "Đơn vị B", "Cơ quan", "A", "K3", ""));
    second.add(row("D", "Đơn vị D", "Cơ quan", "KHONG-CO", "K3", ""));
    JsonNode job2 = waitJob(upload(XlsxFixture.of(second), UUID.randomUUID().toString())
        .andExpect(status().isAccepted()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION));
    List<String> fields2 = new ArrayList<>();
    job2.at("/problem/errors").forEach(e -> fields2.add(e.get("field").asText()));
    assertThat(fields2).containsExactlyInAnyOrder("rows.2.parentCode", "rows.3.parentCode", "rows.4.parentCode");
    assertThat(units()).isEqualTo(1);
  }

  @Test
  void sameIdempotencyKeyDoesNotImportTwice_andNonExcelIsRejected() throws Exception {
    List<List<String>> rows = List.of(ImportColumns.ALL, row("M", "Đơn vị M", "Cơ quan", "BO-X", "K3", ""));
    byte[] file = XlsxFixture.of(rows);
    String key = UUID.randomUUID().toString();
    String first = upload(file, key).andExpect(status().isAccepted())
        .andExpect(header().exists(HttpHeaders.LOCATION)).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
    waitJob(first);
    String again = upload(file, key).andExpect(status().isAccepted()).andReturn().getResponse()
        .getHeader(HttpHeaders.LOCATION);
    assertThat(again).isEqualTo(first);
    Integer jobs = fx.admin().queryForObject("SELECT count(*) FROM org.import_job WHERE tenant_id = ?",
        Integer.class, tenant);
    assertThat(jobs).isEqualTo(1);
    assertThat(units()).isEqualTo(2);

    upload("không phải excel".getBytes(), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-IMPORT-FILE"));
  }

  private static long heapUsed() {
    ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static long peakHeap() {
    long peak = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
        peak += pool.getPeakUsage().getUsed();
      }
    }
    return peak;
  }
}
