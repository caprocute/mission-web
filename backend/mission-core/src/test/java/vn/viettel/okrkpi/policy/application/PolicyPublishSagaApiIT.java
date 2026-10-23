package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.atomic.AtomicInteger;
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
import vn.viettel.okrkpi.config.api.UiPackCompatibilityApi;
import vn.viettel.okrkpi.config.application.NewPeriod;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovalNotices;
import vn.viettel.okrkpi.policy.application.port.PolicyPackDistribution;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishedEvents;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcGoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcPolicyPackRepository;
import vn.viettel.okrkpi.policy.infrastructure.signing.PlatformKeyPolicyPackSigner;
import vn.viettel.okrkpi.policy.infrastructure.web.PolicyCaller;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-10 trên PostgreSQL thật với gói Đảng giả lập: bước nạp lỗi thì bù trừ,
 * phiên bản giữ {@code CHO_PHE_DUYET} có chữ ký; UI Pack không tương thích chặn trước khi gắn; phát
 * hành lại không gọi ký; phát hành thành công gắn kỳ và phát đúng một EVT-13; phiên bản mới đổi
 * công thức áp cho kỳ ghim nó trên cùng bản chạy; thu hồi bị chặn khi kỳ đã khoá; ba thao tác khớp
 * {@code policies.yaml}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {"mission.outbox.relay.enabled=false", "mission.policy.publish.retry-delay=PT0S"})
@AutoConfigureMockMvc(addFilters = false)
@Import({PolicyTestRunApiIT.JsConfig.class, PolicyPublishSagaApiIT.SagaConfig.class})
@DirtiesContext
class PolicyPublishSagaApiIT extends ContractConformanceTest {

  private static final String PACK = "khoi-dang/quy-dinh-moi";

  /** Bên ngoài saga điều khiển được trong kiểm thử; lớp thường kèm {@code @Import}. */
  static class SagaConfig {
    static volatile UUID current;
    static volatile boolean aal2;
    static volatile boolean compatible = true;
    static volatile boolean distributionFails;
    static final AtomicInteger SIGNED = new AtomicInteger();
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    PolicyCaller sagaTestCaller() {
      return new PolicyCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.ofNullable(current);
        }

        @Override
        public Optional<String> acr() {
          return aal2 ? Optional.of("aal2") : Optional.empty();
        }

