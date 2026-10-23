package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovalNotices;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishLauncher;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcGoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcPolicyPackRepository;
import vn.viettel.okrkpi.policy.infrastructure.web.PolicyCaller;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-09 trên PostgreSQL thật với gói Đảng giả lập: gửi khi chưa đạt kiểm thử
 * 409; đạt kiểm thử thì có {@code content_hash} tính lại được; kỳ đã bắt đầu bị chặn; gửi phê duyệt
 * 200; người soạn tự duyệt 403; thiếu bước lên 401; ký xong đủ cột chữ ký và 202 {@code
 * POLICY_PUBLISH}; CHECK {@code ck_policy_pack_version__signed} chặn người duyệt trùng người soạn;
 * từ chối đưa phiên bản về nháp; ba thao tác khớp {@code policies.yaml}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import({PolicyTestRunApiIT.JsConfig.class, PolicyApprovalApiIT.CallerConfig.class})
@DirtiesContext
class PolicyApprovalApiIT extends ContractConformanceTest {

  private static final String PACK = "khoi-dang/quy-dinh-moi";

  /**
   * Người gọi đổi được trong kiểm thử; lớp thường kèm {@code @Import} để không lọt sang IT khác.
   */
  static class CallerConfig {
    static volatile UUID current;
    static volatile String acr;
    static volatile Long authTime;
    static final List<String> NOTICES = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    PolicyApprovalNotices approvalTestNotices() {
      return new PolicyApprovalNotices() {
        @Override
        public void submitted(
            TenantId tenant, String packName, PolicyPackVersion version, List<UUID> to) {
          NOTICES.add("GUI:" + version.version());
        }

        @Override
        public void rejected(
            TenantId tenant,
            String packName,
            PolicyPackVersion version,
            String reason,
            List<UUID> to) {
          NOTICES.add("TU_CHOI:" + version.version() + ":" + to);
        }
      };
    }

    /** Saga phát hành có IT riêng (PolicyPublishSagaApiIT); ở đây chỉ nhận lệnh khởi chạy. */
    @Bean
    @Primary
    PolicyPublishLauncher approvalTestLauncher() {
      return (tenant, actor, version, request) -> UUID.randomUUID().toString();
    }

    @Bean
    @Primary
    PolicyCaller approvalTestCaller() {
      return new PolicyCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.ofNullable(current);
        }

        @Override
        public Optional<String> acr() {
          return Optional.ofNullable(acr);
        }

        @Override
        public Optional<Long> authTime() {
          return Optional.ofNullable(authTime);
        }
      };
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyPackService packs;
  @Autowired private GoldenTestCaseService cases;
  @Autowired private PeriodConfigService periods;
  @Autowired private JdbcPolicyPackRepository packRepo;
  @Autowired private JdbcGoldenTestCaseRepository caseRepo;

  @Autowired
  @Qualifier("policyDataSource") private DataSource policyDataSource;

  @Autowired
  @Qualifier("policyTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.POLICIES;
  }

  @AfterEach
  void resetCaller() {
    CallerConfig.current = null;
    CallerConfig.acr = null;
    CallerConfig.authTime = null;
  }

