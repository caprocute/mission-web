package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import vn.viettel.okrkpi.org.application.port.AccountDirectoryPort;
import vn.viettel.okrkpi.org.application.port.AccountDirectoryPort.DirectoryAccount;
import vn.viettel.okrkpi.org.application.port.AccountDirectoryPort.NewAccount;
import vn.viettel.okrkpi.org.support.ContractValidator;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * DV-04-09 · SCR-0108, SCR-0109 (BS-04, BR-01-19 đến BR-01-22): quản trị tài khoản và phân công trên PostgreSQL thật,
 * đối chiếu hợp đồng {@code org.yaml}. Keycloak được thay bằng mock của {@link AccountDirectoryPort}.
 */
@AutoConfigureMockMvc
class UserAccountApiIT extends IntegrationTestBase {

  private static final List<String> ADMIN = List.of("member", "tenant_admin");

  @Autowired
  MockMvc mvc;
  @MockitoBean
  AccountDirectoryPort directory;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID admin;
  private UUID unit;
  private UUID title;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K6");
    unit = UUID.randomUUID();
    fx.unit(tenant, unit, "SO-A", "Sở Nội vụ", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    title = fx.jobTitle(tenant);
    admin = fx.person(tenant, "Quản Trị Viên");
    fx.assignment(tenant, admin, unit, title, "CHINH", LocalDate.of(2025, 1, 1));
    fx.admin().update("INSERT INTO org.user_account (tenant_id, person_id, username, source, created_by, updated_by) "
        + "VALUES (?, ?, 'qt.admin', 'QUAN_TRI', ?, ?)", tenant, admin, admin, admin);
  }