        @Override
        public Optional<Long> authTime() {
          return aal2 ? Optional.of(Instant.now().getEpochSecond() - 30) : Optional.empty();
        }
      };
    }

    @Bean
    @Primary
    PolicyApprovalNotices sagaTestNotices() {
      return new PolicyApprovalNotices() {
        @Override
        public void submitted(
            TenantId tenant, String packName, PolicyPackVersion version, List<UUID> to) {
          // Không gửi thông báo trong kiểm thử saga.
        }

        @Override
        public void rejected(
            TenantId tenant,
            String packName,
            PolicyPackVersion version,
            String reason,
            List<UUID> to) {
          // Không gửi thông báo trong kiểm thử saga.
        }
      };
    }

    @Bean
    @Primary
    PolicyPackSigner sagaTestSigner(PlatformKeyPolicyPackSigner policyPackSigner) {
      return (tenant, approver, versionId, contentHash, esignSessionId) -> {
        SIGNED.incrementAndGet();
        return policyPackSigner.sign(tenant, approver, versionId, contentHash, esignSessionId);
      };
    }

    @Bean
    @Primary
    UiPackCompatibilityApi sagaTestUiPacks() {
      return (tenant, ref) -> compatible;
    }

    @Bean
    @Primary
    PolicyPackDistribution sagaTestDistribution() {
      return (tenant, pack, version) -> {
        if (distributionFails) {
          throw new PolicyPackDistribution.DistributionFailedException("Cell 2 không nạp được gói");
        }
      };
    }

    @Bean
    @Primary
    PolicyPublishedEvents sagaTestEvents() {
      return (tenant, version) -> EVENTS.add("EVT-13:" + version.version());
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
  @Autowired private PolicyQueryApi query;

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
  void reset() {
    SagaConfig.current = null;
    SagaConfig.aal2 = false;
    SagaConfig.compatible = true;
    SagaConfig.distributionFails = false;
  }

  private void as(UUID user, boolean strong) {
    SagaConfig.current = user;
    SagaConfig.aal2 = strong;
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

  private JdbcClient sql() {
    return JdbcClient.create(policyDataSource);
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

  /** Kiểm thử, gửi phê duyệt cho kỳ, rồi ký phê duyệt; trả mã tác vụ phát hành. */
  private String approve(VersionView root, String v, UUID period) throws Exception {
    passTests(root, v);
    as(author, false);
    MvcResult submitted =
        perform(
            post(path(root, v) + "/submit")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", etag(root, v))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"effectivePeriodId\":\""
                        + period
                        + "\",\"scopes\":[{\"flow\":\"DANG\",\"subjectGroupId\":null,"
                        + "\"orgUnitId\":null}]}"));
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    as(approver, true);
    MvcResult approved =
        perform(
            post(path(root, v) + "/approve")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", etag(root, v))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Đồng ý phát hành\"}"));
    assertThat(approved.getResponse().getStatus()).isEqualTo(202);
    return body(approved).get("id").asText();
  }

  private MvcResult republish(VersionView root, String v) throws Exception {
    as(author, false);
    return perform(
        post(path(root, v) + "/republish")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", etag(root, v)));
  }

  private MvcResult revoke(VersionView root, String v) throws Exception {
    as(approver, true);
    return perform(
        post(path(root, v) + "/revoke")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", etag(root, v))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Phát hiện sai ngưỡng điểm hoàn thành xuất sắc\"}"));
  }

  /** Chờ saga kết thúc; trả trạng thái cuối. */
  private String awaitSaga(String jobId) throws Exception {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      String status =
          tx(
              () ->
                  sql()
                      .sql("SELECT status FROM policy.publish_saga WHERE id = ?")
                      .param(UUID.fromString(jobId))
                      .query(String.class)
                      .single());
      if (!List.of("DANG_CHAY", "CHO_SU_KIEN", "DANG_BU_TRU").contains(status)) {
        return status;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Saga phát hành chưa kết thúc sau 1 phút: "
            + tx(
                () ->
                    sql()
                        .sql(
                            "SELECT status, step_code, attempts, last_error FROM policy.publish_saga WHERE id = ?")
                        .param(UUID.fromString(jobId))
                        .query()
                        .singleRow()));
  }

  private List<UUID> boundVersions(UUID period) throws Exception {
    return tx(
        () ->
            sql()
                .sql(
                    "SELECT policy_pack_version_id FROM policy.policy_binding"
                        + " WHERE period_id = ? AND flow = 'DANG'")
                .param(period)
                .query(UUID.class)
                .list());
  }

  @Test
  void publishCompensateRepublishAndRevokePerContract() throws Exception {
    VersionView root = seed();
    UUID q1 = period("Q1-2027", LocalDate.of(2027, 1, 1));
    UUID q2 = period("Q2-2027", LocalDate.of(2027, 4, 1));
    SagaConfig.EVENTS.clear();
    SagaConfig.SIGNED.set(0);

    // Bước nạp lỗi: bù trừ, phiên bản giữ CHO_PHE_DUYET có chữ ký, không gắn kỳ, không EVT-13.
    SagaConfig.distributionFails = true;
    String failed = approve(root, "2.1.0", q1);
    assertThat(awaitSaga(failed)).isEqualTo("DA_BU_TRU");
    PolicyPackVersion kept = version(root, "2.1.0");
    assertThat(kept.status().name()).isEqualTo("CHO_PHE_DUYET");
    assertThat(kept.signed()).isTrue();
    assertThat(boundVersions(q1)).isEmpty();
    assertThat(SagaConfig.EVENTS).isEmpty();
    assertThat(SagaConfig.SIGNED.get()).isEqualTo(1);
    String error =
        tx(
            () ->
                sql()
                    .sql("SELECT last_error FROM policy.publish_saga WHERE id = ?")
                    .param(UUID.fromString(failed))
                    .query(String.class)
                    .single());
    assertThat(error).contains("Cell 2");

    // UI Pack không tương thích: dừng trước khi gắn.
    SagaConfig.distributionFails = false;
    SagaConfig.compatible = false;
    MvcResult blocked = republish(root, "2.1.0");
    assertThat(blocked.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(
        blocked, "/policies/packs/{id}/versions/{v}/republish", PathItem.HttpMethod.POST);
    UUID blockedId = UUID.fromString(body(blocked).get("id").asText());
    assertThat(awaitSaga(blockedId.toString())).isEqualTo("DA_BU_TRU");
    Map<String, Object> step =
        tx(
            () ->
                sql()
                    .sql("SELECT current_step_no, last_error FROM policy.publish_saga WHERE id = ?")
                    .param(blockedId)
                    .query()
                    .singleRow());
    assertThat(step).containsEntry("current_step_no", 1);
    assertThat(boundVersions(q1)).isEmpty();

    // Phát hành lại thành công, không ký lại; đúng một EVT-13.
    SagaConfig.compatible = true;
    MvcResult again = republish(root, "2.1.0");
    assertThat(again.getResponse().getStatus()).isEqualTo(202);
    assertThat(awaitSaga(body(again).get("id").asText())).isEqualTo("HOAN_TAT");
    PolicyPackVersion published = version(root, "2.1.0");
    assertThat(published.status().name()).isEqualTo("DA_PHAT_HANH");
    assertThat(published.publishedAt()).isNotNull();
    assertThat(boundVersions(q1)).containsExactly(published.id());
    assertThat(SagaConfig.EVENTS).containsExactly("EVT-13:2.1.0");
    assertThat(SagaConfig.SIGNED.get()).isEqualTo(1);
    MvcResult already = republish(root, "2.1.0");
    assertThat(already.getResponse().getStatus()).isEqualTo(409);
    assertResponseConforms(
        already, "/policies/packs/{id}/versions/{v}/republish", PathItem.HttpMethod.POST);
    assertThat(body(already).get("code").asText()).isEqualTo("POLICY-409-VERSION-CONFLICT");

    // Đổi công thức không phát hành phần mềm: 2.2.0 thêm quy tắc, ghim cho Q2 trên cùng bản chạy.
    as(author, false);
    VersionView next =
        TenantContext.call(
            tenant,
            () ->
                packs.newVersion(
                    tenant, author, root.pack().id(), "2.1.0", "2.2.0", "Thêm quy tắc thưởng"));
    tx(
        () -> {
          packRepo.insertRules(
              tenant,
              List.of(
                  new PolicyRule(
                      UUID.randomUUID(),
                      next.version().id(),
                      "quy_tac_thu",
                      "1 + 1",
                      "DECIMAL",
                      "{}",
                      "",
                      999,
                      "{}",
                      false)),
              author);
          return null;
        });
    assertThat(awaitSaga(approve(root, "2.2.0", q2))).isEqualTo("HOAN_TAT");
    PolicyQueryApi.ResolvedPack forQ2 =
        TenantContext.call(tenant, () -> query.resolve(tenant, PeriodId.of(q2), "DANG", null, null))
            .orElseThrow();
    assertThat(forQ2.version()).isEqualTo("2.2.0");
    assertThat(
            TenantContext.call(tenant, () -> query.content(tenant, forQ2.versionId()))
                .orElseThrow()
                .rules())
        .anyMatch(r -> r.ruleKey().equals("quy_tac_thu"));
    assertThat(
            TenantContext.call(
                    tenant, () -> query.resolve(tenant, PeriodId.of(q1), "DANG", null, null))
                .orElseThrow()
                .version())
        .isEqualTo("2.1.0");
    assertThat(SagaConfig.EVENTS).containsExactly("EVT-13:2.1.0", "EVT-13:2.2.0");

    // Thu hồi: kỳ đã khoá bị chặn; trước kỳ hiệu lực thì về phiên bản trước và phát lại EVT-13.
    tx(
        () ->
            sql()
                .sql("UPDATE policy.policy_binding SET is_frozen = true WHERE period_id = ?")
                .param(q2)
                .update());
    MvcResult frozen = revoke(root, "2.2.0");
    assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
    assertResponseConforms(
        frozen, "/policies/packs/{id}/versions/{v}/revoke", PathItem.HttpMethod.POST);
    assertThat(body(frozen).get("code").asText()).isEqualTo("POLICY-409-PERIOD-LOCKED");
    tx(
        () ->
            sql()
                .sql("UPDATE policy.policy_binding SET is_frozen = false WHERE period_id = ?")
                .param(q2)
                .update());
    MvcResult revoked = revoke(root, "2.2.0");
    assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        revoked, "/policies/packs/{id}/versions/{v}/revoke", PathItem.HttpMethod.POST);
    assertThat(body(revoked).get("status").asText()).isEqualTo("NGUNG");
    assertThat(boundVersions(q2)).isEmpty();
    assertThat(version(root, "2.1.0").status().name()).isEqualTo("DA_PHAT_HANH");
    assertThat(SagaConfig.EVENTS).containsExactly("EVT-13:2.1.0", "EVT-13:2.2.0", "EVT-13:2.1.0");
  }
}