  private void as(UUID user, boolean aal2) {
    CallerConfig.current = user;
    CallerConfig.acr = aal2 ? "aal2" : null;
    CallerConfig.authTime = aal2 ? Instant.now().getEpochSecond() - 30 : null;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private <T> T tx(Supplier<T> work) throws Exception {
    return TenantContext.call(tenant, () -> new TransactionTemplate(tm).execute(s -> work.get()));
  }

  private VersionView seed() throws Exception {
    Map<String, Object> manifest = PolicyEvaluatorTest.manifest(PACK);
    VersionView root =
        TenantContext.call(
            tenant,
            () ->
                packs.importPack(
                    tenant,
                    author,
                    new PackImport(manifest, PackOrigin.GOC_TOAN_CUC, "KHOI_DANG", null, null)));
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    List<DecisionTable> tables =
        DmnSpecs.of(PolicyEvaluatorTest.DATA.resolve(PACK), manifest).entrySet().stream()
            .map(
                e ->
                    new DecisionTable(
                        UUID.randomUUID(),
                        root.version().id(),
                        e.getKey(),
                        "<definitions/>",
                        e.getValue().hitPolicy(),
                        sha.digest(e.getKey().getBytes(StandardCharsets.UTF_8)),
                        "",
                        DmnSpecs.rulesJson(e.getValue()),
                        "{}"))
            .toList();
    tx(
        () -> {
          packRepo.insertDecisionTables(tenant, tables, author);
          return null;
        });
    String suite =
        Files.readString(
            PolicyEvaluatorTest.DATA.resolve(PACK).resolve("tests/bo-ca-mau-gia-lap.yaml"));
    TenantContext.call(tenant, () -> cases.importSuite(tenant, author, root.pack().id(), suite));
    List<GoldenTestCase> pending =
        tx(
            () ->
                caseRepo.listCases(
                    tenant, root.pack().id(), GoldenCaseStatus.NHAP, null, null, 200));
    for (GoldenTestCase c : pending) {
      TenantContext.call(tenant, () -> cases.approveCase(tenant, approver, c.id(), c.rowVersion()));
    }
    return root;
  }

  private UUID period(String code, LocalDate start) throws Exception {
    return TenantContext.call(
            tenant,
            () ->
                periods.create(
                    tenant,
                    author,
                    new NewPeriod(
                        code,
                        "Kỳ " + code,
                        PeriodKind.QUY,
                        PeriodFlow.DANG,
                        null,
                        start,
                        start.plusMonths(3).minusDays(1),
                        null,
                        null,
                        null)))
        .period()
        .id()
        .value();
  }

  private PolicyPackVersion version(VersionView root, String v) throws Exception {
    return tx(() -> packRepo.findVersion(tenant, root.pack().id(), v).orElseThrow());
  }

  private static String path(VersionView root, String v) {
    return "/api/v1/policies/packs/" + root.pack().id() + "/versions/" + v;
  }

  private String etag(VersionView root, String v) throws Exception {
    return "\"" + version(root, v).rowVersion() + "\"";
  }

  private void passTests(VersionView root, String v) throws Exception {
    as(author, false);
    MvcResult accepted =
        perform(
            post(path(root, v) + "/test-runs")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", etag(root, v))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    long deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      JsonNode latest =
          body(perform(get(path(root, v) + "/test-runs?limit=1"))).get("items").get(0);
      if (!"DANG_CHAY".equals(latest.get("result").asText())) {
        assertThat(latest.get("result").asText()).isEqualTo("DAT");
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Lần chạy kiểm thử chưa xong sau 2 phút");
  }

  private MockHttpServletRequestBuilder submit(
      VersionView root, String v, String etag, UUID period) {
    MockHttpServletRequestBuilder b =
        post(path(root, v) + "/submit")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"effectivePeriodId\":\""
                    + period
                    + "\",\"scopes\":[{\"flow\":\"DANG\",\"subjectGroupId\":null,"
                    + "\"orgUnitId\":null}],\"note\":\"Áp dụng toàn đơn vị\"}");
    return etag == null ? b : b.header("If-Match", etag);
  }

  private MockHttpServletRequestBuilder approve(VersionView root, String v, String etag) {
    return post(path(root, v) + "/approve")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"comment\":\"Đồng ý phát hành\"}");
  }

  private MockHttpServletRequestBuilder reject(VersionView root, String v, String etag) {
    return post(path(root, v) + "/reject")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"Cần bổ sung ca kiểm thử cho trường hợp ngày nghỉ bù\"}");
  }

  @Test
  void submitApproveAndRejectPerContract() throws Exception {
    VersionView root = seed();
    UUID future = period("Q1-2027", LocalDate.of(2027, 1, 1));
    UUID started = period("Q4-2026", LocalDate.of(2026, 10, 1));

    as(author, false);
    MvcResult untested = perform(submit(root, "2.1.0", etag(root, "2.1.0"), future));
    assertThat(untested.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(untested).get("code").asText()).isEqualTo("POLICY-409-NOT-TESTED");

    passTests(root, "2.1.0");
    PolicyPackVersion tested = version(root, "2.1.0");
    assertThat(tested.contentHash()).hasSize(64);
    String recomputed =
        tx(
            () ->
                PolicyContentHash.of(
                    json,
                    tested.manifestJson(),
                    packRepo.rules(tenant, tested.id()),
                    packRepo.decisionTables(tenant, tested.id())));
    assertThat(recomputed).isEqualTo(tested.contentHash());

    as(author, false);
    assertThat(perform(submit(root, "2.1.0", null, future)).getResponse().getStatus())
        .isEqualTo(428);
    MvcResult locked = perform(submit(root, "2.1.0", etag(root, "2.1.0"), started));
    assertThat(locked.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(locked).get("code").asText()).isEqualTo("POLICY-409-PERIOD-LOCKED");

    MvcResult submitted = perform(submit(root, "2.1.0", etag(root, "2.1.0"), future));
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        submitted, "/policies/packs/{id}/versions/{v}/submit", PathItem.HttpMethod.POST);
    assertThat(body(submitted).get("status").asText()).isEqualTo("CHO_PHE_DUYET");
    assertThat(body(submitted).get("effectiveFrom").asText()).isEqualTo("2027-01-01");

