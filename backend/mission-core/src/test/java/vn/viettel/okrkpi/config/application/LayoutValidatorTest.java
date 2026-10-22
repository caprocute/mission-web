package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.port.PluginWidgets;
import vn.viettel.okrkpi.config.application.port.UiPackChecker.Code;
import vn.viettel.okrkpi.config.application.port.UiPackChecker.Result;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bộ ca kiểm bố cục SDUI theo registry 2026.3 (CORE-08-09, BR-18-02, MSG-18-02). */
class LayoutValidatorTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private final ObjectMapper json = new ObjectMapper();
  private LayoutValidator validator;

  @BeforeEach
  void setUp() throws Exception {
    ComponentRegistryService registry =
        new ComponentRegistryService(new InMemoryComponentRegistry(), PluginWidgets.NONE, json);
    registry.load(ComponentRegistryServiceTest.seed());
    validator = new LayoutValidator(registry, json, List.of("/okr", "/tasks"));
  }

  private ObjectNode document(String homeChildren) throws Exception {
    return (ObjectNode)
        json.readTree(
            """
            {"requires":{"registry":"2026.3"},"layouts":{"home":{"component":"vt.layout.grid",
            "props":{"columns":{"xs":1}},"children":%s}}}
            """
                .formatted(homeChildren));
  }

  private Result failed(JsonNode document) {
    return validator.check(TENANT, document).stream()
        .filter(r -> !r.passed())
        .findFirst()
        .orElse(null);
  }

  private static List<String> fields(Result r) {
    return r.errors().stream().map(ProblemFieldError::field).toList();
  }

  @Test
  void validLayoutPasses() throws Exception {
    List<Result> results =
        validator.check(
            TENANT,
            document(
                """
                [{"component":"vt.widget.myTasksToday","props":{"limit":7},
                  "visibleWhen":"user.roles.exists(r, r in ['leader', 'manager'])"},
                 {"component":"vt.widget.announcement","props":{"i18nKey":"home.notice"}},
                 {"component":"vt.widget.okrProgress","props":{"scope":"unit"}}]
                """));
    assertThat(results).allMatch(Result::passed);
    assertThat(results).extracting(Result::code).containsExactly(Code.REGISTRY, Code.CEL_PARITY);
  }

  @Test
  void unknownComponentIsRegistryError() throws Exception {
    Result r = failed(document("[{\"component\":\"vt.widget.kpiTile\"}]"));
    assertThat(r.code()).isEqualTo(Code.REGISTRY);
    assertThat(r.msg()).isEqualTo("MSG-18-02");
    assertThat(r.message())
        .isEqualTo(
            "Component vt.widget.kpiTile không có trong danh mục cho phép phiên bản 2026.3.");
    assertThat(fields(r)).containsExactly("layouts.home.children[0].component");
  }

  @Test
  void extraPropsAndWrongValuesAreSchemaErrors() throws Exception {
    Result r =
        failed(
            document(
                "[{\"component\":\"vt.widget.myTasksToday\",\"props\":{\"limit\":50,\"color\":\"red\"}}]"));
    assertThat(r.code()).isEqualTo(Code.SCHEMA);
    assertThat(fields(r))
        .contains("layouts.home.children[0].props.limit", "layouts.home.children[0].props.color");
  }

  @Test
  void thirteenWidgetsAreTooMany() throws Exception {
    ArrayNode children = json.createArrayNode();
    for (int i = 0; i < 13; i++) {
      children.addObject().put("component", "vt.widget.pendingApprovals");
    }
    ObjectNode doc = document(children.toString());
    doc.with("layouts").set("dashboard", doc.path("layouts").path("home").deepCopy());
    Result r = failed(doc);
    assertThat(r.code()).isEqualTo(Code.SCHEMA);
    assertThat(fields(r)).containsExactly("layouts.home", "layouts.dashboard");
  }

  @Test
  void htmlScriptsAndUnsafeUrlsAreRejected() throws Exception {
    Result html =
        failed(
            document(
                "[{\"component\":\"vt.widget.announcement\",\"props\":{\"i18nKey\":\"<b>x</b>\"}}]"));
    assertThat(html.code()).isEqualTo(Code.SCHEMA);
    assertThat(html.errors()).anyMatch(e -> e.message().equals("Không được chứa thẻ HTML"));

    ObjectNode url = document("[]");
    ((ObjectNode) url.at("/layouts/home/props/columns")).put("link", "http://evil.example/x");
    Result http = failed(url);
    assertThat(http.code()).isEqualTo(Code.SCHEMA);
    assertThat(fields(http)).contains("layouts.home.props.columns.link");

    assertThat(validator.unsafe("javascript:alert(1)")).isNotNull();
    assertThat(validator.unsafe("JavaScript :alert(1)")).isNotNull();
    assertThat(validator.unsafe("//evil.example")).isNotNull();
    assertThat(validator.unsafe("/admin/x")).isNotNull();
    assertThat(validator.unsafe("/okr/../admin")).isNotNull();
    assertThat(validator.unsafe("mailto:a@b.vn")).isNotNull();
    assertThat(validator.unsafe("https://mission.gov.vn/a")).isNull();
    assertThat(validator.unsafe("/okr/ca-nhan")).isNull();
    assertThat(validator.unsafe("Lưu ý: họp lúc 12:30")).isNull();
    assertThat(validator.unsafe("home.notice")).isNull();
    assertThat(validator.unsafe("")).isNull();
  }

  @Test
  void childrenOnlyInContainers() throws Exception {
    Result r =
        failed(
            document(
                "[{\"component\":\"vt.widget.okrProgress\",\"children\":[{\"component\":\"vt.widget.pendingApprovals\"}]}]"));
    assertThat(r.code()).isEqualTo(Code.SCHEMA);
    assertThat(fields(r)).containsExactly("layouts.home.children[0].children");
  }

  @Test
  void visibleWhenMustBeBoolCelOverUserOnly() throws Exception {
    for (String expr :
        List.of("user.unitId", "tenant.id == 'x'", "user.roles.exists(r,", "1 + 1 == 2 ||")) {
      ObjectNode doc =
          document("[{\"component\":\"vt.widget.pendingApprovals\",\"visibleWhen\":\"\"}]");
      ((ObjectNode) doc.at("/layouts/home/children/0")).put("visibleWhen", expr);
      Result r = failed(doc);
      assertThat(r).as(expr).isNotNull();
      assertThat(r.code()).as(expr).isEqualTo(Code.CEL_PARITY);
      assertThat(fields(r)).containsExactly("layouts.home.children[0].visibleWhen");
    }
    StringBuilder deep = new StringBuilder("user.unitId == 'a'");
    for (int i = 0; i < 40; i++) {
      deep.append(" || user.unitId == 'a").append(i).append("'");
    }
    ObjectNode doc = document("[{\"component\":\"vt.widget.pendingApprovals\"}]");
    ((ObjectNode) doc.at("/layouts/home/children/0")).put("visibleWhen", deep.toString());
    assertThat(failed(doc).code()).isEqualTo(Code.CEL_PARITY);
  }

  @Test
  void unresolvableRegistryAndRangesAreHandled() throws Exception {
    ObjectNode doc = document("[]");
    ((ObjectNode) doc.path("requires")).put("registry", "2099.1");
    Result r = failed(doc);
    assertThat(r.code()).isEqualTo(Code.REGISTRY);
    assertThat(fields(r)).containsExactly("componentRegistryVersion");
    ((ObjectNode) doc.path("requires")).put("registry", ">=2026 <2027");
    assertThat(failed(doc)).isNull();
  }
}
