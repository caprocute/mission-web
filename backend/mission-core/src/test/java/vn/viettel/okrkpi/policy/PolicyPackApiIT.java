package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcPolicyPackRepository;
import vn.viettel.okrkpi.policy.infrastructure.web.PolicyCaller;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-02 trên PostgreSQL thật: nhập gói gốc ND 335 mẫu, nhân bản phiên bản
 * kèm đủ quy tắc và bảng quyết định (Idempotency-Key, ETag, Location), số phiên bản trùng 422
 * {@code POLICY-422-VERSION-EXISTS}, gói tenant kế thừa nhân bản từ gói cha và bị chặn sửa quy tắc
 * không {@code overridable} (403 {@code POLICY-403-NOT-OVERRIDABLE}), chuyển trạng thái có khoá lạc
 * quan; năm thao tác khớp {@code policies.yaml}. Sổ cái thay bằng bản ghi nhận trong bộ nhớ.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
class PolicyPackApiIT extends ContractConformanceTest {

  static final UUID AUTHOR = UUID.randomUUID();
  static final List<String> AUDITS = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class PolicyTestConfig {

    @Bean
    @Primary
    PolicyAudit testPolicyAudit() {
      return (tenant, actor, action, type, objectId, aggregateId, version) ->
          AUDITS.add(action + ":" + objectId + ":" + version);
    }

    @Bean
    PolicyCaller testPolicyCaller() {
      return () -> Optional.of(AUTHOR);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PolicyPackService packs;
  @Autowired private JdbcPolicyPackRepository repository;

  @Autowired
  @Qualifier("policyDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("policyTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());

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

  private <T> T inTenant(Function<JdbcClient, T> query) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> query.apply(jdbc)));
  }

  private static MockHttpServletRequestBuilder create(UUID packId, String key, String content) {
    return post("/api/v1/policies/packs/" + packId + "/versions")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(content);
  }

  /** Nhập gói gốc ND 335 mẫu và gắn ba quy tắc, một bảng quyết định vào phiên bản 1.4.0. */
  private VersionView seedRoot() throws Exception {
    Map<String, Object> manifest =
        PolicyPackFixtures.yaml(
            PolicyPackFixtures.pack(PolicyPackFixtures.GOI_NHA_NUOC).resolve("policy.yaml"));
    VersionView root =
        TenantContext.call(
            tenant,
            () ->
                packs.importPack(
                    tenant,
                    AUTHOR,
                    new PackImport(
                        manifest, PackOrigin.GOC_TOAN_CUC, "KHOI_CHINH_QUYEN", null, null)));
    UUID v = root.version().id();
    inTenant(
        jdbc -> {
          repository.insertRules(
              tenant,
              List.of(
                  rule(v, "kpi_nhiem_vu", 1, false),
                  rule(v, "tru_tien_do", 2, true),
                  rule(v, "muc_cuoi", 3, false)),
              AUTHOR);
          repository.insertDecisionTables(
              tenant,
              List.of(
                  new DecisionTable(
                      Ids.newUuidV7(),
                      v,
                      "xep_loai",
                      "<definitions/>",
                      "FIRST",
                      new byte[] {1, 2, 3},
                      "NĐ 335/2025 Điều 5",
                      "[{\"if\":\"diem >= 90\",\"then\":\"HTXSNV\"}]",
                      "{\"HTXSNV\":\"Hoàn thành xuất sắc\"}")),
              AUTHOR);
          return null;
        });
    return root;
  }

  private static PolicyRule rule(UUID versionId, String key, int order, boolean overridable) {
    return new PolicyRule(
        Ids.newUuidV7(),
        versionId,
        key,
        "ho_so.diem * 1.0",
        "DECIMAL",
        "{\"digits\":1,\"mode\":\"NUA_LEN\"}",
        "NĐ 335/2025",
        order,
        "{\"tru_tien_do\":0.05}",
        overridable);
  }

  private long count(String table, UUID versionId) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM policy." + table + " WHERE policy_pack_version_id = ?")
                .param(versionId)
                .query(Long.class)
                .single());
  }

  @Test
  void cloneVersionCopiesRulesTablesAndConformsToContract() throws Exception {
    VersionView root = seedRoot();
    UUID packId = root.pack().id();
    String key = UUID.randomUUID().toString();
    String content =
        "{\"baseVersion\":\"1.4.0\",\"version\":\"1.5.0\","
            + "\"changeNote\":\"Đổi cách đếm ngày chậm theo ngày làm việc\"}";

    MvcResult created = perform(create(packId, key, content));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(created.getResponse().getHeader("Location"))
        .endsWith("/api/v1/policies/packs/" + packId + "/versions/1.5.0");
    JsonNode draft = body(created);
    assertThat(draft.get("status").asText()).isEqualTo("NHAP");
    assertThat(draft.get("packCode").asText()).isEqualTo("khoi-chinh-quyen/nd335");
    assertThat(draft.get("manifest").get("apiVersion").asText()).isEqualTo("policy.okrkpi.vn/v1");
    assertResponseConforms(created, "/policies/packs/{id}/versions", PathItem.HttpMethod.POST);

    UUID draftId = UUID.fromString(draft.get("id").asText());
    assertThat(count("policy_rule", draftId)).isEqualTo(3);
    assertThat(count("decision_table", draftId)).isEqualTo(1);
    List<String> copied =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT rule_key || ':' || overridable || ':' || params_json::text"
                            + " FROM policy.policy_rule WHERE policy_pack_version_id = ?"
                            + " ORDER BY eval_order")
                    .param(draftId)
                    .query(String.class)
                    .list());
    assertThat(copied)
        .containsExactly(
            "kpi_nhiem_vu:false:{\"tru_tien_do\": 0.05}",
            "tru_tien_do:true:{\"tru_tien_do\": 0.05}",
            "muc_cuoi:false:{\"tru_tien_do\": 0.05}");
    assertThat(AUDITS).contains("TAO_PHIEN_BAN_POLICY_PACK:khoi-chinh-quyen/nd335@1.5.0:0");

    MvcResult replay = perform(create(packId, key, content));
    assertThat(replay.getResponse().getStatus()).isEqualTo(201);
    assertThat(body(replay).get("id").asText()).isEqualTo(draftId.toString());
    assertThat(count("policy_rule", draftId)).isEqualTo(3);

    MvcResult duplicate = perform(create(packId, UUID.randomUUID().toString(), content));
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(duplicate).get("code").asText()).isEqualTo("POLICY-422-VERSION-EXISTS");

    MvcResult noNote =
        perform(
            create(
                packId,
                UUID.randomUUID().toString(),
                "{\"baseVersion\":\"1.4.0\",\"changeNote\":\" \"}"));
    assertThat(noNote.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(noNote).get("code").asText()).isEqualTo("COMMON-422-VALIDATION");

    MvcResult auto =
        perform(
            create(
                packId,
                UUID.randomUUID().toString(),
                "{\"baseVersion\":\"1.4.0\",\"changeNote\":\"Tự đánh số\"}"));
    assertThat(body(auto).get("version").asText()).isEqualTo("1.6.0");

    MvcResult detail = perform(get("/api/v1/policies/packs/" + packId + "/versions/1.5.0"));
    assertThat(detail.getResponse().getStatus()).isEqualTo(200);
    assertThat(detail.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertResponseConforms(detail, "/policies/packs/{id}/versions/{v}", PathItem.HttpMethod.GET);
    assertThat(
            perform(get("/api/v1/policies/packs/" + packId + "/versions/9.9.9"))
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    MvcResult first =
        perform(get("/api/v1/policies/packs/" + packId + "/versions").param("limit", "2"));
    JsonNode page = body(first);
    assertThat(page.get("items"))
        .extracting(n -> n.get("version").asText())
        .containsExactly("1.6.0", "1.5.0");
    assertResponseConforms(first, "/policies/packs/{id}/versions", PathItem.HttpMethod.GET);
    JsonNode rest =
        body(
            perform(
                get("/api/v1/policies/packs/" + packId + "/versions")
                    .param("limit", "2")
                    .param("cursor", page.get("nextCursor").asText())));
    assertThat(rest.get("items"))
        .extracting(n -> n.get("version").asText())
        .containsExactly("1.4.0");
    assertThat(rest.get("nextCursor").isNull()).isTrue();
    JsonNode drafts =
        body(
            perform(get("/api/v1/policies/packs/" + packId + "/versions").param("status", "NHAP")));
    assertThat(drafts.get("items")).hasSize(3);
    assertThat(
            perform(get("/api/v1/policies/packs/" + UUID.randomUUID() + "/versions"))
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    MvcResult list = perform(get("/api/v1/policies/packs").param("flow", "NHA_NUOC"));
    assertResponseConforms(list, "/policies/packs", PathItem.HttpMethod.GET);
    JsonNode listed = body(list).get("items");
    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).get("origin").asText()).isEqualTo("GOC_TOAN_CUC");
    assertThat(listed.get(0).get("versions")).hasSize(3);
    assertThat(body(perform(get("/api/v1/policies/packs").param("flow", "DANG"))).get("items"))
        .isEmpty();
    assertThat(
            perform(get("/api/v1/policies/packs").param("origin", "KHAC"))
                .getResponse()
                .getStatus())
        .isEqualTo(422);

    MvcResult one = perform(get("/api/v1/policies/packs/" + packId));
    assertResponseConforms(one, "/policies/packs/{id}", PathItem.HttpMethod.GET);
    assertThat(body(one).get("legalBasis").asText()).contains("335/2025");
    assertThat(
            perform(get("/api/v1/policies/packs/" + UUID.randomUUID())).getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void tenantPackInheritsParentAndCannotEditLockedRules() throws Exception {
    VersionView root = seedRoot();
    PolicyPack child =
        new PolicyPack(
            Ids.newUuidV7(),
            "don-vi-thu/nd335",
            "Gói riêng của đơn vị",
            "KHOI_CHINH_QUYEN",
            "NHA_NUOC",
            PackOrigin.TENANT,
            root.pack().id(),
            "NĐ 335/2025",
            Instant.now(),
            AUTHOR,
            0);
    Boolean inserted = inTenant(jdbc -> repository.insertPack(tenant, child));
    assertThat(inserted).isTrue();

    MvcResult created =
        perform(
            create(
                child.id(),
                UUID.randomUUID().toString(),
                "{\"baseVersion\":\"1.4.0\",\"changeNote\":\"Kế thừa gói gốc\"}"));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    JsonNode draft = body(created);
    assertThat(draft.get("version").asText()).isEqualTo("1.5.0");
    assertThat(draft.get("sourceVersionId").asText()).isEqualTo(root.version().id().toString());
    assertThat(count("policy_rule", UUID.fromString(draft.get("id").asText()))).isEqualTo(3);

    assertThatThrownBy(
            () ->
                TenantContext.run(
                    tenant,
                    () -> packs.assertRuleEditable(tenant, child.id(), "1.5.0", "kpi_nhiem_vu")))
        .isInstanceOf(BusinessException.class)
        .hasMessage("POLICY-403-NOT-OVERRIDABLE");
    TenantContext.run(
        tenant, () -> packs.assertRuleEditable(tenant, child.id(), "1.5.0", "tru_tien_do"));
    TenantContext.run(
        tenant, () -> packs.assertRuleEditable(tenant, child.id(), "1.5.0", "quy_tac_rieng"));

    JsonNode tenantPacks = body(perform(get("/api/v1/policies/packs").param("origin", "TENANT")));
    assertThat(tenantPacks.get("items"))
        .singleElement()
        .satisfies(
            p -> assertThat(p.get("parentPackId").asText()).isEqualTo(root.pack().id().toString()));
  }

  @Test
  void statusChangesUseOptimisticLockAndBaselineGuards() throws Exception {
    VersionView root = seedRoot();
    UUID packId = root.pack().id();
    var testing =
        TenantContext.call(
            tenant,
            () ->
                packs.changeStatus(
                    tenant, AUTHOR, packId, "1.4.0", 0, PolicyVersionStatus.DANG_KIEM_THU));
    assertThat(testing.rowVersion()).isEqualTo(1);
    String stored =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT status FROM policy.policy_pack_version WHERE id = ?")
                    .param(testing.id())
                    .query(String.class)
                    .single());
    assertThat(stored).isEqualTo("DANG_KIEM_THU");
    assertThat(AUDITS)
        .contains("CHUYEN_TRANG_THAI_POLICY_PACK_DANG_KIEM_THU:khoi-chinh-quyen/nd335@1.4.0:1");
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant,
                    () ->
                        packs.changeStatus(
                            tenant, AUTHOR, packId, "1.4.0", 1, PolicyVersionStatus.DAT_KIEM_THU)))
        .hasMessage("POLICY-409-NOT-TESTED");
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant,
                    () ->
                        packs.changeStatus(
                            tenant, AUTHOR, packId, "1.4.0", 0, PolicyVersionStatus.NHAP)))
        .isInstanceOf(vn.viettel.okrkpi.shared.error.VersionConflictException.class);
    assertThat(
            TenantContext.call(
                    tenant,
                    () ->
                        packs.changeStatus(
                            tenant, AUTHOR, packId, "1.4.0", 1, PolicyVersionStatus.NHAP))
                .status())
        .isEqualTo(PolicyVersionStatus.NHAP);
  }
}
