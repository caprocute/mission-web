package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.AssignmentCommandService;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.AssignCommand;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.ReportingLineCommand;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.TransferCommand;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.application.port.ReportingLineStore;
import vn.viettel.okrkpi.org.domain.AssignmentRules.EvaluationUnitResolution;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.ReportingLineVersion;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;
import vn.viettel.okrkpi.org.support.ContractValidator;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * DV-04-04: Kiểm thử tích hợp phân công hai trục thời gian ({@code org.assignment}), quan hệ báo cáo
 * ({@code org.reporting_line}), quy tắc {@code BR-01-08}, {@code BR-01-09}, sự kiện {@code EVT-15}
 * và 3 API truy vấn với kiểm soát phạm vi đơn vị {@code Đv}.
 */
@AutoConfigureMockMvc
class OrgAssignmentApiIT extends IntegrationTestBase {

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;
  @Autowired
  TransactionTemplate tx;
  @Autowired
  AssignmentCommandService assignmentCommands;
  @Autowired
  ReportingLineStore reportingLineStore;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID adminId;
  private UUID managerId;
  private UUID group;
  private UUID unitA;
  private UUID unitA1;
  private UUID unitB;
  private UUID titleId;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    adminId = UUID.randomUUID();
    managerId = UUID.randomUUID();
    group = fx.subjectGroup(tenant, "K3");
    unitA = UUID.randomUUID();
    unitA1 = UUID.randomUUID();
    unitB = UUID.randomUUID();
    LocalDate base = LocalDate.of(2020, 1, 1);
    fx.unit(tenant, unitA, "CUC-A", "Cục Công nghệ thông tin", "CO_QUAN", group, base, null);
    fx.unit(tenant, unitA1, "PHONG-A1", "Phòng Phần mềm", "PHONG_BAN", group, base, null);
    fx.unit(tenant, unitB, "CUC-B", "Cục Pháp chế", "CO_QUAN", group, base, null);
    fx.relation(tenant, "CHUYEN_MON", unitA, unitA1, base, null);
    titleId = fx.jobTitle(tenant);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  private Actor adminActor() {
    return new Actor(tenant, adminId, Set.of("tenant_admin"), "aal2", Instant.now(), unitA, null);
  }

  private <T> T inTenantTx(Supplier<T> action) {
    TenantContextHolder.set(tenant);
    try {
      return tx.execute(status -> action.get());
    } finally {
      TenantContextHolder.clear();
    }
  }

