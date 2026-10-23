package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * DV-13-10 · Phần "quá trình công tác" của gói chuyển hồ sơ liên cell: cell nguồn trả lý lịch công tác kèm tên đơn vị,
 * chức danh và số quyết định; cell đích nhập phần đã kiểm, chống trùng, không lẫn tenant, chỉ token dịch vụ của
 * integration-gateway gọi được.
 */
@AutoConfigureMockMvc
class OrgTransferApiIT extends IntegrationTestBase {

  @Autowired
  MockMvc mvc;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID person;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K1");
    UUID unitA = UUID.randomUUID();
    UUID unitB = UUID.randomUUID();
    fx.unit(tenant, unitA, "SO-A", "Sở Nội vụ", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    fx.unit(tenant, unitB, "SO-B", "Sở Tài chính", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    UUID title = fx.jobTitle(tenant);
    person = fx.person(tenant, "Nguyễn Văn An");
    UUID order = UUID.randomUUID();
    fx.admin().update("INSERT INTO org.org_change_order (id, tenant_id, order_no, order_date, kind, effective_on, "
        + "signed_doc_ref, payload, status, created_by, updated_by) VALUES (?, ?, '125/QĐ-UBND', '2024-02-20', "
        + "'DIEU_DONG', '2024-03-01', 'vb-125', '{}', 'DA_AP_DUNG', ?, ?)", order, tenant, Fixtures.SYSTEM_ACTOR,
        Fixtures.SYSTEM_ACTOR);
    UUID first = fx.assignment(tenant, person, unitA, title, "CHINH", LocalDate.of(2021, 1, 1));
    fx.admin().update("UPDATE org.assignment SET valid_to = '2024-03-01' WHERE id = ?", first);
    UUID second = fx.assignment(tenant, person, unitB, title, "DIEU_DONG", LocalDate.of(2024, 3, 1));
    fx.admin().update("UPDATE org.assignment SET change_order_id = ? WHERE id = ?", order, second);
  }

  @Test
  void collect_returnsWorkHistoryWithNames() throws Exception {
    gateway(get("/internal/v1/org/transfer-parts/" + person + "/work-history"), tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.personId").value(person.toString()))
        .andExpect(jsonPath("$.entries.length()").value(2))
        .andExpect(jsonPath("$.entries[0].unitName").value("Sở Nội vụ"))
        .andExpect(jsonPath("$.entries[0].validTo").value("2024-03-01"))
        .andExpect(jsonPath("$.entries[1].unitName").value("Sở Tài chính"))
        .andExpect(jsonPath("$.entries[1].kind").value("DIEU_DONG"))
        .andExpect(jsonPath("$.entries[1].orderNo").value("125/QĐ-UBND"))
        .andExpect(jsonPath("$.entries[1].validTo").doesNotExist());
    gateway(get("/internal/v1/org/transfer-parts/" + person + "/work-history"), UUID.randomUUID())
        .andExpect(status().isNotFound());
    call(get("/internal/v1/org/transfer-parts/" + person + "/work-history"), tenant, "tenant_admin")
        .andExpect(status().isForbidden());
  }

  @Test
  void import_isIdempotentAndRejectsInvalidParts() throws Exception {
    UUID target = UUID.randomUUID();
    UUID packageId = UUID.randomUUID();
    String body = "{\"version\":1,\"personId\":\"" + person + "\",\"entries\":[{\"unitName\":\"Sở Nội vụ\"}]}";
    importPart(target, packageId, "cong-tac.json", "QUA_TRINH_CONG_TAC", body).andExpect(status().isCreated());
    importPart(target, packageId, "cong-tac.json", "QUA_TRINH_CONG_TAC", body).andExpect(status().isOk());
    importPart(target, packageId, "cong-tac.json", "QUA_TRINH_CONG_TAC", body.replace("Nội vụ", "Tư pháp"))
        .andExpect(status().isConflict());
    importPart(target, packageId, "b.json", "KET_QUA_XEP_LOAI", body).andExpect(status().isUnprocessableEntity());
    importPart(target, packageId, "c.json", "QUA_TRINH_CONG_TAC",
        body.replace(person.toString(), UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity());
    importPart(target, packageId, "d.json", "QUA_TRINH_CONG_TAC", "không phải JSON")
        .andExpect(status().isUnprocessableEntity());
    gateway(post("/internal/v1/org/transfer-imports/" + person).param("kind", "QUA_TRINH_CONG_TAC")
        .header("Idempotency-Key", UUID.randomUUID() + ":x.json").header("X-Transfer-Package", packageId)
        .header("X-Source-Tenant", "HN-BO-A").contentType(MediaType.APPLICATION_JSON).content(body), target)
        .andExpect(status().isUnprocessableEntity());

    List<String> rows = fx.admin().queryForList("SELECT content::text FROM org.transferred_work_history "
        + "WHERE tenant_id = ? AND package_id = ?", String.class, target, packageId);
    assertThat(rows).singleElement().asString().contains("Sở Nội vụ");
    Integer elsewhere = fx.admin().queryForObject("SELECT count(*) FROM org.transferred_work_history "
        + "WHERE package_id = ? AND tenant_id <> ?", Integer.class, packageId, target);
    assertThat(elsewhere).isZero();
  }

  private ResultActions importPart(UUID target, UUID packageId, String name, String kind, String body)
      throws Exception {
    return gateway(post("/internal/v1/org/transfer-imports/" + person).param("kind", kind)
        .header("Idempotency-Key", packageId + ":" + name).header("X-Transfer-Package", packageId)
        .header("X-Source-Tenant", "HN-BO-A").contentType(MediaType.APPLICATION_JSON)
        .content(body.getBytes(StandardCharsets.UTF_8)), target);
  }

  private ResultActions gateway(MockHttpServletRequestBuilder req, UUID tenantId) throws Exception {
    return call(req, tenantId, "svc_integration_gateway");
  }

  private ResultActions call(MockHttpServletRequestBuilder req, UUID tenantId, String role) throws Exception {
    String token = "Bearer " + TestJwt.serviceToken("integration-gateway", List.of(role));
    return mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token).header("X-Tenant-Id", tenantId));
  }
}