    as(author, true);
    MvcResult self = perform(approve(root, "2.1.0", etag(root, "2.1.0")));
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertResponseConforms(
        self, "/policies/packs/{id}/versions/{v}/approve", PathItem.HttpMethod.POST);
    assertThat(body(self).get("code").asText()).isEqualTo("POLICY-403-SAME-AUTHOR");

    as(approver, false);
    MvcResult weak = perform(approve(root, "2.1.0", etag(root, "2.1.0")));
    assertThat(weak.getResponse().getStatus()).isEqualTo(401);
    assertThat(weak.getResponse().getHeader("WWW-Authenticate")).contains("aal2");
    assertThat(version(root, "2.1.0").signed()).isFalse();

    as(approver, true);
    MvcResult approved = perform(approve(root, "2.1.0", etag(root, "2.1.0")));
    assertThat(approved.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(
        approved, "/policies/packs/{id}/versions/{v}/approve", PathItem.HttpMethod.POST);
    assertThat(body(approved).get("type").asText()).isEqualTo("POLICY_PUBLISH");
    assertThat(approved.getResponse().getHeader("Location")).contains("/jobs/");
    PolicyPackVersion signed = version(root, "2.1.0");
    assertThat(signed.signed()).isTrue();
    assertThat(signed.approvedBy()).isEqualTo(approver);
    assertThat(signed.status().name()).isEqualTo("CHO_PHE_DUYET");
    Map<String, Object> columns =
        tx(
            () ->
                JdbcClient.create(policyDataSource)
                    .sql(
                        "SELECT signature IS NOT NULL AS s, signer_cert_ref IS NOT NULL AS c,"
                            + " signed_at IS NOT NULL AS t, approved_at IS NOT NULL AS a"
                            + " FROM policy.policy_pack_version WHERE id = ?")
                    .param(signed.id())
                    .query()
                    .singleRow());
    assertThat(columns).containsEntry("s", true).containsEntry("c", true);
    assertThat(columns).containsEntry("t", true).containsEntry("a", true);
    String request =
        tx(
            () ->
                JdbcClient.create(policyDataSource)
                    .sql(
                        "SELECT status FROM policy.publish_request WHERE policy_pack_version_id = ?")
                    .param(signed.id())
                    .query(String.class)
                    .single());
    assertThat(request).isEqualTo("DA_DUYET");

    assertThatThrownBy(
            () ->
                tx(
                    () ->
                        JdbcClient.create(policyDataSource)
                            .sql(
                                "UPDATE policy.policy_pack_version SET approved_by = created_by,"
                                    + " status = 'DA_PHAT_HANH' WHERE id = ?")
                            .param(signed.id())
                            .update()))
        .hasMessageContaining("ck_policy_pack_version__signed");

    MvcResult notPending = perform(reject(root, "2.1.0", etag(root, "2.1.0")));
    assertThat(notPending.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(notPending).get("code").asText()).isEqualTo("POLICY-409-NOT-PENDING");

    as(author, false);
    TenantContext.call(
        tenant,
        () -> packs.newVersion(tenant, author, root.pack().id(), "2.1.0", "2.2.0", "Sửa ngưỡng"));
    passTests(root, "2.2.0");
    as(author, false);
    assertThat(
            perform(submit(root, "2.2.0", etag(root, "2.2.0"), future)).getResponse().getStatus())
        .isEqualTo(200);
    as(approver, false);
    MvcResult rejected = perform(reject(root, "2.2.0", etag(root, "2.2.0")));
    assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        rejected, "/policies/packs/{id}/versions/{v}/reject", PathItem.HttpMethod.POST);
    assertThat(body(rejected).get("status").asText()).isEqualTo("NHAP");
    assertThat(CallerConfig.NOTICES).contains("TU_CHOI:2.2.0:[" + author + "]");
  }
}
