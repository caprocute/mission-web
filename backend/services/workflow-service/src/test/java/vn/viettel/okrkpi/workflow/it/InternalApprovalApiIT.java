package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;

/**
 * Cầu nối khởi tạo và kết thúc luồng duyệt cho lõi (CORE-02-08) trên PostgreSQL thật: khởi tạo
 * {@code duyet-ke-hoach-quy} lặp lại trả cùng tiến trình, kết thúc tiến trình còn chờ người duyệt
 * và gọi lặp không lỗi, mẫu lạ 422, thiếu vai trò dịch vụ 403.
 */
class InternalApprovalApiIT extends WorkflowIntegrationTest {

  static final String BASE = "/internal/v1/workflow/approvals";

  @Autowired private ObjectMapper json;

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();
  private UUID tenant;
  private UUID banA;

  @BeforeEach
  void tree() {
    tenant = UUID.randomUUID();
    UUID dangUy = UUID.randomUUID();
    UUID chiBo = UUID.randomUUID();
    UUID coQuan = UUID.randomUUID();
    banA = UUID.randomUUID();
    unit(dangUy, "TO_CHUC_DANG", null, null);
    unit(chiBo, "CHI_BO", dangUy, null);
    unit(coQuan, "CO_QUAN", null, null);
    unit(banA, "PHONG_BAN", chiBo, coQuan);
  }

  private void unit(UUID id, String type, UUID partyParent, UUID adminParent) {
    owner.update(
        "INSERT INTO org_replica.org_unit_v (tenant_id, id, valid_from, code, name, unit_type,"
            + " party_parent_id, admin_parent_id, source_version, source_event_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
        tenant,
        id,
        Date.valueOf(LocalDate.of(2020, 1, 1)),
        "DV-" + id.toString().substring(0, 8),
        "Đơn vị kiểm thử",
        type,
        partyParent,
        adminParent,
        UUID.randomUUID());
  }

  private MockHttpServletRequestBuilder svc(String path, Object body, String role)
      throws Exception {
    return post(path)
        .with(
            jwt()
                .jwt(
                    j ->
                        j.subject(UUID.randomUUID().toString())
                            .claim("azp", "mission-core")
                            .claim("preferred_username", "service-account-mission-core"))
                .authorities(new SimpleGrantedAuthority(role)))
        .header("X-Tenant-Id", tenant.toString())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(body));
  }

  private Map<String, Object> start(UUID plan) {
    Map<String, Object> b = new HashMap<>();
    b.put("template", "duyet-ke-hoach-quy");
    b.put("businessId", plan.toString());
    b.put("objectVersion", 2);
    b.put("flow", "DANG");
    b.put("subjectUnitId", banA.toString());
    b.put("headOfUnit", false);
    b.put("initiatorId", UUID.randomUUID().toString());
    b.put("periodId", UUID.randomUUID().toString());
    b.put("title", "Duyệt kế hoạch Quý IV/2026");
    b.put("planVersionNo", 1);
    return b;
  }

  private JsonNode ok(MockHttpServletRequestBuilder r) throws Exception {
    return json.readTree(
        mvc.perform(r)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void khoiTaoLapLaiCungTienTrinhVaKetThucKhiLoiDaQuyetDinh() throws Exception {
    UUID plan = UUID.randomUUID();
    JsonNode first = ok(svc(BASE, start(plan), "ROLE_service_client"));
    assertThat(first.get("created").asBoolean()).isTrue();
    String pid = first.get("processInstanceId").asText();
    JsonNode again = ok(svc(BASE, start(plan), "ROLE_service_client"));
    assertThat(again.get("created").asBoolean()).isFalse();
    assertThat(again.get("processInstanceId").asText()).isEqualTo(pid);
    assertThat(tasks.createTaskQuery().processInstanceId(pid).count()).isPositive();

    Map<String, Object> cancel =
        Map.of(
            "template", "duyet-ke-hoach-quy", "businessId", plan.toString(), "reason", "Trả lại");
    assertThat(ok(svc(BASE + "/cancel", cancel, "ROLE_service_client")).get("cancelled").asInt())
        .isEqualTo(1);
    assertThat(runtime.createProcessInstanceQuery().processInstanceId(pid).count()).isZero();
    assertThat(ok(svc(BASE + "/cancel", cancel, "ROLE_service_client")).get("cancelled").asInt())
        .isZero();
  }

  @Test
  void mauLaVaThieuVaiTroDichVu() throws Exception {
    Map<String, Object> bad = start(UUID.randomUUID());
    bad.put("template", "khong-co");
    bad.put("flow", "KHAC");
    mvc.perform(svc(BASE, bad, "ROLE_service_client"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("COMMON-422-VALIDATION"));
    Map<String, Object> noPlanNo = start(UUID.randomUUID());
    noPlanNo.remove("planVersionNo");
    mvc.perform(svc(BASE, noPlanNo, "ROLE_service_client"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(svc(BASE, start(UUID.randomUUID()), "ROLE_leader"))
        .andExpect(status().isForbidden());
  }
}
