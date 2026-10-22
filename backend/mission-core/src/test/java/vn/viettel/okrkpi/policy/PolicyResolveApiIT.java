package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import vn.viettel.okrkpi.config.application.NewPeriod;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.application.PeriodView;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.application.PolicyResolutionService;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-03 trên PostgreSQL thật: gói ND 335 đã phát hành tính hạn duyệt kế
 * hoạch khi tạo kỳ quý (ngày làm việc cuối tuần lịch thứ hai), kỳ mới được ghim gói qua sự kiện "kỳ
 * đã tạo", {@code GET /policies/resolve} khớp {@code policies.yaml}, gắn theo đơn vị thắng gắn toàn
 * tenant, kỳ không có gói trả 404 {@code POLICY-404-NO-BINDING}, mất gắn thì ghim lại.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class PolicyResolveApiIT extends ContractConformanceTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyPackService packs;
  @Autowired private PolicyResolutionService resolution;
  @Autowired private PolicyQueryApi query;
  @Autowired private PeriodConfigService periods;

  @Autowired
  @Qualifier("policyDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("policyTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.POLICIES;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private <T> T inTenant(Function<JdbcClient, T> sql) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> sql.apply(jdbc)));
  }

  private MockHttpServletRequestBuilder resolve(UUID period, String flow) {
    return get("/api/v1/policies/resolve")
        .param("tenant", tenant.value().toString())
        .param("period", period.toString())
        .param("flow", flow);
  }

  /**
   * Nhập gói ND 335 mẫu rồi phát hành thẳng ở CSDL (ký và kiểm thử ca vàng thuộc CORE-05-06..08).
   */
  private VersionView publishedNd335() throws Exception {
    Map<String, Object> manifest =
        PolicyPackFixtures.yaml(
            PolicyPackFixtures.pack(PolicyPackFixtures.GOI_NHA_NUOC).resolve("policy.yaml"));
    VersionView root =
        TenantContext.call(
            tenant,
            () ->
                packs.importPack(
                    tenant,
                    author,
                    new PackImport(
                        manifest, PackOrigin.GOC_TOAN_CUC, "KHOI_CHINH_QUYEN", null, null)));
    publish(root.version().id());
    return root;
  }

  private void publish(UUID versionId) throws Exception {
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE policy.policy_pack_version SET status = 'DA_PHAT_HANH',"
                        + " golden_pass_rate = 1, content_hash = decode('a1b2', 'hex'),"
                        + " signature = decode('01', 'hex'), approved_by = ?, approved_at = now(),"
                        + " published_at = now(), bundle_object_key = 'policy/' || id::text"
                        + " WHERE tenant_id = ? AND id = ?")
                .params(UUID.randomUUID(), tenant.value(), versionId)
                .update());
  }

  private long bindings(UUID period) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql(
                    "SELECT count(*) FROM policy.policy_binding WHERE tenant_id = ? AND period_id = ?")
                .params(tenant.value(), period)
                .query(Long.class)
                .single());
  }

  private long awaitBindings(UUID period) throws Exception {
    long deadline = System.nanoTime() + 15_000_000_000L;
    long n = bindings(period);
    while (n == 0 && System.nanoTime() < deadline) {
      Thread.sleep(100);
      n = bindings(period);
    }
    return n;
  }

  @Test
  void createdPeriodIsPinnedAndResolvesByMostSpecificBinding() throws Exception {
    VersionView root = publishedNd335();
    UUID rootVersion = root.version().id();
    PeriodView q1 =
        TenantContext.call(
            tenant,
            () ->
                periods.create(
                    tenant,
                    author,
                    new NewPeriod(
                        "Q1-2027-NN",
                        "Quý I năm 2027",
                        PeriodKind.QUY,
                        PeriodFlow.NHA_NUOC,
                        null,
                        LocalDate.of(2027, 1, 1),
                        LocalDate.of(2027, 3, 31),
                        null,
                        null,
                        null)));
    assertThat(q1.period().schedule().planDeadline()).isEqualTo(LocalDate.of(2027, 1, 8));
    UUID period = q1.period().id().value();
    assertThat(awaitBindings(period)).isEqualTo(1);

    MvcResult ok = perform(resolve(period, "NHA_NUOC"));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(ok, "/policies/resolve", PathItem.HttpMethod.GET);
    JsonNode found = body(ok);
    assertThat(found.get("versionId").asText()).isEqualTo(rootVersion.toString());
    assertThat(found.get("version").asText()).isEqualTo("1.4.0");
    assertThat(found.get("bundleObjectKey").asText()).isEqualTo("policy/" + rootVersion);
    assertThat(found.get("frozen").asBoolean()).isFalse();

    VersionView unitVersion =
        TenantContext.call(
            tenant,
            () ->
                packs.newVersion(
                    tenant, author, root.pack().id(), "1.4.0", "1.5.0", "Phiên bản riêng đơn vị"));
    publish(unitVersion.version().id());
    UUID unit = UUID.randomUUID();
    inTenant(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO policy.policy_binding (id, tenant_id, created_by, updated_by,"
                        + " policy_pack_version_id, period_id, flow, org_unit_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'NHA_NUOC', ?)")
                .params(
                    Ids.newUuidV7(),
                    tenant.value(),
                    author,
                    author,
                    unitVersion.version().id(),
                    period,
                    unit)
                .update());
    JsonNode byUnit = body(perform(resolve(period, "NHA_NUOC").param("unit", unit.toString())));
    assertThat(byUnit.get("version").asText()).isEqualTo("1.5.0");
    assertThat(body(perform(resolve(period, "NHA_NUOC"))).get("version").asText())
        .isEqualTo("1.4.0");
    assertThat(
            body(perform(resolve(period, "NHA_NUOC").param("unit", UUID.randomUUID().toString())))
                .get("version")
                .asText())
        .isEqualTo("1.4.0");

    Optional<Object> deadlineRule =
        TenantContext.call(
            tenant, () -> query.parameter(tenant, rootVersion, "plan_approval_deadline_rule"));
    assertThat(deadlineRule).contains("NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2");
    assertThat(
            TenantContext.call(tenant, () -> query.content(tenant, rootVersion))
                .orElseThrow()
                .version())
        .isEqualTo("1.4.0");

    inTenant(
        jdbc ->
            jdbc.sql("DELETE FROM policy.policy_binding WHERE tenant_id = ? AND period_id = ?")
                .params(tenant.value(), period)
                .update());
    resolution.evict(tenant);
    assertThat(perform(resolve(period, "NHA_NUOC")).getResponse().getStatus()).isEqualTo(200);
    assertThat(bindings(period)).isEqualTo(1);
  }

  @Test
  void periodWithoutPackReturnsNoBindingAndBadFlowIsRejected() throws Exception {
    MvcResult none = perform(resolve(UUID.randomUUID(), "DANG"));
    assertThat(none.getResponse().getStatus()).isEqualTo(404);
    assertThat(body(none).get("code").asText()).isEqualTo("POLICY-404-NO-BINDING");
    assertResponseConforms(none, "/policies/resolve", PathItem.HttpMethod.GET);

    MvcResult bad = perform(resolve(UUID.randomUUID(), "KHAC"));
    assertThat(bad.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(bad).get("code").asText()).isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            TenantContext.call(
                tenant,
                () -> query.resolve(tenant, PeriodId.of(UUID.randomUUID()), "DANG", null, null)))
        .isEmpty();
  }
}
