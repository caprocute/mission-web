package vn.viettel.okrkpi.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.infrastructure.web.PeriodCaller;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.application.OpenApiConformanceEngine;
import vn.viettel.okrkpi.okr.infrastructure.web.OkrCaller;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;
import vn.viettel.okrkpi.policy.infrastructure.web.PolicyCaller;
import vn.viettel.okrkpi.shared.datasource.ModuleName;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.infrastructure.web.TaskCaller;

/**
 * NEN-035, ADR-07, TL-03 "Kiểm thử rò rỉ tenant" mở rộng lên tầng API: sinh từ hợp đồng mọi thao
 * tác {@code get} trả danh sách ({@code items} + {@code nextCursor}) do mission-core sở hữu ({@code
 * x-mission.owner} {@code MOD-*}, SVC-05). Tenant B được mồi dữ liệu bằng chính thao tác tạo
 * ({@code post} cùng đường dẫn, thân lấy từ {@code examples} của hợp đồng); dưới ngữ cảnh tenant A,
 * mọi thao tác danh sách đã hiện thực không được chứa mã nào của B, và lấy một đối tượng của B theo
 * mã phải trả 404 hoặc 403. Thao tác chưa hiện thực (404 không có handler, 501) được ghi nhận,
 * không tính lỗi.
 *
 * <p>Ngữ cảnh tenant được đặt như sau chặng JWT của {@code TenantContextFilter} (mission-core chưa
 * bật resource server; chặng JWT đã có kiểm thử riêng ở {@code vt-platform-starter}).
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiTenantLeakIT.Callers.class)
@DirtiesContext
class ApiTenantLeakIT {

  private static final Logger LOG = LoggerFactory.getLogger(ApiTenantLeakIT.class);
  private static final String BASE = "/api/v1";
  private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}]+)}");
  private static final Set<String> ALL_ROLES =
      Set.of(
          "member",
          "manager",
          "leader",
          "appraiser",
          "decider",
          "inspector",
          "tenant_admin",
          "config_admin",
          "auditor",
          "system_admin");

  /** Thứ tự mồi: cấu hình (kỳ) trước, các module phụ thuộc sau. */
  private static final List<ContractModule> MODULES =
      List.of(
          ContractModule.CONFIG,
          ContractModule.POLICIES,
          ContractModule.OKR,
          ContractModule.TASKS,
          ContractModule.PRODUCTS,
          ContractModule.APPRAISALS,
          ContractModule.DOSSIERS,
          ContractModule.OVERSIGHT,
          ContractModule.ORG,
          ContractModule.PLATFORM);

  /** Hai tenant của bộ sinh dòng NEN-034 ({@code TenantIsolationCatalogTest}). */
  private static final TenantId A =
      TenantId.of(UUID.fromString("01927a00-0000-7000-8000-00000000010a"));

  private static final TenantId B =
      TenantId.of(UUID.fromString("01927a00-0000-7000-8000-00000000010b"));
  private static final Map<TenantId, UUID> USERS =
      Map.of(A, UUID.randomUUID(), B, UUID.randomUUID());
  private static final Map<TenantId, UUID> UNITS =
      Map.of(A, UUID.randomUUID(), B, UUID.randomUUID());

  private static UUID user() {
    return USERS.get(TenantContext.require());
  }

  /**
   * Không gắn {@code @TestConfiguration}: quét thành phần của {@code MissionCoreApplication} nhặt
   * cả lớp cấu hình lồng của kiểm thử khác, và bean {@code @Primary} ở đây sẽ đè caller của chúng.
   */
  static class Callers {

    @Bean
    @Primary
    PeriodCaller leakPeriodCaller() {
      return new PeriodCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(user());
        }

        @Override
        public List<String> roles() {
          return List.copyOf(ALL_ROLES);
        }

        @Override
        public Optional<String> unitId() {
          return Optional.of(UNITS.get(TenantContext.require()).toString());
        }
      };
    }

    @Bean
    @Primary
    PolicyCaller leakPolicyCaller() {
      return () -> Optional.of(user());
    }

    @Bean
    @Primary
    OkrCaller leakOkrCaller() {
      return new OkrCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(user());
        }

        @Override
        public Optional<UUID> currentUnitId() {
          return Optional.of(UNITS.get(TenantContext.require()));
        }
      };
    }

    @Bean
    @Primary
    TaskCaller leakTaskCaller() {
      return () -> Optional.of(new TaskActor(user(), ALL_ROLES));
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
    seedBothTenants();
  }

  /** Mật khẩu {@code svc_<schema>} mà bộ sinh dòng NEN-034 dùng (hằng của lớp đó là protected). */
  private static final String SYNTH_PASSWORD = "mat-khau-thu-nghiem";

  private static final Map<String, Set<String>> B_ROWS = new TreeMap<>();
  private static final List<String> SEED_GAPS = new ArrayList<>();

  /**
   * Mồi dữ liệu hai tenant cho mọi bảng có {@code tenant_id} của 8 schema nghiệp vụ bằng bộ sinh
   * dòng tự động của NEN-034, trước khi ngữ cảnh Spring mở kết nối. Bộ sinh đăng nhập {@code
   * svc_<schema>} bằng mật khẩu thử nghiệm riêng của nó, nên mật khẩu được đổi tạm rồi trả lại.
   */
  private static synchronized void seedBothTenants() {
    if (!B_ROWS.isEmpty() || !SEED_GAPS.isEmpty()) {
      return;
    }
    PostgreSQLContainer<?> pg = MissionCoreTestDatabase.container();
    List<String> schemas = Arrays.stream(ModuleName.values()).map(ModuleName::schema).toList();
    try (Connection admin =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement st = admin.createStatement()) {
      for (String schema : schemas) {
        st.execute("ALTER ROLE svc_" + schema + " PASSWORD '" + SYNTH_PASSWORD + "'");
      }
      try {
        // Vi phạm của lớp 2 do NEN-034 canh; ở đây chỉ cần dòng mồi, bảng không sinh được thì bỏ
        // qua.
        SEED_GAPS.addAll(
            TenantIsolationCatalogTest.verifyTwoTenantIsolation(
                pg.getJdbcUrl(), admin, schemas, Map.of()));
      } finally {
        for (String schema : schemas) {
          st.execute(
              "ALTER ROLE svc_"
                  + schema
                  + " PASSWORD '"
                  + MissionCoreTestDatabase.TEST_DB_PASSWORD
                  + "'");
        }
      }
      try (ResultSet tables =
          st.executeQuery(
              "SELECT c.table_schema, c.table_name FROM information_schema.columns c"
                  + " JOIN information_schema.columns t ON t.table_schema = c.table_schema"
                  + " AND t.table_name = c.table_name AND t.column_name = 'tenant_id'"
                  + " JOIN information_schema.tables tb ON tb.table_schema = c.table_schema"
                  + " AND tb.table_name = c.table_name AND tb.table_type = 'BASE TABLE'"
                  + " WHERE c.column_name = 'id' AND c.table_schema = ANY (string_to_array('"
                  + String.join(",", schemas)
                  + "', ','))")) {
        List<String> names = new ArrayList<>();
        while (tables.next()) {
          names.add(tables.getString(1) + "." + tables.getString(2));
        }
        for (String table : names) {
          try (Statement q = admin.createStatement();
              ResultSet ids =
                  q.executeQuery(
                      "SELECT id::text FROM ONLY "
                          + table
                          + " WHERE tenant_id = '"
                          + B.value()
                          + "'")) {
            while (ids.next()) {
              B_ROWS.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(ids.getString(1));
            }
          }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Không mồi được dữ liệu hai tenant", e);
    }
  }

  @Autowired private MockMvc mvc;

  /** Thao tác danh sách lấy từ hợp đồng. */
  record ListOperation(ContractModule module, String path, Operation get, OpenAPI spec) {}

  /** Mã B mà chính B thấy qua từng thao tác danh sách (ví dụ {@code /okr/objectives}). */
  private final Map<String, List<String>> seededB = new LinkedHashMap<>();

  @Test
  void tenantAKhongBaoGioNhanDoiTuongCuaTenantB() throws Exception {
    List<ListOperation> lists = new ArrayList<>();
    for (ContractModule module : MODULES) {
      OpenAPI spec = OpenApiConformanceEngine.loadExpectedSpec(module);
      spec.getPaths()
          .forEach(
              (path, item) -> {
                Operation op = item.getGet();
                if (op != null && ownedByMissionCore(op) && returnsPage(spec, op)) {
                  lists.add(new ListOperation(module, path, op, spec));
                }
              });
    }
    assertThat(lists).as("hợp đồng phải có thao tác danh sách của mission-core").isNotEmpty();

    Set<String> bIds = new LinkedHashSet<>();
    B_ROWS.values().forEach(bIds::addAll);
    assertThat(bIds).as("phải mồi được dòng của tenant B").isNotEmpty();
    bIds.add(B.value().toString());
    bIds.add(USERS.get(B).toString());
    bIds.add(UNITS.get(B).toString());
    // Thao tác không tham số đường dẫn chạy trước để biết mã B nào thuộc tập hợp nào.
    lists.sort((x, y) -> Boolean.compare(x.path().contains("{"), y.path().contains("{")));

    Map<String, String> outcome = new TreeMap<>();
    List<String> leaks = new ArrayList<>();
    int checked = 0;
    int notImplemented = 0;
    int bSawOwnData = 0;
    for (ListOperation list : lists) {
      String url = BASE + fillPath(list.path()) + query(list.get());
      MvcResult asA = perform(A, get(url));
      int status = asA.getResponse().getStatus();
      String key = "GET " + list.path();
      if (!(asA.getHandler() instanceof org.springframework.web.method.HandlerMethod)
          || status == 501
          || status == 405) {
        notImplemented++;
        outcome.put(key, "chưa hiện thực (" + status + ")");
        continue;
      }
      String body = asA.getResponse().getContentAsString(StandardCharsets.UTF_8);
      if (status / 100 == 2) {
        checked++;
        for (String id : bIds) {
          if (body.contains(id)) {
            leaks.add(key + " dưới ngữ cảnh A trả mã " + id + " của tenant B");
          }
        }
        String asB = perform(B, get(url)).getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> seen = bIds.stream().filter(asB::contains).toList();
        boolean own = !seen.isEmpty();
        if (own && !list.path().contains("{")) {
          seededB.put(list.path(), seen);
        }
        if (own) {
          bSawOwnData++;
        }
        outcome.put(key, status + (own ? ", B thấy dữ liệu của mình" : ", B chưa có dữ liệu mồi"));
      } else {
        outcome.put(key, "đã hiện thực, trả " + status + " với tham số sinh tự động");
        // Problem Details nhắc lại đường dẫn yêu cầu ({@code instance}): mã A tự gửi lên không
        // phải rò rỉ; chỉ tính mã B mà A không gửi.
        for (String id : bIds) {
          if (!url.contains(id) && body.contains(id)) {
            leaks.add(key + " trả lỗi " + status + " có chứa mã " + id + " của tenant B");
          }
        }
      }
    }

    int byIdChecked = 0;
    for (Map.Entry<String, List<String>> e : seededB.entrySet()) {
      String itemPath = e.getKey() + "/";
      for (String id : e.getValue()) {
        MvcResult asA = perform(A, get(BASE + itemPath + id));
        int status = asA.getResponse().getStatus();
        if (status == 405) {
          continue;
        }
        byIdChecked++;
        if (status != 404 && status != 403) {
          leaks.add(
              "GET " + itemPath + "{id} của B dưới ngữ cảnh A trả " + status + " (cần 404/403)");
        }
      }
    }

    StringBuilder report = new StringBuilder("NEN-035 kiểm rò rỉ tenant tầng API:\n");
    B_ROWS.forEach(
        (t, ids) -> report.append("  mồi ").append(t).append(": ").append(ids.size()).append('\n'));
    report.append("  bảng không tự sinh được dòng mồi: ").append(SEED_GAPS.size()).append('\n');
    outcome.forEach((k, v) -> report.append("  ").append(k).append(": ").append(v).append('\n'));
    report
        .append("  Tổng: ")
        .append(lists.size())
        .append(" thao tác danh sách; ")
        .append(checked)
        .append(" đã kiểm (2xx); ")
        .append(notImplemented)
        .append(" chưa hiện thực; ")
        .append(bSawOwnData)
        .append(" có dữ liệu mồi của B; ")
        .append(seededB.values().stream().mapToInt(List::size).sum())
        .append(" mã B gắn được với tập hợp; ")
        .append(byIdChecked)
        .append(" lần lấy theo mã của B dưới ngữ cảnh A.");
    LOG.info("{}", report);
    assertThat(leaks).as(report.toString()).isEmpty();
    assertThat(checked).as("phải có thao tác danh sách đã hiện thực để kiểm").isPositive();
  }

  private MvcResult perform(TenantId tenant, MockHttpServletRequestBuilder request)
      throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  /**
   * Tham số đường dẫn: mã đối tượng B của tập hợp cha nếu có (phép thử mạnh nhất), không thì UUID.
   */
  private String fillPath(String path) {
    Matcher m = PATH_PARAM.matcher(path);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String parent = path.substring(0, m.start() - 1);
      List<String> ids = seededB.getOrDefault(parent, List.of());
      String value = ids.isEmpty() ? UUID.randomUUID().toString() : ids.get(0);
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** Tham số truy vấn bắt buộc lấy từ ví dụ, mặc định hoặc giá trị liệt kê đầu tiên. */
  private static String query(Operation op) {
    if (op.getParameters() == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (Parameter p : op.getParameters()) {
      if (!"query".equals(p.getIn()) || !Boolean.TRUE.equals(p.getRequired())) {
        continue;
      }
      parts.add(p.getName() + "=" + sampleValue(p));
    }
    return parts.isEmpty() ? "" : "?" + String.join("&", parts);
  }

  private static String sampleValue(Parameter p) {
    if (p.getExample() != null) {
      return p.getExample().toString();
    }
    Schema<?> s = p.getSchema();
    if (s == null) {
      return "x";
    }
    if (s.getExample() != null) {
      return s.getExample().toString();
    }
    if (s.getDefault() != null) {
      return s.getDefault().toString();
    }
    if (s.getEnum() != null && !s.getEnum().isEmpty()) {
      return s.getEnum().get(0).toString();
    }
    if ("uuid".equals(s.getFormat())) {
      return UUID.randomUUID().toString();
    }
    if ("date".equals(s.getFormat())) {
      return LocalDate.now().toString();
    }
    return "integer".equals(s.getType()) ? "1" : "x";
  }

  private static boolean ownedByMissionCore(Operation op) {
    Object mission = op.getExtensions() == null ? null : op.getExtensions().get("x-mission");
    return mission instanceof Map<?, ?> m
        && m.get("owner") instanceof String owner
        && owner.startsWith("MOD-");
  }

  private static boolean returnsPage(OpenAPI spec, Operation op) {
    ApiResponse ok = op.getResponses() == null ? null : op.getResponses().get("200");
    if (ok != null && ok.get$ref() != null) {
      ok = spec.getComponents().getResponses().get(simpleName(ok.get$ref()));
    }
    if (ok == null || ok.getContent() == null) {
      return false;
    }
    MediaType media = ok.getContent().get("application/json");
    return media != null && isPage(spec, media.getSchema(), 0);
  }

  private static boolean isPage(OpenAPI spec, Schema<?> schema, int depth) {
    if (schema == null || depth > 6) {
      return false;
    }
    if (schema.get$ref() != null) {
      return isPage(
          spec, spec.getComponents().getSchemas().get(simpleName(schema.get$ref())), depth + 1);
    }
    Map<String, Schema> props = schema.getProperties();
    if (props != null && props.containsKey("items") && props.containsKey("nextCursor")) {
      return true;
    }
    if (schema.getAllOf() != null) {
      for (Schema<?> part : schema.getAllOf()) {
        if (isPage(spec, part, depth + 1)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String simpleName(String ref) {
    return ref.substring(ref.lastIndexOf('/') + 1);
  }
}
