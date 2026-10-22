package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** CORE-08-11: biên dịch token sang CSS, preset, JSON chuẩn tắc. */
class UiPackCompilerTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final UiPackCompiler compiler = new UiPackCompiler(JSON);

  private static JsonNode json(String s) throws Exception {
    return JSON.readTree(s);
  }

  @Test
  void cssHasTenantBlockAndOnlyChangedElderlyVariables() throws Exception {
    JsonNode tokens =
        json(
            """
            {"color":{"primary":{"$type":"color","$value":"#0B5FA5"},
                      "on-primary":{"$value":"#FFFFFF"},
                      "link":{"$value":"{color.primary}"}},
             "font":{"size":{"body":{"$value":"16px"}}}}
            """);
    JsonNode elderly = json("{\"font\":{\"size\":{\"body\":{\"$value\":\"18px\"}}}}");
    String css = compiler.css("t-1", tokens, elderly);
    assertThat(css)
        .contains(":root[data-tenant=\"t-1\"] {")
        .contains("--vt-color-primary: #0B5FA5;")
        .contains("--vt-color-link: var(--vt-color-primary);")
        .contains(
            ":root[data-tenant=\"t-1\"][data-mode=\"elderly\"] {\n  --vt-font-size-body: 18px;\n}");
  }

  @Test
  void unsafeValuesAndTenantAreRejected() throws Exception {
    JsonNode bad = json("{\"color\":{\"primary\":{\"$value\":\"red;} body{x:url(a)\"}}}");
    assertThatThrownBy(() -> compiler.css("t", bad, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> compiler.css("t\"]{", json("{}"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void presetMapsSemanticTokens() throws Exception {
    JsonNode tokens =
        json(
            """
            {"color":{"primary":{"$value":"#0B5FA5"},"on-primary":{"$value":"#FFFFFF"},
                      "surface":{"$value":"#FFFFFF"},"text":{"$value":"#1A1A1A"}},
             "radius":{"card":{"$value":"8px"}}}
            """);
    JsonNode preset =
        compiler.preset(tokens, json("{\"color\":{\"primary\":{\"$value\":\"#084F8C\"}}}"));
    assertThat(preset.at("/default/semantic/primary/color").asText()).isEqualTo("#0B5FA5");
    assertThat(preset.at("/default/semantic/primary/contrastColor").asText()).isEqualTo("#FFFFFF");
    assertThat(preset.at("/default/semantic/colorScheme/light/text/color").asText())
        .isEqualTo("#1A1A1A");
    assertThat(preset.at("/default/extend/vt/radiusCard").asText()).isEqualTo("8px");
    assertThat(preset.at("/elderly/semantic/primary/color").asText()).isEqualTo("#084F8C");
  }

  @Test
  void canonicalSortsKeysAtEveryLevel() throws Exception {
    byte[] a = compiler.canonical(json("{\"b\":1,\"a\":{\"d\":[{\"z\":1,\"y\":2}],\"c\":\"é\"}}"));
    byte[] b = compiler.canonical(json("{\"a\":{\"c\":\"é\",\"d\":[{\"y\":2,\"z\":1}]},\"b\":1}"));
    assertThat(new String(a, StandardCharsets.UTF_8))
        .isEqualTo("{\"a\":{\"c\":\"é\",\"d\":[{\"y\":2,\"z\":1}]},\"b\":1}");
    assertThat(a).isEqualTo(b);
  }
}
