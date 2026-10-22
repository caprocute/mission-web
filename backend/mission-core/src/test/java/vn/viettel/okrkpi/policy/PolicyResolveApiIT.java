package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodCreatedEvent;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPolicyPackView;
import vn.viettel.okrkpi.policy.application.PolicyPeriodCalendar;
import vn.viettel.okrkpi.policy.application.PolicySchemaCatalog;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * CORE-05-03 trên PostgreSQL thật (Testcontainers): {@code resolvePolicyPack} chọn dòng gắn theo
 * đơn vị trước dòng gắn toàn tenant, không có dòng gắn thì chọn theo khoảng hiệu lực ({@code tu_ky}
 * lớn nhất rồi phiên bản lớn nhất) và ghim lại cho kỳ, không có gói trả 404 {@code
 * POLICY-404-NO-BINDING}; listener "kỳ đã tạo" ghim phiên bản cho kỳ; tham số, {@code
 * task_lifecycle} và hai cổng của MOD-08 đọc từ manifest. Kỳ của MOD-08 thay bằng bản giả trong bộ
 * nhớ.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class PolicyResolveApiIT extends ContractConformanceTest {

  static final UUID AUTHOR = UUID.randomUUID();
  static final UUID APPROVER = UUID.randomUUID();
  static final Map<PeriodId, PeriodSnapshot> PERIODS = new ConcurrentHashMap<>();

  @TestConfiguration
  static class PeriodConfig {

    @Bean
    @Primary
    PeriodApi testPeriods() {
      return new PeriodApi() {
        @Override
        public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
          return Optional.empty();
        }

        @Override
        public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId periodId) {
          return Optional.ofNullable(PERIODS.get(periodId));
        }

        @Override
        public void assertOpen(TenantId tenantId, PeriodId periodId) {}
      };
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyQueryApi policies;
  @Autowired private PolicyPeriodCalendar calendars;
  @Autowired private PolicySchemaCatalog schemas;
  @Autowired private ApplicationEventPublisher events;

  @Autowired
  @Qualifier("policyDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("policyTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Override
  protected ContractModule contractModule() {
    return ContractModule.POLICIES;
  }

  private <T> T inTenant(Function<JdbcClient, T> query) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> query.apply(jdbc)));
  }

  private PeriodId period(LocalDate start) {
    PeriodId id = PeriodId.of(Ids.newUuidV7());
    PERIODS.put(
        id,
        new PeriodSnapshot(
            id,
            tenant,
            "KY-" + start,
            "Kỳ " + start,
            start,
            start.plusMonths(3).minusDays(1),
            PeriodStatus.MO));
    return id;
  }

  private UUID pack(String code, String flow) throws Exception {
    UUID id = Ids.newUuidV7();
    inTenant(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO policy.policy_pack (id, created_by, updated_by, code, name, sector,"
                        + " flow, legal_basis) VALUES (?, ?, ?, ?, ?, 'KHOI_DANG', ?, 'QĐ 366')")
                .param(id)
                .param(AUTHOR)
                .param(AUTHOR)
                .param(code)
                .param("Gói " + code)
                .param(flow)
                .update());
    return id;
  }

  /** Phiên bản đã phát hành (hoặc trạng thái khác) với manifest tối thiểu và khối thêm. */
  private UUID version(UUID packId, String version, String status, String tuKy, String extra)
      throws Exception {
    UUID id = Ids.newUuidV7();
    String manifest =
        "{\"apiVersion\":\"policy.okrkpi.vn/v1\",\"kind\":\"PolicyPack\",\"metadata\":{\"version\":\""
            + version
            + "\",\"hieu_luc\":{\"tu_ky\":"
            + (tuKy == null ? "null" : "\"" + tuKy + "\"")
            + ",\"den_ky\":null}}"
            + extra
            + "}";
    boolean released = !status.equals("NHAP");
    inTenant(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO policy.policy_pack_version (id, created_by, updated_by,"
                        + " policy_pack_id, version, status, manifest, content_hash,"
                        + " bundle_object_key, golden_pass_rate, signature, approved_by,"
                        + " change_note) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, 'thử')")
                .param(id)
                .param(AUTHOR)
                .param(AUTHOR)
                .param(packId)
                .param(version)
                .param(status)
                .param(manifest)
                .param(released ? new byte[] {(byte) 0xab, 0x01} : null)
                .param(released ? "policy-cell/" + version + ".bundle" : null)
                .param(released ? java.math.BigDecimal.ONE : null)
                .param(released ? new byte[] {9} : null)
                .param(released ? APPROVER : null)
                .update());
    return id;
  }

  private UUID bind(UUID versionId, PeriodId period, String flow, UUID unit) throws Exception {
    UUID id = Ids.newUuidV7();
    inTenant(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO policy.policy_binding (id, created_by, updated_by,"
                        + " policy_pack_version_id, period_id, flow, org_unit_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                .param(id)
                .param(AUTHOR)
                .param(AUTHOR)
                .param(versionId)
                .param(period.value())
                .param(flow)
                .param(unit)
                .update());
    return id;
  }

  private long bindings(PeriodId period) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM policy.policy_binding WHERE period_id = ?")
                .param(period.value())
                .query(Long.class)
                .single());
  }

  private MvcResult resolve(PeriodId period, String flow, UUID unit) throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/v1/policies/resolve")
            .param("tenant", tenant.value().toString())
            .param("period", period.value().toString())
            .param("flow", flow);
    if (unit != null) {
      request.param("unit", unit.toString());
    }
    return mvc.perform(request).andReturn();
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void dongGanTheoDonViThangDongGanToanTenant() throws Exception {
    UUID packId = pack("khoi-dang/don-vi", "DANG");
    UUID chung = version(packId, "1.0.0", "DA_PHAT_HANH", "2026-Q1", "");
    UUID rieng = version(packId, "1.1.0", "DA_PHAT_HANH", "2026-Q1", "");
    PeriodId ky = period(LocalDate.of(2026, 10, 1));
    UUID unit = UUID.randomUUID();
    UUID tenantWide = bind(chung, ky, "DANG", null);
    UUID unitWide = bind(rieng, ky, "DANG", unit);

    MvcResult byUnit = resolve(ky, "DANG", unit);
    assertThat(byUnit.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(byUnit, "/policies/resolve", PathItem.HttpMethod.GET);
    JsonNode b = body(byUnit);
    assertThat(b.path("versionId").asText()).isEqualTo(rieng.toString());
    assertThat(b.path("bindingId").asText()).isEqualTo(unitWide.toString());
    assertThat(b.path("contentHash").asText()).isEqualTo("ab01");
    assertThat(b.path("bundleObjectKey").asText()).isEqualTo("policy-cell/1.1.0.bundle");
    assertThat(b.path("code").asText()).isEqualTo("khoi-dang/don-vi");
    assertThat(b.path("frozen").asBoolean()).isFalse();

    JsonNode other = body(resolve(ky, "DANG", UUID.randomUUID()));
    assertThat(other.path("bindingId").asText()).isEqualTo(tenantWide.toString());
    assertThat(other.path("versionId").asText()).isEqualTo(chung.toString());
  }

  @Test
  void khongCoDongGanThiChonTheoKhoangHieuLucVaGhimChoKy() throws Exception {
    UUID packId = pack("khoi-dang/hieu-luc", "DANG");
    UUID cu = version(packId, "1.0.0", "DA_PHAT_HANH", "2026-Q1", "");
    version(packId, "2.0.0", "DA_PHAT_HANH", "2027-Q1", "");
    UUID moiNhat = version(packId, "2.1.0", "NGUNG", "2027-Q1", "");
    version(packId, "3.0.0", "NHAP", "2027-Q1", "");
    PeriodId quyIv = period(LocalDate.of(2026, 10, 1));
    PeriodId quyIi = period(LocalDate.of(2027, 4, 1));

    MvcResult first = resolve(quyIv, "DANG", null);
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(first, "/policies/resolve", PathItem.HttpMethod.GET);
    assertThat(body(first).path("versionId").asText()).isEqualTo(cu.toString());
    assertThat(bindings(quyIv)).isEqualTo(1);

    JsonNode q2 = body(resolve(quyIi, "DANG", null));
    assertThat(q2.path("versionId").asText()).isEqualTo(moiNhat.toString());
    assertThat(q2.path("version").asText()).isEqualTo("2.1.0");
    // Gói mới phát hành sau đó không đổi phiên bản đã ghim của kỳ.
    version(packId, "2.2.0", "DA_PHAT_HANH", "2027-Q1", "");
    TenantContext.run(
        tenant,
        () ->
            ((vn.viettel.okrkpi.policy.application.DefaultPolicyQueryService) policies)
                .invalidate(tenant));
    JsonNode again = body(resolve(quyIi, "DANG", null));
    assertThat(again.path("versionId").asText()).isEqualTo(moiNhat.toString());
    assertThat(again.path("bindingId").asText()).isEqualTo(q2.path("bindingId").asText());
    assertThat(bindings(quyIi)).isEqualTo(1);
  }

  @Test
  void khongCoGoiTra404() throws Exception {
    pack("khoi-dang/chi-dang", "DANG");
    MvcResult r = resolve(period(LocalDate.of(2026, 10, 1)), "NHA_NUOC", null);
    assertThat(r.getResponse().getStatus()).isEqualTo(404);
    assertThat(body(r).path("code").asText()).isEqualTo("POLICY-404-NO-BINDING");
    assertResponseConforms(r, "/policies/resolve", PathItem.HttpMethod.GET);
  }

  @Test
  void kyMoiTaoDuocGhimPhienBanHieuLuc() throws Exception {
    UUID packId = pack("khoi-dang/ky-moi", "DANG");
    UUID v = version(packId, "1.0.0", "DA_PHAT_HANH", "2026-Q4", "");
    PeriodId ky = period(LocalDate.of(2026, 10, 1));
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(tm)
                .executeWithoutResult(
                    s ->
                        events.publishEvent(
                            new PeriodCreatedEvent(
                                ky,
                                tenant,
                                "Q4-2026",
                                "QUY",
                                "CA_HAI",
                                LocalDate.of(2026, 10, 1),
                                LocalDate.of(2026, 12, 31),
                                0,
                                Instant.now()))));
    long deadline = System.currentTimeMillis() + 15_000;
    while (bindings(ky) == 0 && System.currentTimeMillis() < deadline) {
      Thread.onSpinWait();
    }
    // Kỳ hai luồng: chỉ luồng Đảng có gói nên chỉ ghim một dòng.
    assertThat(bindings(ky)).isEqualTo(1);
    Optional<ResolvedPolicyPackView> resolved =
        TenantContext.call(tenant, () -> policies.resolve(tenant, ky, "DANG", null, null));
    assertThat(resolved).get().extracting(ResolvedPolicyPackView::versionId).isEqualTo(v);
  }

  @Test
  void thamSoVongDoiNhiemVuVaHaiCongCuaCauHinh() throws Exception {
    UUID packId = pack("khoi-dang/noi-dung", "DANG");
    String extra =
        ",\"params\":{\"tru_tien_do\":{\"type\":\"double\",\"mac_dinh\":0.05},"
            + "\"plan_approval_deadline_rule\":{\"type\":\"string\","
            + "\"mac_dinh\":\"NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2\"}},"
            + "\"task_lifecycle\":{\"transitions\":[{\"from\":\"NHAP\",\"command\":\"ASSIGN\","
            + "\"to\":\"DA_GIAO\",\"allowedRoles\":[\"manager\"],\"guardCel\":null,"
            + "\"reasonRequired\":false}]},"
            + "\"forms\":{\"mau-1\":{\"schema\":{\"properties\":{\"ty_le\":{},\"ghi_chu\":{}}}}},"
            + "\"inputs\":{\"ty_le\":{\"type\":\"double\",\"source\":\"form.ty_le\"},"
            + "\"ty_le_O\":{\"type\":\"double\",\"source\":\"okr.aggregate.objective_ratio\"}}";
    UUID v = version(packId, "1.2.0", "DA_PHAT_HANH", "2026-Q1", extra);
    UUID cu = version(packId, "1.0.0", "NGUNG", "2025-Q1", "");

    TenantContext.run(
        tenant,
        () -> {
          assertThat(policies.parameter(tenant, v, "tru_tien_do")).contains(0.05);
          assertThat(policies.taskLifecycle(tenant, v))
              .singleElement()
              .satisfies(
                  t -> {
                    assertThat(t.command()).isEqualTo("ASSIGN");
                    assertThat(t.allowedRoles()).containsExactly("manager");
                    assertThat(t.guardCel()).isNull();
                  });
          assertThat(
                  schemas.fields(
                      tenant,
                      "policy://khoi-dang/noi-dung/forms/mau-1@1.2.0",
                      "khoi-dang/noi-dung@^1.0"))
              .contains(Set.of("ty_le", "ghi_chu"));
          assertThat(schemas.fields(tenant, "policy://khoi-dang/noi-dung/forms/mau-1", ">=2.0.0"))
              .isEmpty();
          assertThat(schemas.scoredFields(tenant, v)).containsExactly(Map.entry("ty_le", "number"));
          assertThat(schemas.retired(tenant, "khoi-dang/noi-dung@1.0.0")).isTrue();
          assertThat(schemas.retired(tenant, "khoi-dang/noi-dung@^1.0")).isFalse();
          assertThat(cu).isNotNull();
          // Gói khai quy tắc hạn duyệt mặc định: MOD-08 dùng lịch mặc định của mình.
          assertThat(
                  calendars.calendarFor(
                      tenant, "QUY", "DANG", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)))
              .isEmpty();
        });
  }
}
