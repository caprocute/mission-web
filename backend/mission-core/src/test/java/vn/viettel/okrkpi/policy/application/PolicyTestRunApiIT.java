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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService.CaseEdit;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.application.port.CelJsRunner;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcGoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcPolicyPackRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-07 trên PostgreSQL thật với gói Đảng giả lập và bộ 63 ca của DL-03:
 * chạy kiểm thử (202, tác vụ {@code TEST_RUN}) đạt thì phiên bản sang {@code DAT_KIEM_THU}; một
 * biểu thức cho kết quả khác ở cel-js thì về {@code NHAP} với {@code engine_match = false}; thêm
 * một ca sai thì về {@code NHAP}; thiếu {@code If-Match} 428, lệch 409; ba thao tác khớp {@code
 * policies.yaml}. Bộ chạy cel-js giả lập chạy lại bằng cel-java.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class PolicyTestRunApiIT extends ContractConformanceTest {

  private static final String PACK = "khoi-dang/quy-dinh-moi";
  static final AtomicBoolean MISMATCH = new AtomicBoolean();

  @TestConfiguration
  static class JsConfig {
    @Bean
    @Primary
    CelJsRunner testCelJsRunner() {
      return new CelJsRunner() {
        @Override
        public boolean available() {
          return true;
        }

        @Override
        public List<Result> evaluate(List<Evaluation> evaluations) {
          return evaluations.stream()
              .map(
                  e ->
                      MISMATCH.get() && e.expression().contains("so_luong_duyet")
                          ? new Result(true, 0.5, null, null)
                          : PolicyTestRunServiceTest.echo(e))
              .toList();
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
  @Autowired private JdbcPolicyPackRepository packRepo;
  @Autowired private JdbcGoldenTestCaseRepository caseRepo;

  @Autowired
  @Qualifier("policyTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();

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
    approveAll(root.pack().id());
    return root;
  }

  private void approveAll(UUID packId) throws Exception {
    List<GoldenTestCase> pending =
        tx(() -> caseRepo.listCases(tenant, packId, GoldenCaseStatus.NHAP, null, null, 200));
    for (GoldenTestCase c : pending) {
      TenantContext.call(tenant, () -> cases.approveCase(tenant, approver, c.id(), c.rowVersion()));
    }
  }

  private PolicyPackVersion version(VersionView root) throws Exception {
    return tx(() -> packRepo.findVersion(tenant, root.pack().id(), "2.1.0").orElseThrow());
  }

  private static String base(VersionView root) {
    return "/api/v1/policies/packs/" + root.pack().id() + "/versions/2.1.0/test-runs";
  }

  private MockHttpServletRequestBuilder run(VersionView root, String etag) {
    MockHttpServletRequestBuilder b =
        post(base(root))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"propertyDatasets\":200}");
    return etag == null ? b : b.header("If-Match", etag);
  }

  /** Chạy và đợi lần chạy mới nhất xong; trả chi tiết lần chạy. */
  private JsonNode runAndWait(VersionView root) throws Exception {
    MvcResult accepted = perform(run(root, "\"" + version(root).rowVersion() + "\""));
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(
        accepted, "/policies/packs/{id}/versions/{v}/test-runs", PathItem.HttpMethod.POST);
    assertThat(body(accepted).get("type").asText()).isEqualTo("TEST_RUN");
    long deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      JsonNode latest = body(perform(get(base(root) + "?limit=1"))).get("items").get(0);
      if (!"DANG_CHAY".equals(latest.get("result").asText())) {
        MvcResult one = perform(get(base(root) + "/" + latest.get("id").asText()));
        assertThat(one.getResponse().getStatus()).isEqualTo(200);
        assertResponseConforms(
            one, "/policies/packs/{id}/versions/{v}/test-runs/{runId}", PathItem.HttpMethod.GET);
        return body(one);
      }
      Thread.onSpinWait();
      Thread.sleep(100);
    }
    throw new AssertionError("Lần chạy kiểm thử chưa xong sau 2 phút");
  }

  @Test
  void testRunsMoveVersionPerContract() throws Exception {
    VersionView root = seed();
    assertThat(perform(run(root, null)).getResponse().getStatus()).isEqualTo(428);
    MvcResult stale = perform(run(root, "\"99\""));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(stale).get("code").asText()).isEqualTo("POLICY-409-VERSION-CONFLICT");

    JsonNode passed = runAndWait(root);
    assertThat(passed.get("result").asText()).isEqualTo("DAT");
    assertThat(passed.get("total").asInt()).isEqualTo(63);
    assertThat(passed.get("passed").asInt()).isEqualTo(63);
    assertThat(passed.get("engineMatch").asBoolean()).isTrue();
    assertThat(passed.get("runNo").asInt()).isEqualTo(1);
    assertThat(passed.get("propertyResult").get("datasets").asInt()).isEqualTo(200);
    assertThat(passed.get("propertyResult").get("violations").asInt()).isZero();
    PolicyPackVersion tested = version(root);
    assertThat(tested.status().name()).isEqualTo("DAT_KIEM_THU");
    assertThat(tested.goldenPassRate()).isEqualByComparingTo("1");

    MISMATCH.set(true);
    JsonNode mismatch;
    try {
      mismatch = runAndWait(root);
    } finally {
      MISMATCH.set(false);
    }
    assertThat(mismatch.get("result").asText()).isEqualTo("KHONG_DAT");
    assertThat(mismatch.get("engineMatch").asBoolean()).isFalse();
    assertThat(mismatch.get("passed").asInt()).isEqualTo(63);
    assertThat(mismatch.get("engineMatchedCount").asInt()).isLessThan(63);
    assertThat(version(root).status().name()).isEqualTo("NHAP");

    GoldenTestCase wrong =
        TenantContext.call(
            tenant,
            () ->
                cases.addCase(
                    tenant,
                    author,
                    new CaseEdit(
                        root.pack().id(),
                        "Q-SAI",
                        "Kỳ vọng sai để kiểm tra lần chạy không đạt",
                        PolicyEvaluatorTest.suite(PACK).entries().get(0).input(),
                        Map.of("diem", 12.3),
                        null,
                        "BA")));
    TenantContext.call(
        tenant, () -> cases.approveCase(tenant, approver, wrong.id(), wrong.rowVersion()));
    JsonNode failed = runAndWait(root);
    assertThat(failed.get("result").asText()).isEqualTo("KHONG_DAT");
    assertThat(failed.get("total").asInt()).isEqualTo(64);
    assertThat(failed.get("failed").asInt()).isEqualTo(1);
    assertThat(failed.get("engineMatch").asBoolean()).isTrue();
    assertThat(failed.get("runNo").asInt()).isEqualTo(3);
    assertThat(version(root).status().name()).isEqualTo("NHAP");

    MvcResult list = perform(get(base(root)));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        list, "/policies/packs/{id}/versions/{v}/test-runs", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items"))
        .extracting(n -> n.get("runNo").asInt())
        .containsExactly(3, 2, 1);
    MvcResult page = perform(get(base(root) + "?limit=1"));
    MvcResult next =
        perform(get(base(root) + "?limit=1&cursor=" + body(page).get("nextCursor").asText()));
    assertThat(body(next).get("items").get(0).get("runNo").asInt()).isEqualTo(2);
    assertThat(perform(get(base(root) + "/" + UUID.randomUUID())).getResponse().getStatus())
        .isEqualTo(404);
  }
}
