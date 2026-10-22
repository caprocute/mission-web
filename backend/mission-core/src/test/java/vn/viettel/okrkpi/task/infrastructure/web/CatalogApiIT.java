package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.application.NewPeriod;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.catalog.CatalogService;

/**
 * Kiểm thử tích hợp CORE-02-03 trên PostgreSQL thật: tám thao tác {@code /catalog} khớp {@code
 * tasks.yaml}; kế thừa danh mục mẫu (chỉ đọc ở danh mục con); BR-04-14 trả 422; BR-04-13 lưu thay
 * đổi của mục đã dùng ở kỳ đã khoá vào phiên bản nháp mới ({@code movedToNewVersion}); nhân bản giữ
 * đủ mục và mã; áp dụng phiên bản chuyển bản cũ sang hết hiệu lực; chặn vòng kế thừa.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "mission.config.period-opening.enabled=false"})
@AutoConfigureMockMvc(addFilters = false)
class CatalogApiIT extends ContractConformanceTest {

  private static final String BASE = "/api/v1/catalog";
  private static final int BUCKET = 20271;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PeriodConfigService periodConfig;
  @Autowired private CatalogService catalogs;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = UUID.randomUUID();
  private final UUID position = UUID.randomUUID();
  private final UUID template = UUID.randomUUID();
  private final UUID catalog = UUID.randomUUID();
  private final UUID usedItem = UUID.randomUUID();
  private final UUID freeItem = UUID.randomUUID();
  private final UUID templateItem = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.TASKS;
  }

  @Test
  void catalogLifecycleConformsToContract() throws Exception {
    seed(lockedPeriod());

    // Đọc: phiên bản đang áp dụng đứng trước; mục kế thừa nhìn thấy ở danh mục con.
    MvcResult list = perform(get(BASE).param("limit", "10"));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(list, "/catalog", PathItem.HttpMethod.GET);
    JsonNode first = body(list).get("items").get(0);
    assertThat(first.get("status").asText()).isEqualTo("AP_DUNG");
    assertThat(body(list).get("items")).hasSize(2);

    MvcResult items = perform(get(BASE + "/" + catalog + "/items"));
    assertResponseConforms(items, "/catalog/{catalogId}/items", PathItem.HttpMethod.GET);
    JsonNode page = body(items);
    assertThat(page.get("total").asInt()).isEqualTo(3);
    assertThat(page.get("positionCount").asInt()).isEqualTo(1);
    assertThat(page.get("items").get(0).get("code").asText()).isEqualTo("TC-DV-01");
    assertThat(page.get("items").get(0).get("usedInLockedPeriod").asBoolean()).isTrue();
    assertThat(page.get("items").get(0).get("usageCount").asInt()).isEqualTo(1);
    assertThat(page.get("items").get(2).get("catalogId").asText()).isEqualTo(template.toString());
    JsonNode paged = body(perform(get(BASE + "/" + catalog + "/items").param("limit", "2")));
    assertThat(paged.get("items")).hasSize(2);
    JsonNode rest =
        body(
            perform(
                get(BASE + "/" + catalog + "/items")
                    .param("limit", "2")
                    .param("cursor", paged.get("nextCursor").asText())));
    assertThat(rest.get("items")).hasSize(1);
    assertThat(rest.get("nextCursor").isNull()).isTrue();
    assertThat(
            body(perform(get(BASE + "/" + catalog + "/items").param("q", "BAO CAO")))
                .get("total")
                .asInt())
        .isEqualTo(1);

    MvcResult positions = perform(get(BASE + "/" + catalog + "/positions"));
    assertResponseConforms(positions, "/catalog/{catalogId}/positions", PathItem.HttpMethod.GET);
    assertThat(body(positions).get("items").get(0).get("name").asText())
        .isEqualTo("Chuyên viên công tác đảng viên");

    MvcResult applicable = perform(get(BASE + "/items").param("q", "ho so"));
    assertResponseConforms(applicable, "/catalog/items", PathItem.HttpMethod.GET);
    assertThat(body(applicable).get("items")).hasSize(2);
    MvcResult mine = perform(get(BASE + "/items").param("applicable", "me"));
    assertThat(body(mine).get("items")).isEmpty();
    assertThat(perform(get(BASE + "/" + UUID.randomUUID() + "/items")).getResponse().getStatus())
        .isEqualTo(404);

    // BR-04-14: mức khó 0, điểm 101, từ mơ hồ trả 422.
    assertThat(status(add(catalog, input(null, "Lập hồ sơ", 0, "30")))).isEqualTo(422);
    MvcResult score = perform(add(catalog, input(null, "Lập hồ sơ", 3, "101")));
    assertThat(score.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(score).get("code").asText()).isEqualTo("COMMON-422-VALIDATION");
    assertThat(status(add(catalog, input(null, "Tăng cường lập hồ sơ", 3, "30")))).isEqualTo(422);

    // Thêm vào phiên bản đang áp dụng: ghi vào nháp v2, mã tự sinh, gửi lại cùng khoá trả cùng mục.
    String key = UUID.randomUUID().toString();
    MvcResult added = perform(add(catalog, input(null, "Lập hồ sơ khen thưởng", 3, "15"), key));
    assertThat(added.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(added, "/catalog/{catalogId}/items", PathItem.HttpMethod.POST);
    JsonNode saved = body(added);
    assertThat(saved.get("movedToNewVersion").asBoolean()).isTrue();
    assertThat(saved.get("item").get("code").asText()).isEqualTo("TC-DV-03");
    assertThat(saved.get("catalog").get("versionNo").asInt()).isEqualTo(2);
    assertThat(saved.get("catalog").get("status").asText()).isEqualTo("NHAP");
    UUID draft = UUID.fromString(saved.get("catalog").get("id").asText());
    assertThat(
            body(perform(add(catalog, input(null, "Lập hồ sơ khen thưởng", 3, "15"), key)))
                .get("item")
                .get("id"))
        .isEqualTo(saved.get("item").get("id"));

    // BR-04-13: mục đã dùng ở kỳ đã khoá được lưu ở phiên bản nháp, kỳ cũ giữ nguyên.
    String change = input("TC-DV-01", "Báo cáo chuyên đề công tác tổ chức", 4, "35");
    assertThat(status(update(catalog, usedItem, null, change))).isEqualTo(428);
    MvcResult stale = perform(update(catalog, usedItem, "\"5\"", change));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(stale).get("code").asText()).isEqualTo("TASK-409-VERSION-CONFLICT");
    MvcResult moved = perform(update(catalog, usedItem, "\"0\"", change));
    assertThat(moved.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(moved, "/catalog/{catalogId}/items/{itemId}", PathItem.HttpMethod.PUT);
    JsonNode movedBody = body(moved);
    assertThat(movedBody.get("movedToNewVersion").asBoolean()).isTrue();
    assertThat(movedBody.get("item").get("catalogId").asText()).isEqualTo(draft.toString());
    assertThat(movedBody.get("item").get("baseScore").decimalValue()).isEqualByComparingTo("35");
    JsonNode original = body(perform(get(BASE + "/" + catalog + "/items"))).get("items").get(0);
    assertThat(original.get("baseScore").decimalValue()).isEqualByComparingTo("30");

    // Mục chưa dùng ở kỳ khoá: sửa tại chỗ. Mục kế thừa: chỉ đọc.
    MvcResult inPlace =
        perform(
            update(catalog, freeItem, "\"0\"", input(null, "Thẩm định hồ sơ đảng viên", 2, "12")));
    assertThat(body(inPlace).get("movedToNewVersion").asBoolean()).isFalse();
    assertThat(inPlace.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    MvcResult inherited =
        perform(update(catalog, templateItem, "\"0\"", input(null, "Thẩm định hồ sơ", 2, "12")));
    assertThat(inherited.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(inherited).get("code").asText()).isEqualTo("TASK-409-NOT-DRAFT");

    // Đã có nháp thì không nhân bản thêm; áp dụng nháp chuyển bản cũ sang hết hiệu lực.
    assertThat(status(post(BASE + "/" + catalog + "/versions").header("Idempotency-Key", k())))
        .isEqualTo(409);
    assertThat(
            status(
                post(BASE + "/" + draft + "/activate")
                    .header("Idempotency-Key", k())
                    .header("If-Match", "\"3\"")))
        .isEqualTo(409);
    MvcResult activated =
        perform(
            post(BASE + "/" + draft + "/activate")
                .header("Idempotency-Key", k())
                .header("If-Match", "\"0\""));
    assertThat(activated.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(activated, "/catalog/{catalogId}/activate", PathItem.HttpMethod.POST);
    assertThat(body(activated).get("status").asText()).isEqualTo("AP_DUNG");
    assertThat(body(activated).get("itemCount").asInt()).isEqualTo(4);
    MvcResult retired =
        perform(update(catalog, freeItem, "\"1\"", input(null, "Thẩm định", 2, "12")));
    assertThat(retired.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(retired).get("code").asText()).isEqualTo("TASK-409-NOT-DRAFT");

    // Nhân bản: phiên bản 3 ở nháp, đủ mục, mã giữ nguyên.
    MvcResult cloned =
        perform(post(BASE + "/" + draft + "/versions").header("Idempotency-Key", k()));
    assertThat(cloned.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(cloned, "/catalog/{catalogId}/versions", PathItem.HttpMethod.POST);
    JsonNode v3 = body(cloned);
    assertThat(v3.get("versionNo").asInt()).isEqualTo(3);
    assertThat(v3.get("status").asText()).isEqualTo("NHAP");
    assertThat(codes(UUID.fromString(v3.get("id").asText()))).isEqualTo(codes(draft));
    assertThat(codes(draft)).isEqualTo("TC-DV-01,TC-DV-02,TC-DV-03,TC-MT-01,");

    // Chặn vòng kế thừa: danh mục mẫu không được kế thừa từ danh mục con của nó.
    assertThatThrownBy(() -> as(() -> catalogs.inheritFrom(tenant, actor, template, catalog)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION"));
  }

  private String codes(UUID catalogId) throws Exception {
    JsonNode items = body(perform(get(BASE + "/" + catalogId + "/items"))).get("items");
    StringBuilder out = new StringBuilder();
    items.forEach(i -> out.append(i.get("code").asText()).append(','));
    return out.toString();
  }

  private PeriodId lockedPeriod() throws Exception {
    PeriodId period =
        as(
            () ->
                periodConfig
                    .create(
                        tenant,
                        actor,
                        new NewPeriod(
                            "2027-Q1",
                            "Quý I/2027",
                            PeriodKind.QUY,
                            PeriodFlow.CA_HAI,
                            null,
                            LocalDate.parse("2027-01-01"),
                            LocalDate.parse("2027-03-31"),
                            null,
                            null,
                            null))
                    .period()
                    .id());
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .executeWithoutResult(
                    s ->
                        periodConfig.transition(
                            tenant,
                            period,
                            PeriodState.DANG_KHOA,
                            UUID.randomUUID(),
                            actor,
                            null,
                            null)));
    return period;
  }

  /** Danh mục mẫu cấp trên (1 mục) và danh mục con đang áp dụng (2 mục, một mục đã giao việc). */
  private void seed(PeriodId period) throws SQLException {
    PostgreSQLContainer<?> pg = MissionCoreTestDatabase.container();
    try (Connection svc =
            DriverManager.getConnection(
                pg.getJdbcUrl(), "svc_task", MissionCoreTestDatabase.TEST_DB_PASSWORD);
        PreparedStatement ps =
            svc.prepareStatement("SELECT task.fn_create_period_partitions('task.task', ?)")) {
      ps.setInt(1, BUCKET);
      ps.executeQuery().close();
    }
    try (Connection admin =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      catalogRow(admin, template, "MAU-TW", "Danh mục mẫu Ban Tổ chức Trung ương", null, null);
      catalogRow(
          admin, catalog, "CV-DANG-VIEN", "Chuyên viên công tác đảng viên", position, template);
      itemRow(
          admin, templateItem, template, "TC-MT-01", "Thẩm định hồ sơ kết nạp đảng viên", 3, 20);
      itemRow(admin, usedItem, catalog, "TC-DV-01", "Báo cáo chuyên đề công tác tổ chức", 4, 30);
      itemRow(admin, freeItem, catalog, "TC-DV-02", "Rà soát hồ sơ đảng viên", 2, 10);
      try (PreparedStatement ps =
          admin.prepareStatement(
              """
              INSERT INTO task.task
                (id, tenant_id, created_by, updated_by, period_id, period_bucket, code, title,
                 task_group, source, assigner_id, due_date, unit, dup_fingerprint,
                 state_machine_version, catalog_item_id)
              VALUES (?, ?, ?, ?, ?, ?, 'NV-0001', 'Báo cáo chuyên đề quý I', 'THUONG_XUYEN',
                      'KE_HOACH', ?, DATE '2027-03-20', 'Báo cáo', '\\x01', 'v1', ?)
              """)) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, tenant.value());
        ps.setObject(3, actor);
        ps.setObject(4, actor);
        ps.setObject(5, period.value());
        ps.setInt(6, BUCKET);
        ps.setObject(7, actor);
        ps.setObject(8, usedItem);
        ps.executeUpdate();
      }
    }
  }

  private void catalogRow(Connection c, UUID id, String code, String name, UUID pos, UUID parent)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            INSERT INTO task.work_catalog
              (id, tenant_id, created_by, updated_by, code, name, scope, position_id, version,
               version_no, source, status, parent_catalog_id)
            VALUES (?, ?, ?, ?, ?, ?, 'VI_TRI', ?, 1, 1, 'TU_TAO', 'HIEU_LUC', ?)
            """)) {
      ps.setObject(1, id);
      ps.setObject(2, tenant.value());
      ps.setObject(3, actor);
      ps.setObject(4, actor);
      ps.setString(5, code);
      ps.setString(6, name);
      ps.setObject(7, pos);
      ps.setObject(8, parent);
      ps.executeUpdate();
    }
  }

  private void itemRow(
      Connection c, UUID id, UUID catalogId, String code, String name, int difficulty, int score)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            INSERT INTO task.catalog_item
              (id, tenant_id, created_by, updated_by, work_catalog_id, code, name, product_name,
               unit, difficulty, standard_score, base_score, quality_standard,
               evidence_requirement, task_group, default_group, standard_days)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'Hồ sơ', 'Bộ', ?, ?, ?, 'Đủ thành phần',
                    'Biên bản', 'THUONG_XUYEN', 'THUONG_XUYEN', 10)
            """)) {
      ps.setObject(1, id);
      ps.setObject(2, tenant.value());
      ps.setObject(3, actor);
      ps.setObject(4, actor);
      ps.setObject(5, catalogId);
      ps.setString(6, code);
      ps.setString(7, name);
      ps.setInt(8, difficulty);
      ps.setInt(9, score);
      ps.setInt(10, score);
      ps.executeUpdate();
    }
  }

  private static String input(String code, String name, int difficulty, String score) {
    return """
        {%s"name":"%s","productName":"Hồ sơ","unit":"Bộ","difficulty":%d,"baseScore":%s,
         "qualityCriteria":"Đủ thành phần, đúng thể thức","evidenceRequired":"Biên bản",
         "defaultGroup":"THUONG_XUYEN","standardDays":10}
        """
        .formatted(code == null ? "" : "\"code\":\"" + code + "\",", name, difficulty, score);
  }

  private MockHttpServletRequestBuilder add(UUID catalogId, String body) {
    return add(catalogId, body, k());
  }

  private MockHttpServletRequestBuilder add(UUID catalogId, String body, String key) {
    return post(BASE + "/" + catalogId + "/items")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private MockHttpServletRequestBuilder update(
      UUID catalogId, UUID itemId, String ifMatch, String body) {
    MockHttpServletRequestBuilder b =
        put(BASE + "/" + catalogId + "/items/" + itemId)
            .header("Idempotency-Key", k())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  private static String k() {
    return UUID.randomUUID().toString();
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return perform(request).getResponse().getStatus();
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return as(() -> mvc.perform(request.principal(actor::toString)).andReturn());
  }

  private <T> T as(Callable<T> work) throws Exception {
    return TenantContext.call(tenant, work);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
