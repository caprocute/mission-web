package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * Lược đồ UI Pack 1.0 (CORE-08-08, X-G1-01): gói hợp lệ đi qua, chuỗi có thẻ HTML ở mọi trường văn
 * bản tự do bị từ chối, trường lạ và giá trị CSS nguy hiểm bị từ chối.
 */
class UiPackSchemaTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UiPackSchema SCHEMA = UiPackSchema.load(JSON);

  static ObjectNode valid() throws Exception {
    return (ObjectNode)
        JSON.readTree(
            """
            {"pack":"so-y-te-x","version":"1.8.0","requires":{"registry":"^2.4.0","policyPack":"khoi-chinh-quyen/nd335@>=1.2.0 <2.0.0"},
             "tokens":{"color":{"$type":"color","primary":{"$value":"#0A7E8C","$description":"Màu chính"}},
                       "shadow":{"card":{"$type":"shadow","$value":{"color":"#00000033","blur":"4px"}}},
                       "$extensions":{"modes":{"elderly":{"font":{"body":{"$value":"18px"}}}}}},
             "modes":{"elderly":{"tokens":{"font":{"body":{"$value":"18px"}}},"flags":{"reducedSteps":true,"minTouchTarget":"48px"}}},
             "layouts":{"home":{"component":"vt.layout.grid","props":{"columns":{"md":2},"title":"Việc hôm nay"},
                        "visibleWhen":"user.roles.exists(r, r == 'member')",
                        "children":[{"component":"vt.widget.myTasksToday","props":{"limit":7}}]}},
             "forms":{"phieu-vc-yte":{"layout":"two-column"}},
             "plugins":[{"name":"y-te","remoteEntry":"https://cdn.okrkpi.vn/plugins/y-te/remoteEntry.json","integrity":"sha384-abc+/="}],
             "i18n":{"vi":{"home.notice.q4":"Hạn nộp hồ sơ quý IV là ngày 10/01/2027."}},
             "assets":[{"key":"logo","path":"assets/logo.svg","mediaType":"image/svg+xml","sha256":"%s","sizeBytes":8421}]}
            """
                .formatted("a".repeat(64)));
  }

  @Test
  void validPackAndDefaultContentPass() throws Exception {
    assertThat(SCHEMA.validate(valid())).isEmpty();
  }

  /** Bộ ca: chèn chuỗi có thẻ HTML vào từng trường văn bản tự do. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "/i18n/vi|home.notice.q4|i18n.vi.home.notice.q4",
        "/layouts/home/props|title|layouts.home.props.title",
        "/layouts/home|visibleWhen|layouts.home.visibleWhen",
        "/tokens/color/primary|$description|tokens.color.primary.$description",
        "/tokens/color/primary|$value|tokens.color.primary.$value",
        "/forms/phieu-vc-yte|layout|forms.phieu-vc-yte.layout",
        "/tokens/$extensions/modes|note|tokens.$extensions.modes.note"
      })
  void htmlInFreeTextIsRejected(String parent, String field, String path) throws Exception {
    for (String html :
        List.of(
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "Chào <b>bạn</b>",
            "a>b")) {
      ObjectNode doc = valid();
      ((ObjectNode) doc.at(parent)).put(field, html);
      List<ProblemFieldError> errors = SCHEMA.validate(doc);
      assertThat(errors).as(path + " " + html).extracting(ProblemFieldError::field).contains(path);
      assertThat(errors).extracting(ProblemFieldError::message).anyMatch(m -> m.contains("HTML"));
    }
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "/tokens/color/primary|$value|url(https://x.vn/a.png)",
        "/tokens/color/primary|$value|expression(alert(1))",
        "/tokens/color/primary|$value|red;}body{display:none",
        "/plugins/0|remoteEntry|javascript:alert(1)",
        "/plugins/0|integrity|md5-abc",
        "/layouts/home|component|<div>",
        "/assets/0|sha256|xyz",
        "/requires|registry|<script>",
        "/requires|policyPack|nd335 <b>"
      })
  void dangerousValuesAreRejected(String parent, String field, String value) throws Exception {
    ObjectNode doc = valid();
    ((ObjectNode) doc.at(parent)).put(field, value);
    assertThat(SCHEMA.validate(doc)).isNotEmpty();
  }

  @Test
  void structureErrorsCarryFieldPaths() throws Exception {
    ObjectNode doc = valid();
    doc.put("script", "x");
    doc.remove("requires");
    ((ObjectNode) doc.at("/layouts/home")).put("html", "x");
    ((ObjectNode) doc.at("/assets/0")).put("sizeBytes", 60000);
    ((ObjectNode) doc.at("/tokens/color/primary")).put("onclick", "x");
    ((ObjectNode) doc.at("/tokens")).put("bad key!", JSON.createObjectNode());
    ((ObjectNode) doc.at("/modes/elderly/flags")).put("reducedSteps", "co");
    ((ObjectNode) doc.at("/assets/0")).put("mediaType", "text/html");
    doc.withArray("plugins").add(JSON.createObjectNode().put("name", "Y Te"));
    assertThat(SCHEMA.validate(doc))
        .extracting(ProblemFieldError::field)
        .contains(
            "script",
            "requires",
            "layouts.home.html",
            "assets[0].sizeBytes",
            "assets[0].mediaType",
            "tokens.color.primary.onclick",
            "tokens.bad key!",
            "modes.elderly.flags.reducedSteps",
            "plugins[1].remoteEntry",
            "plugins[1].name");
    assertThat(SCHEMA.validate(JSON.createArrayNode()))
        .extracting(ProblemFieldError::field)
        .containsExactly("$");
  }

  @Test
  void errorCountIsCapped() throws Exception {
    ObjectNode doc = valid();
    ObjectNode i18n = (ObjectNode) doc.at("/i18n/vi");
    for (int i = 0; i < 80; i++) {
      i18n.put("k" + i, "<b>x</b>");
    }
    assertThat(SCHEMA.validate(doc)).hasSize(UiPackSchema.MAX_ERRORS);
  }

  @Test
  void bespokeKeywordsAndLimits() throws Exception {
    JsonNode schema =
        JSON.readTree(
            """
            {"type":"object","properties":{
               "n":{"type":"integer","minimum":1,"maximum":3},
               "s":{"type":"string","minLength":2},
               "a":{"type":"array","minItems":1,"maxItems":1},
               "o":{"type":"object","maxProperties":1},
               "e":{"enum":["x"]},
               "z":{"type":"null"}}}
            """);
    UiPackSchema s = UiPackSchema.of(schema);
    assertThat(
            s.validate(
                JSON.readTree(
                    "{\"n\":2.0,\"s\":\"ab\",\"a\":[1],\"o\":{},\"e\":\"x\",\"z\":null}")))
        .isEmpty();
    assertThat(
            s.validate(
                JSON.readTree(
                    "{\"n\":5,\"s\":\"a\",\"a\":[],\"o\":{\"a\":1,\"b\":2},\"e\":\"y\",\"z\":1}")))
        .extracting(ProblemFieldError::field)
        .containsExactlyInAnyOrder("n", "s", "a", "o", "e", "z");
    assertThat(s.validate(JSON.readTree("{\"n\":0,\"a\":[1,2]}"))).hasSize(2);
    assertThat(s.validate(JSON.readTree("{\"n\":1.5}"))).hasSize(1);
    assertThatThrownBy(() -> UiPackSchema.of(JSON.readTree("{\"oneOf\":[]}")))
        .isInstanceOf(IllegalStateException.class);
    UiPackSchema badRef = UiPackSchema.of(JSON.readTree("{\"$ref\":\"#/$defs/x\"}"));
    assertThatThrownBy(() -> badRef.validate(JSON.createObjectNode()))
        .isInstanceOf(IllegalStateException.class);
    UiPackSchema remoteRef = UiPackSchema.of(JSON.readTree("{\"$ref\":\"other.json\"}"));
    assertThatThrownBy(() -> remoteRef.validate(JSON.createObjectNode()))
        .isInstanceOf(IllegalStateException.class);
    UiPackSchema strangeType = UiPackSchema.of(JSON.readTree("{\"type\":\"date\"}"));
    assertThatThrownBy(() -> strangeType.validate(JSON.createObjectNode()))
        .isInstanceOf(IllegalStateException.class);
  }
}
