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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcPolicyPackRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-08 trên PostgreSQL thật với gói Đảng giả lập và bộ ca của DL-03: so
 * sánh phiên bản 2.2.0 (thêm một quy tắc) với 2.1.0, thiếu phiên bản nguồn 404; tính thử ca Q-001
 * cho mức xếp loại, vết tính và lời giải thích; ca ngoài bộ mẫu 422; hai thao tác khớp {@code
 * policies.yaml}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class PolicyPreviewApiIT extends ContractConformanceTest {

  private static final String PACK = "khoi-dang/quy-dinh-moi";

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyPackService packs;
  @Autowired private GoldenTestCaseService cases;
  @Autowired private JdbcPolicyPackRepository packRepo;

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
    return root;
  }

  private static String path(VersionView root, String version) {
    return "/api/v1/policies/packs/" + root.pack().id() + "/versions/" + version;
  }

  private MockHttpServletRequestBuilder evaluate(VersionView root, String caseCode) {
    return post(path(root, "2.1.0") + "/evaluate")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"caseCode\":\"" + caseCode + "\"}");
  }

  @Test
  void diffAndEvaluatePerContract() throws Exception {
    VersionView root = seed();
    VersionView next =
        TenantContext.call(
            tenant,
            () ->
                packs.newVersion(
                    tenant, author, root.pack().id(), "2.1.0", "2.2.0", "Thêm quy tắc thử"));
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
                      "QĐ Điều 11",
                      999,
                      "{}",
                      false)),
              author);
          return null;
        });

    MvcResult diff = perform(get(path(root, "2.2.0") + "/diff?base=2.1.0"));
    assertThat(diff.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(diff, "/policies/packs/{id}/versions/{v}/diff", PathItem.HttpMethod.GET);
    JsonNode d = body(diff);
    assertThat(d.get("base").asText()).isEqualTo("2.1.0");
    assertThat(d.get("target").asText()).isEqualTo("2.2.0");
    assertThat(d.get("changes"))
        .anySatisfy(
            c -> {
              assertThat(c.get("kind").asText()).isEqualTo("RULE");
              assertThat(c.get("key").asText()).isEqualTo("quy_tac_thu");
              assertThat(c.get("change").asText()).isEqualTo("THEM");
            });
    assertThat(perform(get(path(root, "2.2.0") + "/diff?base=9.9.9")).getResponse().getStatus())
        .isEqualTo(404);

    MvcResult ok = perform(evaluate(root, "Q-001"));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        ok, "/policies/packs/{id}/versions/{v}/evaluate", PathItem.HttpMethod.POST);
    JsonNode e = body(ok);
    assertThat(e.get("caseCode").asText()).isEqualTo("Q-001");
    assertThat(e.get("ratingLevel").asText()).isEqualTo("HTXSNV");
    assertThat(e.get("trace").size()).isPositive();
    assertThat(e.get("explanation").asText()).contains("2.1.0").doesNotContain("{");

    MvcResult missing = perform(evaluate(root, "KHONG-CO"));
    assertThat(missing.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(missing).get("code").asText()).isEqualTo("POLICY-422-CASE-NOT-GOLDEN");
  }
}
