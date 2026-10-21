package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.org.support.ContractValidator;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * DV-04-03: quyết định thay đổi tổ chức ({@code POST /api/v1/org/units/{id}/changes}) cho 6 loại
 * {@code THANH_LAP}, {@code DOI_TEN}, {@code SAP_NHAP}, {@code HOP_NHAT}, {@code CHIA_TACH}, {@code GIAI_THE},
 * chuyển phân công ({@code MSG-01-11}), bắt buộc tiền thân ({@code BR-01-07}, {@code MSG-01-09}), cảnh báo kỳ đánh giá
 * đã khoá ({@code MSG-01-10}), phát sự kiện {@code EVT-14} và {@code EVT-15}.
 */
@AutoConfigureMockMvc
class OrgChangeApiIT extends IntegrationTestBase {

  private static final OffsetDateTime T1 = OffsetDateTime.parse("2025-01-02T08:00:00+07:00");

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID admin;
  private UUID group;
  private UUID root;
  private UUID unitA;
  private UUID unitB;
  private UUID title;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    admin = UUID.randomUUID();
    group = fx.subjectGroup(tenant, "K3");
    root = UUID.randomUUID();
    unitA = UUID.randomUUID();
    unitB = UUID.randomUUID();
    LocalDate base = LocalDate.of(2020, 1, 1);
    fx.unitVersion(tenant, root, "UBND", "Ủy ban nhân dân tỉnh", "CO_QUAN", group, base, null, T1, null, 0);
    fx.unitVersion(tenant, unitA, "STC", "Sở Tài chính", "CO_QUAN", group, base, null, T1, null, 0);
    fx.unitVersion(tenant, unitB, "SKHDT", "Sở Kế hoạch và Đầu tư", "CO_QUAN", group, base, null, T1, null, 0);
    fx.relationVersion(tenant, UUID.randomUUID(), "CHUYEN_MON", root, unitA, base, null, T1, null);
    fx.relationVersion(tenant, UUID.randomUUID(), "CHUYEN_MON", root, unitB, base, null, T1, null);
    title = fx.jobTitle(tenant);
  }

  private String token() {
    return "Bearer " + TestJwt.token(tenant, admin, List.of("tenant_admin"), "aal2", root);
  }

  private ResultActions asAdmin(MockHttpServletRequestBuilder req) throws Exception {
    ResultActions r = mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token()));
    MvcResult result = r.andReturn();
    ValidationReport report = ContractValidator.validate(result);
    assertThat(report.getMessages()).as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
        + " " + result.getResponse().getContentAsString()).isEmpty();
    return r;
  }

  private MockHttpServletRequestBuilder postChange(UUID id, String key, Object body) throws Exception {
    return post("/api/v1/org/units/{id}/changes", id)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private JsonNode body(ResultActions r) throws Exception {
    return json.readTree(r.andReturn().getResponse().getContentAsString());
  }

  private Map<String, Object> changeReq(String orderNo, String changeType, String effectiveOn) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("orderNo", orderNo);
    req.put("orderDate", effectiveOn);
    req.put("changeType", changeType);
    req.put("effectiveOn", effectiveOn);
    return req;
  }

  private Map<String, Object> unitInput(String code, String name, String validFrom, UUID parent) {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("code", code);
    in.put("name", name);
    in.put("level", "TINH");
    in.put("unitType", "CO_QUAN");
    in.put("subjectGroupId", group.toString());
    in.put("flows", List.of("NHA_NUOC"));
    in.put("validFrom", validFrom);
    if (parent != null) {
      in.put("parentId", parent.toString());
    }
    return in;
  }

  @Test
  void thanhLapVaDoiTenVaCanhBaoKyKhoa() throws Exception {
    Map<String, Object> createReq = changeReq("101/QĐ-UBND", "THANH_LAP", "2026-01-01");
    createReq.put("newUnits", List.of(unitInput("SKHCN", "Sở Khoa học và Công nghệ", "2026-01-01", root)));

    JsonNode created = body(asAdmin(postChange(root, UUID.randomUUID().toString(), createReq))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderNo").value("101/QĐ-UBND"))
        .andExpect(jsonPath("$.changeType").value("THANH_LAP")));
    UUID newId = UUID.fromString(created.get("createdUnitIds").get(0).asText());

    asAdmin(get("/api/v1/org/units/{id}", newId).param("asOf", "2026-01-01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SKHCN"))
        .andExpect(jsonPath("$.parentId").value(root.toString()));

    // Đổi tên trong kỳ đã khoá Quý I/2027 -> trả cảnh báo MSG-01-10.
    Map<String, Object> renameReq = changeReq("102/QĐ-UBND", "DOI_TEN", "2027-02-01");
    renameReq.put("newName", "Sở Khoa học, Công nghệ và Đổi mới sáng tạo");

    String idemKey = UUID.randomUUID().toString();
    JsonNode renamed = body(asAdmin(postChange(newId, idemKey, renameReq))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.changeType").value("DOI_TEN")));
    assertThat(renamed.get("warnings").get(0).get("msg").asText()).isEqualTo("MSG-01-10");
    assertThat(renamed.get("warnings").get(0).get("period").asText()).isEqualTo("Quý I/2027");

    asAdmin(get("/api/v1/org/units/{id}", newId).param("asOf", "2027-02-05"))
        .andExpect(jsonPath("$.name").value("Sở Khoa học, Công nghệ và Đổi mới sáng tạo"));
    asAdmin(get("/api/v1/org/units/{id}", newId).param("asOf", "2026-06-01"))
        .andExpect(jsonPath("$.name").value("Sở Khoa học và Công nghệ"));

    // Idempotency replay & mismatch & trùng số quyết định.
    asAdmin(postChange(newId, idemKey, renameReq)).andExpect(status().isCreated());
    renameReq.put("newName", "Tên khác");
    asAdmin(postChange(newId, idemKey, renameReq))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("COMMON-422-IDEMPOTENCY-MISMATCH"));
    asAdmin(postChange(newId, UUID.randomUUID().toString(), renameReq))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORG-409-ORDER-NO-EXISTS"));
  }

  @Test
  void sapNhapChuyenPhanCongVaLuuTienThan() throws Exception {
    UUID p1 = fx.person(tenant, "CB001");
    UUID p2 = fx.person(tenant, "CB002");
    fx.assignment(tenant, p1, unitB, title, "CHINH", LocalDate.of(2022, 1, 1));
    fx.assignment(tenant, p2, unitB, title, "KIEM_NHIEM", LocalDate.of(2023, 1, 1));

    // BR-01-07 / MSG-01-09: thiếu relatedUnitIds (đơn vị tiền thân) khi sáp nhập.
    Map<String, Object> invalidMerge = changeReq("200/QĐ-UBND", "SAP_NHAP", "2026-03-01");
    asAdmin(postChange(unitA, UUID.randomUUID().toString(), invalidMerge))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-PREDECESSOR-REQUIRED"))
        .andExpect(jsonPath("$.msg").value("MSG-01-09"))
        .andExpect(jsonPath("$.errors[0].rule").value("BR-01-07"));

    // Sáp nhập Sở KH&ĐT (unitB) vào Sở Tài chính (unitA) và chuyển phân công sang unitA.
    Map<String, Object> mergeReq = changeReq("201/QĐ-UBND", "SAP_NHAP", "2026-03-01");
    mergeReq.put("relatedUnitIds", List.of(unitB.toString()));
    mergeReq.put("moveAssignmentsTo", unitA.toString());

    JsonNode merged = body(asAdmin(postChange(unitA, UUID.randomUUID().toString(), mergeReq))
        .andExpect(status().isCreated()));
    assertThat(merged.get("movedAssignmentCount").asInt()).isEqualTo(2);

    // Đơn vị bị sáp nhập kết thúc hiệu lực từ 2026-03-01 nhưng vẫn đọc được ở quá khứ.
    asAdmin(get("/api/v1/org/units/{id}", unitB).param("asOf", "2026-03-01"))
        .andExpect(status().isNotFound());
    asAdmin(get("/api/v1/org/units/{id}", unitB).param("asOf", "2026-02-28"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Sở Kế hoạch và Đầu tư"));

    // Đơn vị nhận sáp nhập hiển thị tiền thân trong chi tiết và lịch sử.
    asAdmin(get("/api/v1/org/units/{id}", unitA).param("asOf", "2026-03-05"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.predecessorIds[0]").value(unitB.toString()))
        .andExpect(jsonPath("$.assignmentCount").value(2));

    asAdmin(get("/api/v1/org/units/{id}/history", unitA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].changeOrder.orderNo").value("201/QĐ-UBND"))
        .andExpect(jsonPath("$.items[0].changeType").value("SAP_NHAP"));

    // Outbox: 2 EVT-14 (unitA, unitB) + 4 EVT-15 (2 đóng cũ + 2 tạo mới).
    assertThat(fx.outboxCount(tenant, "org.unit.changed.v1")).isEqualTo(2);
    assertThat(fx.outboxCount(tenant, "org.assignment.changed.v1")).isEqualTo(4);
  }

  @Test
  void hopNhatChiaTachVaGiaiThe() throws Exception {
    // Không thể giải thể đơn vị gốc khi còn đơn vị con hiệu lực.
    Map<String, Object> dissolveRoot = changeReq("300/QĐ-UBND", "GIAI_THE", "2026-04-01");
    asAdmin(postChange(root, UUID.randomUUID().toString(), dissolveRoot))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-HAS-ACTIVE-CHILDREN"));

    // Hợp nhất unitA và unitB thành một đơn vị mới.
    Map<String, Object> consolidateReq = changeReq("301/QĐ-UBND", "HOP_NHAT", "2026-04-01");
    consolidateReq.put("relatedUnitIds", List.of(unitB.toString()));
    consolidateReq.put("newUnits", List.of(unitInput("STCKH", "Sở Tài chính - Kế hoạch", "2026-04-01", root)));

    JsonNode consolidated = body(asAdmin(postChange(unitA, UUID.randomUUID().toString(), consolidateReq))
        .andExpect(status().isCreated()));
    UUID mergedUnit = UUID.fromString(consolidated.get("createdUnitIds").get(0).asText());

    asAdmin(get("/api/v1/org/units/{id}", mergedUnit).param("asOf", "2026-04-05"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.predecessorIds.length()").value(2));

    // Chia tách mergedUnit thành 2 đơn vị mới từ 2026-07-01.
    Map<String, Object> splitReq = changeReq("302/QĐ-UBND", "CHIA_TACH", "2026-07-01");
    splitReq.put("newUnits", List.of(
        unitInput("STC2", "Sở Tài chính mới", "2026-07-01", root),
        unitInput("SKH2", "Sở Kế hoạch mới", "2026-07-01", root)));

    JsonNode split = body(asAdmin(postChange(mergedUnit, UUID.randomUUID().toString(), splitReq))
        .andExpect(status().isCreated()));
    assertThat(split.get("createdUnitIds")).hasSize(2);
    UUID splitChild1 = UUID.fromString(split.get("createdUnitIds").get(0).asText());

    // Giải thể một đơn vị lá sau chia tách từ 2026-10-01.
    Map<String, Object> dissolveLeaf = changeReq("303/QĐ-UBND", "GIAI_THE", "2026-10-01");
    asAdmin(postChange(splitChild1, UUID.randomUUID().toString(), dissolveLeaf))
        .andExpect(status().isCreated());
    asAdmin(get("/api/v1/org/units/{id}", splitChild1).param("asOf", "2026-10-01"))
        .andExpect(status().isNotFound());
  }
}
