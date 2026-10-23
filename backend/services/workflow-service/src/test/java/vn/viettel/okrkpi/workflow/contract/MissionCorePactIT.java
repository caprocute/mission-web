package vn.viettel.okrkpi.workflow.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;

/**
 * QA-02 · bên cung cấp service với service: workflow-service (SVC-06) xác minh hợp đồng mà
 * mission-core công bố ở {@code
 * backend/mission-core/src/test/resources/pacts/mission-core-workflow-service.json}: cầu nối khởi
 * tạo, kết thúc luồng duyệt (CORE-02-08) và tham số luồng (DV-06-08) trên PostgreSQL và engine
 * Flowable thật. Token dịch vụ của mission-core là JWT giả lập vai trò {@code service_client};
 * tenant đích đi trong {@code X-Tenant-Id} như hợp đồng.
 */
class MissionCorePactIT extends WorkflowIntegrationTest {

  static final Path PACT =
      Path.of("../../mission-core/src/test/resources/pacts/mission-core-workflow-service.json");

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();

  @Test
  void missionCoreDungHopDongVoiWorkflowService() {
    Pact pact = Pact.load(PACT);
    assertThat(pact.consumer()).isEqualTo("mission-core");
    assertThat(pact.provider()).isEqualTo("workflow-service");
    assertThat(verifier().failures(pact)).isEmpty();
  }

  @Test
  void doiTenTruongMissionCoreDocThiXacMinhDo() {
    ObjectNode raw = Pact.load(PACT).copyRaw();
    ObjectNode body = (ObjectNode) raw.at("/interactions/0/response/body");
    body.set("pid", body.remove("processInstanceId"));
    assertThat(verifier().failures(Pact.of(raw)))
        .anySatisfy(e -> assertThat(e).contains("$.pid: thiếu"));
  }

  private MockMvcPactVerifier verifier() {
    return new MockMvcPactVerifier(mvc, serviceToken(), this::state);
  }

  /** Token client credentials của mission-core: không có claim tenant. */
  private static RequestPostProcessor serviceToken() {
    return jwt()
        .jwt(
            j ->
                j.subject(UUID.randomUUID().toString())
                    .claim("azp", "mission-core")
                    .claim("preferred_username", "service-account-mission-core"))
        .authorities(new SimpleGrantedAuthority("ROLE_service_client"));
  }

  private Callable<MvcResult> state(Pact.State s, Callable<MvcResult> request) throws Exception {
    UUID tenant = uuid(s, "tenantId");
    switch (s.name()) {
      case "tenant có đơn vị trình kế hoạch trong bản sao cây tổ chức" ->
          tree(tenant, uuid(s, "unitId"));
      case "kế hoạch đang chờ duyệt trong luồng" -> {
        tree(tenant, uuid(s, "unitId"));
        startPlanApproval(tenant, uuid(s, "unitId"), uuid(s, "planId"));
      }
      case "phiên bản Policy Pack chưa lưu tham số luồng" -> {
        // Tenant mới chưa có dòng wf_binding nào.
      }
      default -> throw new IllegalArgumentException("Chưa có trạng thái: " + s.name());
    }
    return request;
  }

  /** Cây Đảng và chính quyền tối thiểu cho đơn vị trình (như {@code InternalApprovalApiIT}). */
  private void tree(UUID tenant, UUID unit) {
    UUID dangUy = UUID.nameUUIDFromBytes(("dang-uy" + tenant).getBytes(StandardCharsets.UTF_8));
    UUID chiBo = UUID.nameUUIDFromBytes(("chi-bo" + tenant).getBytes(StandardCharsets.UTF_8));
    UUID coQuan = UUID.nameUUIDFromBytes(("co-quan" + tenant).getBytes(StandardCharsets.UTF_8));
    unit(tenant, dangUy, "TO_CHUC_DANG", null, null);
    unit(tenant, chiBo, "CHI_BO", dangUy, null);
    unit(tenant, coQuan, "CO_QUAN", null, null);
    unit(tenant, unit, "PHONG_BAN", chiBo, coQuan);
  }

  private void unit(UUID tenant, UUID id, String type, UUID partyParent, UUID adminParent) {
    owner.update(
        "INSERT INTO org_replica.org_unit_v (tenant_id, id, valid_from, code, name, unit_type,"
            + " party_parent_id, admin_parent_id, source_version, source_event_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?) ON CONFLICT DO NOTHING",
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

  /** Kế hoạch đã trình: một tiến trình duyệt đang chờ người duyệt. */
  private void startPlanApproval(UUID tenant, UUID unit, UUID plan) throws Exception {
    String body =
        """
        {"template":"duyet-ke-hoach-quy","businessId":"%s","objectVersion":2,"planVersionNo":1,\
        "subjectUnitId":"%s","flow":"DANG","headOfUnit":false,"title":"Kế hoạch công tác quý"}
        """
            .formatted(plan, unit);
    int status =
        mvc.perform(
                post("/internal/v1/workflow/approvals")
                    .with(serviceToken())
                    .header("X-Tenant-Id", tenant.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.getBytes(StandardCharsets.UTF_8)))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(status).isEqualTo(200);
  }

  private static UUID uuid(Pact.State s, String param) {
    return UUID.fromString(s.params().path(param).asText());
  }
}