  @Test
  void taoTaiKhoanGhiHoSoPhanCongVaTraMatKhauTamMotLan() throws Exception {
    UUID kc = UUID.randomUUID();
    when(directory.create(any())).thenReturn(kc);
    when(directory.find(kc)).thenReturn(Optional.of(new DirectoryAccount(kc, "nv.an", "an@vidu.vn", true, 0)));
    call(post("/api/v1/org/users").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"fullName\":\"Nguyễn Văn An\",\"username\":\"nv.an\",\"email\":\"an@vidu.vn\",\"orgUnitId\":\""
            + unit + "\",\"jobTitleId\":\"" + title + "\",\"gender\":\"NAM\"}"), ADMIN, "aal2")
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.id").value(kc.toString()))
        .andExpect(jsonPath("$.status").value("HOAT_DONG"))
        .andExpect(jsonPath("$.primaryUnitName").value("Sở Nội vụ"))
        .andExpect(jsonPath("$.jobTitleName").value("Chuyên viên"))
        .andExpect(jsonPath("$.source").value("QUAN_TRI"))
        .andExpect(jsonPath("$.temporaryPassword").isString());
    ArgumentCaptor<NewAccount> sent = ArgumentCaptor.forClass(NewAccount.class);
    verify(directory).create(sent.capture());
    assertThat(sent.getValue().tenantId()).isEqualTo(tenant);
    assertThat(sent.getValue().unitId()).isEqualTo(unit);
    assertThat(sent.getValue().temporaryPassword()).hasSize(16);
    assertThat(fx.admin().queryForObject("SELECT gender FROM org.person WHERE id = ?", String.class, kc))
        .isEqualTo("NAM");
    assertThat(fx.admin().queryForObject("SELECT count(*) FROM org.assignment WHERE person_id = ? AND kind = 'CHINH'",
        Integer.class, kc)).isEqualTo(1);
    assertThat(fx.outboxCount(tenant, "org.person.changed.v1")).isEqualTo(1);
  }

  @Test
  void trungTenDangNhapTra409VaKhongGhiHoSo() throws Exception {
    when(directory.create(any())).thenThrow(new AccountDirectoryPort.UsernameTakenException());
    call(post("/api/v1/org/users").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"fullName\":\"Trần Thị Bình\",\"username\":\"qt.admin\",\"orgUnitId\":\"" + unit
            + "\",\"jobTitleId\":\"" + title + "\"}"), ADMIN, "aal2")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORG-409-USERNAME-TAKEN"));
    assertThat(fx.admin().queryForObject("SELECT count(*) FROM org.person WHERE tenant_id = ?", Integer.class,
        tenant)).isEqualTo(1);
  }

  @Test
  void tenDangNhapTienToTaiKhoanDichVuBiTuChoi() throws Exception {
    call(post("/api/v1/org/users").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"fullName\":\"Trần Thị Bình\",\"username\":\"service-account-mission-web\",\"orgUnitId\":\""
            + unit + "\",\"jobTitleId\":\"" + title + "\"}"), ADMIN, "aal2")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-USERNAME"));
    verify(directory, never()).create(any());
  }

  @Test
  void taoTaiKhoanCanBuocLenVaVaiTroQuanTri() throws Exception {
    String body = "{\"fullName\":\"Lê Văn Cường\",\"username\":\"lv.cuong\",\"orgUnitId\":\"" + unit
        + "\",\"jobTitleId\":\"" + title + "\"}";
    call(post("/api/v1/org/users").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(body), ADMIN, "aal1")
        .andExpect(status().isUnauthorized());
    call(post("/api/v1/org/users").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(body), List.of("member", "leader"), "aal2")
        .andExpect(status().isForbidden());
    verify(directory, never()).create(any());
  }

  @Test
  void danhSachGhiBuTaiKhoanNapSanVaDemTheoTrangThai() throws Exception {
    UUID seeded = fx.person(tenant, "Phạm Thị Dung");
    fx.assignment(tenant, seeded, unit, title, "CHINH", LocalDate.of(2025, 1, 1));
    UUID noLogin = fx.person(tenant, "Hoàng Văn Em");
    fx.assignment(tenant, noLogin, unit, title, "CHINH", LocalDate.of(2025, 1, 1));
    when(directory.find(seeded)).thenReturn(Optional.of(new DirectoryAccount(seeded, "pt.dung", null, false, 0)));
    when(directory.find(noLogin)).thenReturn(Optional.empty());
    call(get("/api/v1/org/users").param("unit", unit.toString()), ADMIN, "aal1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.counts.total").value(2))
        .andExpect(jsonPath("$.counts.locked").value(1))
        .andExpect(jsonPath("$.items[0].username").value("pt.dung"))
        .andExpect(jsonPath("$.items[0].source").value("NAP_SAN"))
        .andExpect(jsonPath("$.items[0].status").value("KHOA"));
    call(get("/api/v1/org/users").param("q", "dung").param("status", "KHOA"), ADMIN, "aal1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void khoaMoKhoaThuHoiPhienVaKiemPhienBan() throws Exception {
    UUID target = fx.person(tenant, "Vũ Thị Giang");
    fx.assignment(tenant, target, unit, title, "CHINH", LocalDate.of(2025, 1, 1));
    fx.admin().update("INSERT INTO org.user_account (tenant_id, person_id, username, source, created_by, updated_by) "
        + "VALUES (?, ?, 'vt.giang', 'QUAN_TRI', ?, ?)", tenant, target, admin, admin);
    String reason = "{\"reason\":\"Nghỉ phép dài hạn\"}";
    call(post("/api/v1/org/users/" + target + "/lock").header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON).content(reason), ADMIN, "aal2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("KHOA"))
        .andExpect(jsonPath("$.lockReason").value("Nghỉ phép dài hạn"));
    verify(directory).setEnabled(target, false);
    call(post("/api/v1/org/users/" + target + "/unlock").header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON).content(reason), ADMIN, "aal2")
        .andExpect(status().isConflict());
    call(post("/api/v1/org/users/" + admin + "/lock").header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON).content(reason), ADMIN, "aal2")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORG-422-SELF-LOCK"));
  }

  @Test
  void datLaiMatKhauVaSuaHoTen() throws Exception {
    UUID target = fx.person(tenant, "Đỗ Văn Hải");
    fx.assignment(tenant, target, unit, title, "CHINH", LocalDate.of(2025, 1, 1));
    fx.admin().update("INSERT INTO org.user_account (tenant_id, person_id, username, source, created_by, updated_by) "
        + "VALUES (?, ?, 'dv.hai', 'QUAN_TRI', ?, ?)", tenant, target, admin, admin);
    call(post("/api/v1/org/users/" + target + "/reset-password").header("Idempotency-Key",
        UUID.randomUUID().toString()), ADMIN, "aal2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.temporaryPassword").isString());
    verify(directory).resetPassword(eq(target), anyString());
    call(patch("/api/v1/org/users/" + target).header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, "\"1\"").contentType(MediaType.APPLICATION_JSON)
        .content("{\"fullName\":\"Đỗ Văn Hải Nam\"}"), ADMIN, "aal1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fullName").value("Đỗ Văn Hải Nam"))
        .andExpect(jsonPath("$.rowVersion").value(2));
    verify(directory).updateProfile(target, "Đỗ Văn Hải Nam", null);
  }

  @Test
  void themKiemNhiemRoiKetThucPhanCong() throws Exception {
    UUID other = UUID.randomUUID();
    fx.unit(tenant, other, "SO-B", "Sở Tài chính", "CO_QUAN", fx.subjectGroup(tenant, "K7"), LocalDate.of(2020, 1, 1),
        null);
    MvcResult created = call(post("/api/v1/org/assignments").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"personId\":\"" + admin + "\",\"orgUnitId\":\"" + other + "\",\"jobTitleId\":\"" + title
            + "\",\"assignmentKind\":\"KIEM_NHIEM\",\"validFrom\":\"2026-01-01\"}"), ADMIN, "aal2")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.kind").value("KIEM_NHIEM"))
        .andReturn();
    String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    String etag = created.getResponse().getHeader(HttpHeaders.ETAG);
    call(post("/api/v1/org/assignments/" + id + "/end").header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, etag).contentType(MediaType.APPLICATION_JSON)
        .content("{\"validTo\":\"2026-06-30\",\"reason\":\"Thôi kiêm nhiệm theo quyết định\"}"), ADMIN, "aal2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.validTo").value("2026-06-30"));
    assertThat(fx.outboxCount(tenant, "org.assignment.changed.v1")).isEqualTo(2);
  }

  private ResultActions call(MockHttpServletRequestBuilder req, List<String> roles, String acr) throws Exception {
    String token = "Bearer " + TestJwt.token(tenant, admin, roles, acr, null);
    ResultActions r = mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token));
    MvcResult result = r.andReturn();
    ValidationReport report = ContractValidator.validate(result);
    assertThat(report.getMessages()).as(result.getResponse().getContentAsString()).isEmpty();
    return r;
  }
}
