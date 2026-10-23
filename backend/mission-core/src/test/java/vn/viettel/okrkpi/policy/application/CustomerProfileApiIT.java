package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedProfile;
import vn.viettel.okrkpi.policy.application.CustomerProfileService.CreateProfile;
import vn.viettel.okrkpi.policy.application.CustomerProfileService.VersionView;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Approver;
import vn.viettel.okrkpi.policy.domain.ParamConstraint;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.infrastructure.persistence.JdbcPolicyPackRepository;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-05-12 trên PostgreSQL thật: vòng đời hồ sơ tập khách hàng qua HTTP khớp
 * {@code policies.yaml}, hồ sơ trỏ tới gói chưa phát hành bị chặn khi gửi duyệt, ký duyệt hai người
 * có bước lên; cùng một kỳ ghim hai hồ sơ khác nhau cho hai tenant và {@code PolicyQueryApi} trả
 * đúng cờ điểm cắm; khung tham số DC-02 từ chối hệ số nhóm 1,6 và nhận 1,2 với khung [1,0; 1,5]. Dữ
 * liệu hồ sơ trong kiểm thử là giả lập trung tính, không phải hồ sơ khách hàng thật.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import(PolicyApprovalApiIT.CallerConfig.class)
@DirtiesContext
class CustomerProfileApiIT extends ContractConformanceTest {

  private static final String BASE = "/api/v1/policies/customer-profiles";

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private CustomerProfileService profiles;
  @Autowired private PolicyQueryApi query;
  @Autowired private JdbcPolicyPackRepository packRepo;

  @Autowired
  @Qualifier("policyDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("policyTransactionManager") private PlatformTransactionManager tm;

  private final UUID author = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.POLICIES;
  }

  @AfterEach
  void resetCaller() {
    PolicyApprovalApiIT.CallerConfig.current = null;
    PolicyApprovalApiIT.CallerConfig.acr = null;
    PolicyApprovalApiIT.CallerConfig.authTime = null;
  }

  private static void as(UUID user, boolean aal2) {
    PolicyApprovalApiIT.CallerConfig.current = user;
    PolicyApprovalApiIT.CallerConfig.acr = aal2 ? "aal2" : null;
    PolicyApprovalApiIT.CallerConfig.authTime = aal2 ? Instant.now().getEpochSecond() - 30 : null;
  }

