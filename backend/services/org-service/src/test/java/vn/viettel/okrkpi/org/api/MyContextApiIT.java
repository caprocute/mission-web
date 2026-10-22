package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.report.ValidationReport;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.org.application.port.IdentityContextPort;
import vn.viettel.okrkpi.org.application.port.IdentityContextPort.StoredContext;
import vn.viettel.okrkpi.org.support.ContractValidator;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * DV-02-02 · SCR-0001, SCR-0002: {@code getMyOrgProfile} và {@code setMyContext} trên PostgreSQL thật, đối chiếu hợp
 * đồng {@code org.yaml}. Keycloak được thay bằng mock của {@link IdentityContextPort}; phần đọc thuộc tính thành claim
 * kiểm ở {@code MissionContextMapperIT} của iam.
 */
@AutoConfigureMockMvc
class MyContextApiIT extends IntegrationTestBase {

  @Autowired
  MockMvc mvc;
  @MockitoBean
  IdentityContextPort identity;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID person;
  private UUID unit;
  private UUID primary;
  private UUID party;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K3");
    unit = UUID.randomUUID();
    fx.unit(tenant, unit, "CUC-A", "Cục Công nghệ thông tin", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    UUID title = fx.jobTitle(tenant);
    person = fx.person(tenant, "Nguyễn Văn An");
    primary = fx.assignment(tenant, person, unit, title, "CHINH", LocalDate.of(2025, 1, 1));
    party = fx.assignment(tenant, person, unit, title, "KIEM_NHIEM", LocalDate.of(2025, 1, 1));
    fx.assignment(tenant, person, unit, title, "BIET_PHAI", LocalDate.now().plusYears(1));
  }

  @Test
  void hoSoLietKePhanCongConHieuLucVaPhanCongLanTruoc() throws Exception {
    when(identity.lastAssignment(person)).thenReturn(Optional.of(party));
    call(get("/api/v1/org/me"), List.of("member", "leader", "default-roles-mission"), "aal1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.personId").value(person.toString()))
        .andExpect(jsonPath("$.fullName").value("Nguyễn Văn An"))
        .andExpect(jsonPath("$.assignments.length()").value(2))
        .andExpect(jsonPath("$.assignments[0].roles[0]").value("leader"))
        .andExpect(jsonPath("$.lastAssignmentId").value(party.toString()));
  }

  @Test
  void chonPhanCongGhiNguCanhVaSoCaiMotLanDuGoiLai() throws Exception {
    String key = UUID.randomUUID().toString();
    for (int i = 0; i < 2; i++) {
      call(put("/api/v1/org/me/context").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
          .content("{\"assignmentId\":\"" + primary + "\"}"), List.of("member"), "aal1")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.assignmentId").value(primary.toString()))
          .andExpect(jsonPath("$.orgUnitId").value(unit.toString()));
    }
    ArgumentCaptor<StoredContext> stored = ArgumentCaptor.forClass(StoredContext.class);
    verify(identity, times(1)).write(eq(person), stored.capture());
    assertThat(stored.getValue().unitId()).isEqualTo(unit);
    assertThat(stored.getValue().flows()).containsExactly("NHA_NUOC");
    assertThat(stored.getValue().roles()).containsExactly("member");
    assertThat(fx.outboxCount(tenant, "sys.audit.v1")).isEqualTo(1);
  }

  @Test
  void phanCongKhongHopLeTra404() throws Exception {
    call(put("/api/v1/org/me/context").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content("{\"assignmentId\":\"" + UUID.randomUUID() + "\"}"),
        List.of("member"), "aal1").andExpect(status().isNotFound());
    verify(identity, never()).write(any(), any());
  }

  @Test
  void vaiTroAal2TrongPhienAal1Tra401BuocLen() throws Exception {
    call(put("/api/v1/org/me/context").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content("{\"assignmentId\":\"" + primary + "\"}"),
        List.of("member", "appraiser"), "aal1").andExpect(status().isUnauthorized());
    verify(identity, never()).write(any(), any());
    assertThat(fx.outboxCount(tenant, "sys.audit.v1")).isZero();
  }

  private ResultActions call(MockHttpServletRequestBuilder req, List<String> roles, String acr) throws Exception {
    String token = "Bearer " + TestJwt.token(tenant, person, roles, acr, null);
    ResultActions r = mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token));
    MvcResult result = r.andReturn();
    ValidationReport report = ContractValidator.validate(result);
    assertThat(report.getMessages()).as(result.getResponse().getContentAsString()).isEmpty();
    return r;
  }
}
