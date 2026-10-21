package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
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
 * DV-04-02: đọc đơn vị theo {@code asOf}/{@code recordedAsOf}, cây, cây con, tìm kiếm, lịch sử, sửa đơn vị với
 * If-Match và Idempotency-Key, chặn vòng; mọi phản hồi được so với hợp đồng {@code org.yaml}.
 */
@AutoConfigureMockMvc
class OrgUnitApiIT extends IntegrationTestBase {

  private static final OffsetDateTime T1 = OffsetDateTime.parse("2025-01-02T08:00:00+07:00");
  private static final OffsetDateTime T2 = OffsetDateTime.parse("2025-03-10T08:00:00+07:00");

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID admin;
  private UUID group;
  private UUID root;
  private UUID childA;
  private UUID childB;
  private UUID grand;

  /**
   * Cây chuyên môn: UBND tỉnh → Sở Tài chính (đổi tên hồi tố từ 01/03/2025, ghi nhận 10/03/2025) → Phòng Ngân sách;
   * UBND tỉnh → Sở Nội vụ.
   */
  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    admin = UUID.randomUUID();
    group = fx.subjectGroup(tenant, "K3");
    root = UUID.randomUUID();
    childA = UUID.randomUUID();
    childB = UUID.randomUUID();
    grand = UUID.randomUUID();
    LocalDate base = LocalDate.of(2020, 1, 1);
    fx.unitVersion(tenant, root, "UBND", "Ủy ban nhân dân tỉnh", "CO_QUAN", group, base, null, T1, null, 0);
    // V1: Sở Tài chính từ 2020 như biết tại T1; V2, V3: đính chính tại T2 (đổi tên từ 01/03/2025).
    fx.unitVersion(tenant, childA, "STC", "Sở Tài chính", "CO_QUAN", group, base, null, T1, T2, 0);
    fx.unitVersion(tenant, childA, "STC", "Sở Tài chính", "CO_QUAN", group, base, LocalDate.of(2025, 3, 1), T2,
        null, 1);
    fx.unitVersion(tenant, childA, "STC", "Sở Tài chính và Kế hoạch", "CO_QUAN", group, LocalDate.of(2025, 3, 1),
        null, T2, null, 1);
    fx.unitVersion(tenant, childB, "SNV", "Sở Nội vụ", "CO_QUAN", group, base, null, T1, null, 0);
    fx.unitVersion(tenant, grand, "PNS", "Phòng Ngân sách", "PHONG_BAN", group, base, null, T1, null, 0);
    fx.relationVersion(tenant, UUID.randomUUID(), "CHUYEN_MON", root, childA, base, null, T1, null);
    fx.relationVersion(tenant, UUID.randomUUID(), "CHUYEN_MON", root, childB, base, null, T1, null);
    // Phòng Ngân sách chỉ được ghi nhận là cấp dưới của Sở Tài chính tại T2.
    fx.relationVersion(tenant, UUID.randomUUID(), "CHUYEN_MON", childA, grand, base, null, T2, null);
  }

  private String token(List<String> roles, String acr) {
    return "Bearer " + TestJwt.token(tenant, admin, roles, acr, childA);
  }

  private ResultActions call(MockHttpServletRequestBuilder req, List<String> roles, String acr) throws Exception {
    ResultActions r = mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token(roles, acr)));
    MvcResult result = r.andReturn();
    ValidationReport report = ContractValidator.validate(result);
    assertThat(report.getMessages()).as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
        + " " + result.getResponse().getContentAsString()).isEmpty();
    return r;
  }

  private ResultActions asAdmin(MockHttpServletRequestBuilder req) throws Exception {
    return call(req, List.of("tenant_admin"), "aal2");
  }

  private JsonNode body(ResultActions r) throws Exception {
    return json.readTree(r.andReturn().getResponse().getContentAsString());
  }

  @Test
  void asOfVaRecordedAsOfChoKetQuaKhacNhau() throws Exception {
    asAdmin(get("/api/v1/org/units/{id}", childA).param("asOf", "2025-03-05"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Sở Tài chính và Kế hoạch"))
        .andExpect(jsonPath("$.parentName").value("Ủy ban nhân dân tỉnh"))
        .andExpect(jsonPath("$.subjectGroupName").value("Khối K3"))
        .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
    asAdmin(get("/api/v1/org/units/{id}", childA).param("asOf", "2025-03-05")
        .param("recordedAsOf", "2025-03-05T12:00:00+07:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Sở Tài chính"))
        .andExpect(header().string(HttpHeaders.ETAG, "\"0\""));
    asAdmin(get("/api/v1/org/units/{id}", childA).param("asOf", "2025-02-15"))
        .andExpect(jsonPath("$.name").value("Sở Tài chính"));
    asAdmin(get("/api/v1/org/units/{id}", childA).param("asOf", "2019-12-31"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORG-404-NOT-FOUND"));
  }

  @Test
  void cayVaCayConTheoHaiTruc() throws Exception {
    JsonNode now = body(asAdmin(get("/api/v1/org/units/tree").param("tree", "CHUYEN_MON").param("depth", "3"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(false)));
    JsonNode rootNode = now.get("roots").get(0);
    assertThat(rootNode.get("name").asText()).isEqualTo("Ủy ban nhân dân tỉnh");
    assertThat(rootNode.get("children")).hasSize(2);
    JsonNode stc = rootNode.get("children").get(0);
    assertThat(stc.get("name").asText()).isEqualTo("Sở Nội vụ");
    assertThat(rootNode.get("children").get(1).get("children").get(0).get("name").asText())
        .isEqualTo("Phòng Ngân sách");

    // Như biết tại 05/03/2025 (trước T2): Phòng Ngân sách chưa thuộc Sở Tài chính nên là gốc riêng.
    JsonNode past = body(asAdmin(get("/api/v1/org/units/tree").param("tree", "CHUYEN_MON")
        .param("asOf", "2025-03-05").param("recordedAsOf", "2025-03-05T12:00:00+07:00"))
        .andExpect(jsonPath("$.readOnly").value(true)));
    assertThat(past.get("roots")).extracting(n -> n.get("name").asText())
        .containsExactly("Phòng Ngân sách", "Ủy ban nhân dân tỉnh");

    // depth = 1 (một tầng dưới gốc): nút ở tầng cuối còn cấp dưới được đánh dấu để nạp lười.
    JsonNode shallow = body(asAdmin(get("/api/v1/org/units/tree").param("tree", "CHUYEN_MON").param("depth", "1")));
    JsonNode lastLevel = shallow.get("roots").get(0).get("children").get(1);
    assertThat(lastLevel.get("name").asText()).isEqualTo("Sở Tài chính và Kế hoạch");
    assertThat(lastLevel.get("hasChildren").asBoolean()).isTrue();
    assertThat(lastLevel.has("children") ? lastLevel.get("children").size() : 0).isZero();

    JsonNode sub = body(asAdmin(get("/api/v1/org/units/{id}/subtree", childA)).andExpect(status().isOk()));
    assertThat(sub.get("roots").get(0).get("children").get(0).get("name").asText()).isEqualTo("Phòng Ngân sách");
    JsonNode subPast = body(asAdmin(get("/api/v1/org/units/{id}/subtree", childA)
        .param("recordedAsOf", "2025-03-05T12:00:00+07:00").param("asOf", "2025-03-05")));
    assertThat(subPast.get("roots").get(0).get("hasChildren").asBoolean()).isFalse();
    assertThat(subPast.get("roots").get(0).get("name").asText()).isEqualTo("Sở Tài chính");
  }

  @Test
  void timKiemBoDauVaTraToTien() throws Exception {
    JsonNode hits = body(asAdmin(get("/api/v1/org/units/search").param("q", "ngan sach"))
        .andExpect(status().isOk()));
    assertThat(hits.get("items")).hasSize(1);
    assertThat(hits.get("items").get(0).get("ancestors")).extracting(n -> n.get("name").asText())
        .containsExactly("Ủy ban nhân dân tỉnh", "Sở Tài chính và Kế hoạch");
    JsonNode byCode = body(asAdmin(get("/api/v1/org/units/search").param("q", "stc")));
    assertThat(byCode.get("items").get(0).get("code").asText()).isEqualTo("STC");
  }

  @Test
  void lichSuPhienBanMoiNhatTruoc() throws Exception {
    JsonNode page = body(asAdmin(get("/api/v1/org/units/{id}/history", childA).param("limit", "2"))
        .andExpect(status().isOk()));
    assertThat(page.get("items")).hasSize(2);
    assertThat(page.get("items").get(0).get("name").asText()).isEqualTo("Sở Tài chính và Kế hoạch");
    assertThat(page.get("items").get(0).get("changedFields")).extracting(JsonNode::asText).contains("name");
    String cursor = page.get("nextCursor").asText();
    JsonNode next = body(asAdmin(get("/api/v1/org/units/{id}/history", childA).param("limit", "2")
        .param("cursor", cursor)));
    assertThat(next.get("items")).hasSize(1);
    assertThat(next.get("items").get(0).has("recordedTo")).isTrue();
    assertThat(next.get("nextCursor").isNull()).isTrue();
  }

  private Map<String, Object> input(String code, String name, String validFrom, UUID parent) {
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

  private MockHttpServletRequestBuilder putUnit(UUID id, String ifMatch, String key, Object body) throws Exception {
    MockHttpServletRequestBuilder b = put("/api/v1/org/units/{id}", id).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
    if (ifMatch != null) {
      b.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    return key == null ? b : b.header("Idempotency-Key", key);
  }

  @Test
  void suaDonViTaoPhienBanMoiVaPhatEvt14() throws Exception {
    String key = UUID.randomUUID().toString();
    Map<String, Object> rename = input("SNV", "Sở Nội vụ tỉnh", "2026-01-01", null);
    asAdmin(putUnit(childB, "\"0\"", key, rename))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
        .andExpect(jsonPath("$.name").value("Sở Nội vụ tỉnh"))
        .andExpect(jsonPath("$.validFrom").value("2026-01-01"))
        .andExpect(jsonPath("$.parentId").value(root.toString()));
    assertThat(fx.outboxCount(tenant, "org.unit.changed.v1")).isEqualTo(1);
    Map<String, Object> evt = fx.admin().queryForMap("SELECT event_key, aggregate_version, "
        + "convert_from(payload, 'UTF8') AS payload, headers ->> 'classification' AS cls FROM org.outbox_event "
        + "WHERE tenant_id = ?", tenant);
    assertThat(evt.get("event_key")).isEqualTo(tenant + ":" + childB);
    assertThat(evt.get("cls")).isEqualTo("C1");
    assertThat(((Number) evt.get("aggregate_version")).longValue()).isEqualTo(1);
    assertThat(evt.get("payload").toString()).contains("Sở Nội vụ tỉnh");

    // Không ghi đè: phiên bản cũ đóng trục ghi nhận, khoảng trước 2026 giữ tên cũ.
    asAdmin(get("/api/v1/org/units/{id}", childB).param("asOf", "2025-12-31"))
        .andExpect(jsonPath("$.name").value("Sở Nội vụ"));
    assertThat(fx.admin().queryForObject("SELECT count(*) FROM org.org_unit WHERE id = ?", Integer.class, childB))
        .isEqualTo(3);

    // Gửi lại cùng khoá: trả kết quả cũ, không phát thêm sự kiện.
    asAdmin(putUnit(childB, "\"0\"", key, rename)).andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Sở Nội vụ tỉnh"));
    assertThat(fx.outboxCount(tenant, "org.unit.changed.v1")).isEqualTo(1);
    // Cùng khoá khác nội dung: 422.
    asAdmin(putUnit(childB, "\"0\"", key, input("SNV", "Tên khác", "2026-01-01", null)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("COMMON-422-IDEMPOTENCY-MISMATCH"));
    // If-Match cũ: 409.
    asAdmin(putUnit(childB, "\"0\"", UUID.randomUUID().toString(), input("SNV", "Tên khác", "2026-01-01", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORG-409-VERSION-CONFLICT"));
    // Thiếu If-Match: 428.
    asAdmin(putUnit(childB, null, UUID.randomUUID().toString(), rename))
        .andExpect(status().isPreconditionRequired());
  }

  @Test
  void doiCapTrenVaChanVong() throws Exception {
    // Chuyển Sở Nội vụ thành cấp dưới của Sở Tài chính từ 2026.
    asAdmin(putUnit(childB, "\"0\"", UUID.randomUUID().toString(), input("SNV", "Sở Nội vụ", "2026-01-01", childA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentId").value(childA.toString()))
        .andExpect(jsonPath("$.parentName").value("Sở Tài chính và Kế hoạch"));
    asAdmin(get("/api/v1/org/units/{id}", childB).param("asOf", "2025-06-01"))
        .andExpect(jsonPath("$.parentId").value(root.toString()));
    // Đặt UBND tỉnh làm cấp dưới của Phòng Ngân sách (cháu của nó): vòng.
    asAdmin(putUnit(root, "\"0\"", UUID.randomUUID().toString(),
        input("UBND", "Ủy ban nhân dân tỉnh", "2026-06-01", grand)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-CYCLE"))
        .andExpect(jsonPath("$.errors[0].field").value("parentId"));
    // Vòng chỉ xuất hiện từ 2026 (Sở Nội vụ dưới Sở Tài chính): đặt Sở Tài chính dưới Sở Nội vụ từ 2025 vẫn bị chặn.
    asAdmin(putUnit(childA, "\"1\"", UUID.randomUUID().toString(),
        input("STC", "Sở Tài chính và Kế hoạch", "2025-06-01", childB)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-CYCLE"));
    // Chính nó: vòng.
    asAdmin(putUnit(grand, "\"0\"", UUID.randomUUID().toString(),
        input("PNS", "Phòng Ngân sách", "2026-01-01", grand)))
        .andExpect(status().isUnprocessableEntity());
    assertThat(fx.outboxCount(tenant, "org.unit.changed.v1")).isEqualTo(1);
  }

  @Test
  void maTrungVaKhoiDoiTuongKhongHopLe() throws Exception {
    asAdmin(putUnit(childB, "\"0\"", UUID.randomUUID().toString(), input("STC", "Sở Nội vụ", "2026-01-01", null)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-CODE-TAKEN"))
        .andExpect(jsonPath("$.errors[0].field").value("code"));
    Map<String, Object> noGroup = input("SNV", "Sở Nội vụ", "2026-01-01", null);
    noGroup.put("subjectGroupId", UUID.randomUUID().toString());
    asAdmin(putUnit(childB, "\"0\"", UUID.randomUUID().toString(), noGroup))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-SUBJECT-GROUP"));
    asAdmin(putUnit(childB, "\"0\"", UUID.randomUUID().toString(), input("SNV", "Sở Nội vụ", "2019-01-01", null)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-NOT-VALID-AT-DATE"));
    asAdmin(putUnit(childB, "\"abc\"", UUID.randomUUID().toString(), input("SNV", "Sở Nội vụ", "2026-01-01", null)))
        .andExpect(status().isBadRequest());
    assertThat(fx.outboxCount(tenant, "org.unit.changed.v1")).isZero();
  }

  @Test
  void phanQuyenTheoHopDong() throws Exception {
    Map<String, Object> rename = input("SNV", "Sở Nội vụ tỉnh", "2026-01-01", null);
    call(putUnit(childB, "\"0\"", UUID.randomUUID().toString(), rename), List.of("member"), "aal2")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON-403-OUT-OF-SCOPE"));
    call(putUnit(childB, "\"0\"", UUID.randomUUID().toString(), rename), List.of("tenant_admin"), "aal1")
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
            org.hamcrest.Matchers.containsString("insufficient_user_authentication")));
    call(get("/api/v1/org/units/{id}", childA), List.of("member"), "aal1").andExpect(status().isOk());
    call(get("/api/v1/org/units/tree"), List.of("member"), "aal1").andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/org/units/{id}", childA)).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").exists());
  }

  @Test
  void tenantKhacKhongThayDonVi() throws Exception {
    UUID otherTenant = UUID.randomUUID();
    mvc.perform(get("/api/v1/org/units/{id}", childA).header(HttpHeaders.AUTHORIZATION,
            "Bearer " + TestJwt.token(otherTenant, admin, List.of("tenant_admin"), "aal2", null)))
        .andExpect(status().isNotFound());
  }

  @Test
  void danhMucVaDonViTheoPhamVi() throws Exception {
    JsonNode groups = body(asAdmin(get("/api/v1/org/subject-groups")).andExpect(status().isOk()));
    assertThat(groups.get("items")).extracting(n -> n.get("code").asText()).containsExactly("K3");
    JsonNode siblings = body(asAdmin(get("/api/v1/org/units").param("scope", "unit").param("limit", "1")));
    assertThat(siblings.get("items")).hasSize(1);
    assertThat(siblings.get("nextCursor").isNull()).isFalse();
    JsonNode rest = body(asAdmin(get("/api/v1/org/units").param("scope", "unit").param("limit", "1")
        .param("cursor", siblings.get("nextCursor").asText())));
    assertThat(rest.get("items")).hasSize(1);
    JsonNode children = body(asAdmin(get("/api/v1/org/units").param("scope", "children")));
    assertThat(children.get("items")).extracting(n -> n.get("name").asText()).containsExactly("Phòng Ngân sách");
  }

  static Timestamp ts(OffsetDateTime t) {
    return Timestamp.from(t.toInstant());
  }
}