  private MvcResult perform(TenantId tenant, MockHttpServletRequestBuilder request)
      throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private <T> T sql(TenantId tenant, Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(jdbc)));
  }

  private <T> T in(TenantId tenant, Supplier<T> work) throws Exception {
    return TenantContext.call(tenant, work::get);
  }

  /** Gói và một phiên bản ghi thẳng ở CSDL với trạng thái cho trước. */
  private UUID[] seedPack(
      TenantId tenant,
      String code,
      String version,
      String status,
      UUID parentPackId,
      UUID packId,
      UUID sourceVersionId,
      String paramsJson)
      throws Exception {
    UUID pack = packId == null ? UUID.randomUUID() : packId;
    UUID versionId = UUID.randomUUID();
    sql(
        tenant,
        jdbc -> {
          if (packId == null) {
            jdbc.sql(
                    "INSERT INTO policy.policy_pack (id, tenant_id, created_by, updated_by, code,"
                        + " name, sector, flow, origin, parent_pack_id, legal_basis)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'KHOI_DANG', 'DANG', ?, ?, 'Giả lập')")
                .params(
                    pack,
                    tenant.value(),
                    author,
                    author,
                    code,
                    "Bộ quy tắc " + code,
                    parentPackId == null ? "GOC_TOAN_CUC" : "TENANT",
                    parentPackId)
                .update();
          }
          jdbc.sql(
                  "INSERT INTO policy.policy_pack_version (id, tenant_id, created_by, updated_by,"
                      + " policy_pack_id, version, status, manifest, content_hash,"
                      + " golden_pass_rate, signature, approved_by, approved_at, published_at,"
                      + " source_version_id, change_note)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'NHAP', ?::jsonb, decode('a1b2', 'hex'), 1,"
                      + " NULL, NULL, NULL, NULL, ?, 'Giả lập')")
              .params(
                  versionId,
                  tenant.value(),
                  author,
                  author,
                  pack,
                  version,
                  "{\"params\":" + paramsJson + "}",
                  sourceVersionId)
              .update();
          return null;
        });
    setStatus(tenant, versionId, status);
    return new UUID[] {pack, versionId};
  }

  private void setStatus(TenantId tenant, UUID versionId, String status) throws Exception {
    sql(
        tenant,
        jdbc ->
            "DA_PHAT_HANH".equals(status)
                ? jdbc.sql(
                        "UPDATE policy.policy_pack_version SET status = 'DA_PHAT_HANH',"
                            + " signature = decode('01', 'hex'), approved_by = ?,"
                            + " approved_at = now(), published_at = now()"
                            + " WHERE tenant_id = ? AND id = ?")
                    .params(UUID.randomUUID(), tenant.value(), versionId)
                    .update()
                : jdbc.sql(
                        "UPDATE policy.policy_pack_version SET status = ?"
                            + " WHERE tenant_id = ? AND id = ?")
                    .params(status, tenant.value(), versionId)
                    .update());
  }

  private static String manifest(String packCode, String packVersion, String pluginPoints) {
    return "{\"components\":{\"policyPack\":{\"code\":\""
        + packCode
        + "\",\"version\":\""
        + packVersion
        + "\",\"flow\":\"DANG\"},\"catalog\":null,\"uiPack\":null},\"pluginPoints\":"
        + pluginPoints
        + "}";
  }

  private MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String etag, String content) {
    b.header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(content == null ? "{}" : content);
    return etag == null ? b : b.header("If-Match", etag);
  }

  private String etag(TenantId tenant, String path) throws Exception {
    return perform(tenant, get(path)).getResponse().getHeader("ETag");
  }

  @Test
  void lifecycleBlocksUnpublishedComponentAndConformsToContract() throws Exception {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    UUID[] pack = seedPack(tenant, "goi-mau/diem", "1.0.0", "DAT_KIEM_THU", null, null, null, "{}");

    as(author, false);
    String create =
        "{\"code\":\"mau/ho-so-a\",\"name\":\"Hồ sơ mẫu A (giả lập)\",\"isSimulated\":true,"
            + "\"legalBasis\":[\"Dự thảo quy định mẫu\"],\"tenantTags\":[\"nhom-mau\"]}";
    MvcResult created = perform(tenant, write(post(BASE), null, create));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/policies/customer-profiles", PathItem.HttpMethod.POST);
    String profileId = body(created).get("id").asText();
    String profilePath = BASE + "/" + profileId;
    MvcResult duplicate = perform(tenant, write(post(BASE), null, create));
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(duplicate).get("code").asText()).isEqualTo("POLICY-422-PROFILE-CODE-EXISTS");

    MvcResult badRef =
        perform(
            tenant,
            write(
                post(profilePath + "/versions"),
                null,
                "{\"version\":\"0.1.0\",\"changeNote\":\"Phiên bản đầu\",\"manifest\":"
                    + manifest("goi-mau/khong-co", "1.0.0", "{}")
                    + "}"));
    assertThat(badRef.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(badRef).get("code").asText()).isEqualTo("POLICY-422-PROFILE-REF");

    MvcResult draft =
        perform(
            tenant,
            write(
                post(profilePath + "/versions"),
                null,
                "{\"version\":\"0.1.0\",\"changeNote\":\"Phiên bản đầu\",\"manifest\":"
                    + manifest(
                        "goi-mau/diem",
                        "1.0.0",
                        "{\"boundedInheritance\":true,\"workItemScore\":true}")
                    + "}"));
    assertThat(draft.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(
        draft, "/policies/customer-profiles/{profileId}/versions", PathItem.HttpMethod.POST);
    assertThat(body(draft).get("status").asText()).isEqualTo("NHAP");
    String v1 = profilePath + "/versions/0.1.0";

    MvcResult badFrame =
        perform(
            tenant,
            write(
                put(v1 + "/param-constraints"),
                etag(tenant, v1),
                "{\"items\":[{\"paramKey\":\"tham_so.he_so_nhom\",\"canToggle\":false,"
                    + "\"canChangeLevels\":false,\"minValue\":2,\"maxValue\":1,"
                    + "\"tightenDirection\":\"KHONG\",\"allowedScopes\":[]}]}"));
    assertThat(badFrame.getResponse().getStatus()).isEqualTo(422);
    MvcResult frames =
        perform(
            tenant,
            write(
                put(v1 + "/param-constraints"),
                etag(tenant, v1),
                "{\"items\":[{\"paramKey\":\"tham_so.he_so_nhom\",\"canToggle\":false,"
                    + "\"canChangeLevels\":false,\"minValue\":1.0,\"maxValue\":1.5,"
                    + "\"tightenDirection\":\"KHONG\",\"allowedScopes\":[\"KHOI\"]}]}"));
    assertThat(frames.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        frames,
        "/policies/customer-profiles/{profileId}/versions/{v}/param-constraints",
        PathItem.HttpMethod.PUT);
    MvcResult listedFrames = perform(tenant, get(v1 + "/param-constraints"));
    assertResponseConforms(
        listedFrames,
        "/policies/customer-profiles/{profileId}/versions/{v}/param-constraints",
        PathItem.HttpMethod.GET);
    assertThat(body(listedFrames).get("items").get(0).get("maxValue").decimalValue())
        .isEqualByComparingTo("1.5");

    MvcResult validated = perform(tenant, write(post(v1 + "/validate"), etag(tenant, v1), null));
    assertThat(validated.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(
        validated,
        "/policies/customer-profiles/{profileId}/versions/{v}/validate",
        PathItem.HttpMethod.POST);
    assertThat(validated.getResponse().getHeader("Location")).contains("/jobs/");
    MvcResult tested = perform(tenant, get(v1));
    assertResponseConforms(
        tested, "/policies/customer-profiles/{profileId}/versions/{v}", PathItem.HttpMethod.GET);
    assertThat(body(tested).get("status").asText()).isEqualTo("DAT_KIEM_THU");
    assertThat(body(tested).get("contentHash").asText()).hasSize(64);
    assertThat(body(tested).get("paramConstraintCount").asInt()).isEqualTo(1);

    MvcResult unpublished = perform(tenant, write(post(v1 + "/submit"), etag(tenant, v1), null));
    assertThat(unpublished.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(unpublished).get("code").asText()).isEqualTo("POLICY-422-PROFILE-REF");
    assertThat(body(perform(tenant, get(v1))).get("status").asText()).isEqualTo("DAT_KIEM_THU");

    setStatus(tenant, pack[1], "DA_PHAT_HANH");
    MvcResult submitted = perform(tenant, write(post(v1 + "/submit"), etag(tenant, v1), null));
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        submitted,
        "/policies/customer-profiles/{profileId}/versions/{v}/submit",
        PathItem.HttpMethod.POST);
    assertThat(body(submitted).get("status").asText()).isEqualTo("CHO_PHE_DUYET");

    MvcResult frozen =
        perform(
            tenant,
            write(
                put(v1),
                etag(tenant, v1),
                "{\"manifest\":" + manifest("goi-mau/diem", "1.0.0", "{}") + "}"));
    assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(frozen).get("code").asText()).isEqualTo("POLICY-409-VERSION-FROZEN");

    as(author, true);
    MvcResult self = perform(tenant, write(post(v1 + "/approve"), etag(tenant, v1), null));
    assertThat(self.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(self).get("code").asText()).isEqualTo("POLICY-403-SAME-AUTHOR");
    as(approver, false);
    assertThat(
            perform(tenant, write(post(v1 + "/approve"), etag(tenant, v1), null))
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    as(approver, true);
    MvcResult approved =
        perform(
            tenant,
            write(post(v1 + "/approve"), etag(tenant, v1), "{\"comment\":\"Đồng ý phát hành\"}"));
    assertThat(approved.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(
        approved,
        "/policies/customer-profiles/{profileId}/versions/{v}/approve",
        PathItem.HttpMethod.POST);
    assertThat(body(approved).get("type").asText()).isEqualTo("POLICY_PUBLISH");
    JsonNode published = body(perform(tenant, get(v1)));
    assertThat(published.get("status").asText()).isEqualTo("DA_PHAT_HANH");
    assertThat(published.get("approvedBy").get("id").asText()).isEqualTo(approver.toString());
    assertThatThrownBy(
            () ->
                sql(
                    tenant,
                    jdbc ->
                        jdbc.sql(
                                "UPDATE policy.customer_profile_version SET manifest = '{}'"
                                    + " WHERE tenant_id = ? AND version = '0.1.0'")
                            .params(tenant.value())
                            .update()))
        .hasMessageContaining("đóng băng");

    as(author, false);
    MvcResult list = perform(tenant, get(BASE).param("limit", "10"));
    assertResponseConforms(list, "/policies/customer-profiles", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items").get(0).get("versions").get(0).get("status").asText())
        .isEqualTo("DA_PHAT_HANH");
    assertResponseConforms(
        perform(tenant, get(profilePath)),
        "/policies/customer-profiles/{profileId}",
        PathItem.HttpMethod.GET);
    assertResponseConforms(
        perform(tenant, get(profilePath + "/versions")),
        "/policies/customer-profiles/{profileId}/versions",
        PathItem.HttpMethod.GET);

    MvcResult next =
        perform(
            tenant,
            write(
                post(profilePath + "/versions"),
                null,
                "{\"baseVersion\":\"0.1.0\",\"changeNote\":\"Sửa khung tham số\"}"));
    assertThat(next.getResponse().getStatus()).isEqualTo(201);
    assertThat(body(next).get("version").asText()).isEqualTo("0.2.0");
    assertThat(body(next).get("paramConstraintCount").asInt()).isEqualTo(1);
    String v2 = profilePath + "/versions/0.2.0";
    perform(tenant, write(post(v2 + "/validate"), etag(tenant, v2), null));
    perform(tenant, write(post(v2 + "/submit"), etag(tenant, v2), null));
    as(approver, false);
    MvcResult rejected =
        perform(
            tenant,
            write(
                post(v2 + "/reject"),
                etag(tenant, v2),
                "{\"reason\":\"Cần rà lại khung hệ số nhóm theo văn bản\"}"));
    assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        rejected,
        "/policies/customer-profiles/{profileId}/versions/{v}/reject",
        PathItem.HttpMethod.POST);
    assertThat(body(rejected).get("status").asText()).isEqualTo("NHAP");
  }

  /** Hồ sơ đã phát hành qua dịch vụ (người soạn và người duyệt khác nhau). */
  private VersionView publishedProfile(
      TenantId tenant, String code, String packCode, String pluginPoints) throws Exception {
    UUID profileId =
        in(
                tenant,
                () ->
                    profiles.newProfile(
                        tenant,
                        author,
                        new CreateProfile(code, "Hồ sơ " + code, true, List.of(), List.of())))
            .profile()
            .id();
    Map<String, Object> manifest =
        json.readValue(manifest(packCode, "1.0.0", pluginPoints), Map.class);
    VersionView created =
        in(
            tenant,
            () ->
                profiles.newVersion(
                    tenant, author, profileId, null, "1.0.0", "Phát hành đầu", manifest));
    VersionView tested =
        in(
            tenant,
            () ->
                profiles.validate(
                    tenant, author, profileId, "1.0.0", created.version().rowVersion()));
    VersionView pending =
        in(
            tenant,
            () ->
                profiles.submit(
                    tenant, author, profileId, "1.0.0", tested.version().rowVersion(), null));
    return in(
        tenant,
        () ->
            profiles.approve(
                tenant,
                new Approver(approver, "aal2", Instant.now().getEpochSecond() - 30),
                profileId,
                "1.0.0",
                pending.version().rowVersion(),
                null,
                null));
  }

  private UUID binding(TenantId tenant, UUID versionId, UUID period) throws Exception {
    UUID id = UUID.randomUUID();
    sql(
        tenant,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO policy.policy_binding (id, tenant_id, created_by, updated_by,"
                        + " policy_pack_version_id, period_id, flow) VALUES (?, ?, ?, ?, ?, ?,"
                        + " 'DANG')")
                .params(id, tenant.value(), author, author, versionId, period)
                .update());
    return id;
  }

  @Test
  void samePeriodPinsDifferentProfilesForTwoTenants() throws Exception {
    TenantId first = TenantId.of(UUID.randomUUID());
    TenantId second = TenantId.of(UUID.randomUUID());
    UUID period = UUID.randomUUID();
    UUID[] packA =
        seedPack(first, "goi-mau/chung", "1.0.0", "DA_PHAT_HANH", null, null, null, "{}");
    UUID[] packB =
        seedPack(second, "goi-mau/chung", "1.0.0", "DA_PHAT_HANH", null, null, null, "{}");
    VersionView a =
        publishedProfile(first, "mau/nhom-mot", "goi-mau/chung", "{\"workItemScore\":true}");
    VersionView b =
        publishedProfile(
            second,
            "mau/nhom-hai",
            "goi-mau/chung",
            "{\"classificationChain\":true,\"assignmentMode\":\"MOT_NGUOI_MOT_VIEC\"}");
    UUID bindingA = binding(first, packA[1], period);
    UUID bindingB = binding(second, packB[1], period);

    assertThat(
            in(first, () -> query.customerProfile(first, PeriodId.of(period), "DANG", null, null)))
        .as("chưa ghim hồ sơ thì điểm cắm giữ mặc định")
        .isEmpty();

    in(
        first,
        () -> {
          profiles.pinProfile(first, author, bindingA, a.version().id());
          return null;
        });
    in(
        second,
        () -> {
          profiles.pinProfile(second, author, bindingB, b.version().id());
          return null;
        });

    ResolvedProfile ra =
        in(first, () -> query.customerProfile(first, PeriodId.of(period), "DANG", null, null))
            .orElseThrow();
    ResolvedProfile rb =
        in(second, () -> query.customerProfile(second, PeriodId.of(period), "DANG", null, null))
            .orElseThrow();
    assertThat(ra.code()).isEqualTo("mau/nhom-mot");
    assertThat(ra.versionId()).isEqualTo(a.version().id());
    assertThat(ra.pluginPoints().workItemScore()).isTrue();
    assertThat(ra.pluginPoints().assignmentMode()).isEqualTo("CHU_TRI_PHOI_HOP");
    assertThat(rb.code()).isEqualTo("mau/nhom-hai");
    assertThat(rb.pluginPoints().workItemScore()).isFalse();
    assertThat(rb.pluginPoints().classificationChain()).isTrue();
    assertThat(rb.pluginPoints().assignmentMode()).isEqualTo("MOT_NGUOI_MOT_VIEC");
    assertThat(ra.components()).containsKey("policyPack");

    MvcResult resolved =
        perform(
            first,
            get("/api/v1/policies/resolve")
                .param("tenant", first.value().toString())
                .param("period", period.toString())
                .param("flow", "DANG"));
    assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(resolved, "/policies/resolve", PathItem.HttpMethod.GET);
    assertThat(body(resolved).get("customerProfileVersionId").asText())
        .isEqualTo(a.version().id().toString());

    VersionView draft =
        in(
            first,
            () ->
                profiles.newVersion(
                    first, author, a.version().profileId(), "1.0.0", null, "Bản nháp", null));
    assertThatThrownBy(
            () ->
                in(
                    first,
                    () -> {
                      profiles.pinProfile(first, author, bindingA, draft.version().id());
                      return null;
                    }))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("POLICY-422-PROFILE-REF"));
  }

  @Test
  void boundedInheritanceRejectsOutOfFrameChildPack() throws Exception {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    UUID[] parent =
        seedPack(
            tenant,
            "goi-mau/cap-tren",
            "1.0.0",
            "DA_PHAT_HANH",
            null,
            null,
            null,
            "{\"he_so_nhom\":{\"kieu\":\"so\",\"mac_dinh\":1.0}}");
    VersionView profile =
        publishedProfile(
            tenant, "mau/ke-thua", "goi-mau/cap-tren", "{\"boundedInheritance\":true}");
    assertThat(profile.version().status().name()).isEqualTo("DA_PHAT_HANH");
    // Khung được khai trên phiên bản đã phát hành của hồ sơ qua bản nháp kế tiếp rồi phát hành lại
    // thì dài; ghi thẳng khung vào phiên bản đã phát hành (bảng khung không đóng băng ở CSDL).
    sql(
        tenant,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO policy.param_constraint (id, tenant_id, created_by, updated_by,"
                        + " customer_profile_version_id, param_key, min_value, max_value,"
                        + " tighten_direction, allowed_scopes) VALUES (?, ?, ?, ?, ?,"
                        + " 'tham_so.he_so_nhom', 1.0, 1.5, 'KHONG', '[\"KHOI\"]'::jsonb)")
                .params(UUID.randomUUID(), tenant.value(), author, author, profile.version().id())
                .update());

    UUID[] child =
        seedPack(
            tenant,
            "goi-mau/cap-duoi",
            "1.0.0",
            "DAT_KIEM_THU",
            parent[0],
            null,
            parent[1],
            "{\"he_so_nhom\":{\"kieu\":\"so\",\"mac_dinh\":1.6}}");
    UUID[] childOk =
        seedPack(
            tenant,
            "goi-mau/cap-duoi",
            "1.1.0",
            "DAT_KIEM_THU",
            parent[0],
            child[0],
            parent[1],
            "{\"he_so_nhom\":{\"kieu\":\"so\",\"mac_dinh\":1.2}}");
    PolicyPack childPack = sql(tenant, jdbc -> packRepo.findPack(tenant, child[0]).orElseThrow());
    PolicyPackVersion outOfFrame =
        sql(tenant, jdbc -> packRepo.findVersionById(tenant, child[1]).orElseThrow());
    PolicyPackVersion inFrame =
        sql(tenant, jdbc -> packRepo.findVersionById(tenant, childOk[1]).orElseThrow());

    assertThatThrownBy(
            () ->
                in(
                    tenant,
                    () -> {
                      profiles.checkInheritance(tenant, childPack, outOfFrame);
                      return null;
                    }))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("POLICY-422-PARAM-CONSTRAINT");
              ProblemFieldError error = e.errors().get(0);
              assertThat(error.field()).isEqualTo("tham_so.he_so_nhom");
              assertThat(error.message()).contains("1,6").contains("[1; 1,5]");
              assertThat(error.rule()).isEqualTo("DC-02");
            });
    in(
        tenant,
        () -> {
          profiles.checkInheritance(tenant, childPack, inFrame);
          return null;
        });
    List<ParamConstraint> frames =
        in(tenant, () -> profiles.constraints(tenant, profile.version().profileId(), "1.0.0"));
    assertThat(frames)
        .singleElement()
        .extracting(ParamConstraint::paramKey)
        .isEqualTo("tham_so.he_so_nhom");
  }
}