  private ResultActions call(MockHttpServletRequestBuilder req, UUID subject, List<String> roles, String acr,
      UUID unitId) throws Exception {
    String token = "Bearer " + TestJwt.token(tenant, subject, roles, acr, unitId);
    ResultActions r = mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token));
    MvcResult result = r.andReturn();
    ValidationReport report = ContractValidator.validate(result);
    assertThat(report.getMessages()).as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
        + " " + result.getResponse().getContentAsString()).isEmpty();
    return r;
  }

  @Test
  void br0108_preventsDuplicatePrimaryAndAllowsConcurrentAndPartyAssignments() {
    UUID person = fx.person(tenant, "Nguyễn Văn An");

    UUID primaryCqId = inTenantTx(() -> assignmentCommands.recordAssignment(
        adminActor(),
        new AssignCommand(
            person,
            unitA,
            null,
            titleId,
            "CHINH",
            "CHINH_QUYEN",
            true,
            LocalDate.of(2026, 1, 1),
            null,
            false,
            "10/QĐ-BNV",
            LocalDate.of(2025, 12, 25),
            "VB-10",
            null,
            null)));
    assertThat(primaryCqId).isNotNull();

    // Tạo thêm phân công CHINH trùng hiệu lực trên cùng nhánh CHINH_QUYEN (autoClose = false) -> từ chối bởi BR-01-08
    assertThatThrownBy(() -> inTenantTx(() -> assignmentCommands.recordAssignment(
        adminActor(),
        new AssignCommand(
            person,
            unitB,
            null,
            titleId,
            "CHINH",
            "CHINH_QUYEN",
            false,
            LocalDate.of(2026, 3, 1),
            null,
            false,
            null,
            null,
            null,
            null,
            null))))
        .isInstanceOf(OrgRuleViolation.class)
        .satisfies(ex -> {
          OrgRuleViolation v = (OrgRuleViolation) ex;
          assertThat(v.code()).isEqualTo("ORG-422-PRIMARY-OVERLAP");
          assertThat(v.rule()).isEqualTo("BR-01-08");
        });

    // Ràng buộc loại trừ ex_assignment__one_primary dưới CSDL cũng chặn nếu cố chèn trực tiếp
    assertThatThrownBy(() -> fx.assignment(tenant, person, unitB, titleId, "CHINH", LocalDate.of(2026, 4, 1)))
        .isInstanceOf(DataIntegrityViolationException.class);

    // Cho phép đồng thời CHINH trên nhánh DANG và nhiều KIEM_NHIEM / BIET_PHAI
    UUID primaryPartyId = inTenantTx(() -> assignmentCommands.recordAssignment(
        adminActor(),
        new AssignCommand(
            person,
            unitA,
            null,
            titleId,
            "CHINH",
            "DANG",
            true,
            LocalDate.of(2026, 1, 1),
            null,
            false,
            "11/QĐ-ĐU",
            LocalDate.of(2025, 12, 26),
            null,
            null,
            null)));
    UUID concurrentId = inTenantTx(() -> assignmentCommands.recordAssignment(
        adminActor(),
        new AssignCommand(
            person,
            unitB,
            null,
            titleId,
            "KIEM_NHIEM",
            "CHINH_QUYEN",
            false,
            LocalDate.of(2026, 2, 1),
            null,
            false,
            "12/QĐ-BNV",
            LocalDate.of(2026, 1, 28),
            null,
            null,
            null)));

    assertThat(primaryPartyId).isNotNull();
    assertThat(concurrentId).isNotNull();
    assertThat(fx.outboxCount(tenant, "org.assignment.changed.v1")).isEqualTo(3);
    // bổ sung QA-03: EVT-15 mang tên chức danh để bản sao hiện chức danh
    assertThat(fx.outboxPayloads(tenant, "org.assignment.changed.v1"))
        .allMatch(p -> p.contains("jobTitleName") && p.contains("Chuyên viên"));

    // Kết thúc phân công kiêm nhiệm từ 01/08/2026
    inTenantTx(() -> {
      assignmentCommands.endAssignment(adminActor(), concurrentId, LocalDate.of(2026, 8, 1), null);
      return null;
    });
    assertThat(fx.outboxCount(tenant, "org.assignment.changed.v1")).isEqualTo(4);
  }

  @Test
  void futureTransfer_closesPreviousPrimaryAndResolvesEvaluationUnitUnderBr0109() throws Exception {
    UUID person = fx.person(tenant, "Trần Thị Bình");
    UUID superior = fx.person(tenant, "Lê Văn Thủ Trưởng");

    inTenantTx(() -> assignmentCommands.recordAssignment(
        adminActor(),
        new AssignCommand(
            person,
            unitA,
            null,
            titleId,
            "CHINH",
            "CHINH_QUYEN",
            false,
            LocalDate.of(2026, 1, 1),
            null,
            false,
            "01/QĐ-TC",
            LocalDate.of(2025, 12, 20),
            null,
            superior,
            "TRUC_TIEP")));

    // Điều động tương lai từ 02/05/2026 sang unitB -> tự động đóng phân công CHINH tại unitA ở ngày 02/05/2026
    LocalDate transferDate = LocalDate.of(2026, 5, 2);
    inTenantTx(() -> assignmentCommands.transferPrimaryAssignment(
        adminActor(),
        new TransferCommand(
            person,
            unitB,
            null,
            titleId,
            "CHINH_QUYEN",
            true,
            transferDate,
            null,
            "88/QĐ-ĐĐ",
            LocalDate.of(2026, 4, 25),
            "VB-88",
            null,
            null)));

    // Kiểm tra tại asOf = 2026-05-01 (trước ngày điều động): đơn vị công tác là unitA, validTo = 2026-05-02
    call(get("/api/v1/org/persons/{id}/assignments", person).param("asOf", "2026-05-01"),
        adminId, List.of("tenant_admin"), "aal2", unitA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].orgUnitId").value(unitA.toString()))
        .andExpect(jsonPath("$.items[0].validFrom").value("2026-01-01"))
        .andExpect(jsonPath("$.items[0].validTo").value("2026-05-02"));

    // Kiểm tra tại asOf = 2026-05-02 (từ ngày điều động): đơn vị công tác là unitB
    call(get("/api/v1/org/persons/{id}/assignments", person).param("asOf", "2026-05-02"),
        adminId, List.of("tenant_admin"), "aal2", unitA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].orgUnitId").value(unitB.toString()))
        .andExpect(jsonPath("$.items[0].validFrom").value("2026-05-02"))
        .andExpect(jsonPath("$.items[0].roles").isArray());

    // BR-01-09:
    // Kỳ 01/01/2026 .. 30/06/2026 -> tại unitB từ 02/05..30/06 = 1 tháng 29 ngày (< 2 tháng) -> unitA đánh giá
    EvaluationUnitResolution underTwoMonths = inTenantTx(() -> assignmentCommands.danhGiaTaiDonVi(
        person, "CHINH_QUYEN", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)));
    assertThat(underTwoMonths.transferredUnderTwoMonths()).isTrue();
    assertThat(underTwoMonths.evaluatingUnitId()).isEqualTo(unitA);

    // Kỳ 01/01/2026 .. 01/07/2026 -> tại unitB từ 02/05..01/07 = đúng 2 tháng (>= 2 tháng) -> unitB đánh giá
    EvaluationUnitResolution exactTwoMonths = inTenantTx(() -> assignmentCommands.danhGiaTaiDonVi(
        person, "CHINH_QUYEN", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1)));
    assertThat(exactTwoMonths.transferredUnderTwoMonths()).isFalse();
    assertThat(exactTwoMonths.evaluatingUnitId()).isEqualTo(unitB);

    // Kỳ 01/01/2026 .. 02/07/2026 -> tại unitB từ 02/05..02/07 = 2 tháng 1 ngày (>= 2 tháng) -> unitB đánh giá
    EvaluationUnitResolution overTwoMonths = inTenantTx(() -> assignmentCommands.danhGiaTaiDonVi(
        person, "CHINH_QUYEN", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 2)));
    assertThat(overTwoMonths.transferredUnderTwoMonths()).isFalse();
    assertThat(overTwoMonths.evaluatingUnitId()).isEqualTo(unitB);

    // Kiểm tra bản ghi cũ đã đóng recorded_to < infinity, không sửa cột nghiệp vụ tại chỗ
    List<Map<String, Object>> rows = fx.admin().queryForList(
        "SELECT org_unit_id, valid_from, valid_to::text AS valid_to, (recorded_to < 'infinity'::timestamptz) AS closed "
            + "FROM org.assignment WHERE tenant_id = ? AND person_id = ? ORDER BY recorded_from, valid_from",
        tenant, person);
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).get("closed")).isEqualTo(true);
  }

  @Test
  void reportingLine_bitemporalStorageAndQuery() {
    UUID sub = fx.person(tenant, "Lê Văn Cấp Dưới");
    UUID sup1 = fx.person(tenant, "Phạm Văn Cấp Trên 1");
    UUID sup2 = fx.person(tenant, "Hoàng Văn Cấp Trên 2");

    UUID line1 = inTenantTx(() -> assignmentCommands.recordReportingLine(
        adminActor(),
        new ReportingLineCommand(
            sub,
            sup1,
            "TRUC_TIEP",
            "CHINH_QUYEN",
            LocalDate.of(2026, 1, 1),
            null,
            false,
            null)));
    assertThat(line1).isNotNull();

    // Đổi người quản lý trực tiếp từ 01/06/2026 (autoClosePrevious = true)
    UUID line2 = inTenantTx(() -> assignmentCommands.recordReportingLine(
        adminActor(),
        new ReportingLineCommand(
            sub,
            sup2,
            "TRUC_TIEP",
            "CHINH_QUYEN",
            LocalDate.of(2026, 6, 1),
            null,
            true,
            null)));
    assertThat(line2).isNotNull();

    List<ReportingLineVersion> atApril = inTenantTx(() -> reportingLineStore.findBySubordinateAt(
        sub, new TemporalQuery(LocalDate.of(2026, 4, 15), null)));
    assertThat(atApril).hasSize(1);
    assertThat(atApril.get(0).superiorPersonId()).isEqualTo(sup1);

    List<ReportingLineVersion> atJuly = inTenantTx(() -> reportingLineStore.findBySubordinateAt(
        sub, new TemporalQuery(LocalDate.of(2026, 7, 15), null)));
    assertThat(atJuly).hasSize(1);
    assertThat(atJuly.get(0).superiorPersonId()).isEqualTo(sup2);

    // Đóng đường báo cáo từ 01/10/2026
    inTenantTx(() -> {
      assignmentCommands.endReportingLine(adminActor(), line2, LocalDate.of(2026, 10, 1), null);
      return null;
    });
    List<ReportingLineVersion> atNov = inTenantTx(() -> reportingLineStore.findBySubordinateAt(
        sub, new TemporalQuery(LocalDate.of(2026, 11, 1), null)));
    assertThat(atNov).isEmpty();
  }

  @Test
  void queryApis_listPersonsAndUnitAssignments_enforceUnitScopeAndCursorPagination() throws Exception {
    UUID p1 = fx.person(tenant, "Nguyễn Cục Trưởng A");
    UUID p2 = fx.person(tenant, "Trần Chuyên Viên A1");
    UUID p3 = fx.person(tenant, "Lê Chuyên Viên B");

    LocalDate today = LocalDate.now();
    fx.assignment(tenant, p1, unitA, titleId, "CHINH", today.minusMonths(2));
    fx.assignment(tenant, p2, unitA1, titleId, "CHINH", today.minusMonths(2));
    fx.assignment(tenant, p3, unitB, titleId, "CHINH", today.minusMonths(2));

    // 1. manager của unitA gọi GET /api/v1/org/persons?scope=unit -> chỉ thấy p1
    call(get("/api/v1/org/persons").param("scope", "unit"),
        managerId, List.of("manager"), "aal1", unitA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(p1.toString()));

    // 2. manager của unitA gọi GET /api/v1/org/persons?scope=subtree&limit=1 -> phân trang con trỏ qua p1, p2 (không có p3)
    ResultActions page1Res = call(get("/api/v1/org/persons").param("scope", "subtree").param("limit", "1"),
        managerId, List.of("manager"), "aal1", unitA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    JsonNode page1Json = json.readTree(page1Res.andReturn().getResponse().getContentAsString());
    String cursor = page1Json.get("nextCursor").asText();

    call(get("/api/v1/org/persons").param("scope", "subtree").param("limit", "1").param("cursor", cursor),
        managerId, List.of("manager"), "aal1", unitA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    // 3. manager của unitA gọi GET /api/v1/org/units/{id}/assignments cho unitA1 (thuộc cây con của unitA) -> 200 OK
    call(get("/api/v1/org/units/{id}/assignments", unitA1),
        managerId, List.of("manager"), "aal1", unitA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].personId").value(p2.toString()));

    // 4. manager của unitA gọi GET /api/v1/org/units/{id}/assignments cho unitB (ngoài cây con) -> 403 Forbidden
    call(get("/api/v1/org/units/{id}/assignments", unitB),
        managerId, List.of("manager"), "aal1", unitA)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON-403-OUT-OF-SCOPE"));

    // 5. manager của unitA gọi GET /api/v1/org/persons/{id}/assignments cho p3 (thuộc unitB) -> 403 Forbidden
    call(get("/api/v1/org/persons/{id}/assignments", p3),
        managerId, List.of("manager"), "aal1", unitA)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON-403-OUT-OF-SCOPE"));

    // 6. tenant_admin xem được cả unitB và p3
    call(get("/api/v1/org/units/{id}/assignments", unitB),
        adminId, List.of("tenant_admin"), "aal2", unitA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].personId").value(p3.toString()));
  }
}
