package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.config.application.port.PluginWidgets;
import vn.viettel.okrkpi.config.application.port.UiPackChecker.Code;
import vn.viettel.okrkpi.config.application.port.UiPackChecker.Result;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bộ ca kiểm tra tĩnh UI Pack (CORE-08-10, BR-18-01, BR-18-08, BR-18-09). */
class UiPackStaticChecksTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, byte[]> files = new HashMap<>();
  private final PolicySchemaPort schemas =
      (tenant, ref, range) ->
          ref.equals("policy://khoi-chinh-quyen/nd335/forms/mau-1b@1.2.0")
              ? Optional.of(Set.of("ketQuaNhiemVu", "diemTuCham"))
              : Optional.empty();
  private final UiPackStaticChecks checks =
      new UiPackStaticChecks(json, (t, p) -> Optional.ofNullable(files.get(p)), schemas);

  private ObjectNode document(String text, String background) throws Exception {
    return (ObjectNode)
        json.readTree(
            """
            {"requires":{"registry":"2026.3","policyPack":"nd335@>=1.2.0 <2.0.0"},
             "tokens":{"color":{"$type":"color","surface":{"$value":"%s"},"text":{"$value":"%s"},
               "primary":{"$value":"#0A5FA8"},"onPrimary":{"$value":"{color.white}"},
               "white":{"$value":"#FFFFFF"}},
               "font":{"size":{"body":{"$type":"dimension","$value":"15px"}}},
               "radius":{"card":{"$value":"12px"},"button":{"$value":"0.5rem"}}},
             "layouts":{"home":{"component":"vt.layout.grid"}},"assets":[]}
            """
                .formatted(background, text));
  }

  private Result result(ObjectNode doc, Code code) {
    return checks.check(TENANT, doc).stream()
        .filter(r -> r.code() == code)
        .findFirst()
        .orElse(null);
  }

  private static List<String> fields(Result r) {
    return r.errors().stream().map(ProblemFieldError::field).toList();
  }

  private void elderly(ObjectNode doc, String overridesJson, String touch) throws Exception {
    ObjectNode elderly = doc.putObject("modes").putObject("elderly");
    elderly.set("tokens", json.readTree(overridesJson));
    ObjectNode flags = elderly.putObject("flags").put("reducedSteps", true);
    if (touch != null) {
      flags.put("minTouchTarget", touch);
    }
  }

  @Test
  void contrastAaBlocksBelowFourPointFive() throws Exception {
    Result low = result(document("#787878", "#FFFFFF"), Code.CONTRAST);
    assertThat(low.passed()).isFalse();
    assertThat(low.msg()).isEqualTo("MSG-18-01");
    assertThat(low.message())
        .isEqualTo(
            "Màu chữ text trên nền surface có tương phản 4,41 : 1, chưa đạt mức AA (4,5 : 1).");
    assertThat(fields(low)).containsExactly("tokens.color.text");

    Result ok = result(document("#767676", "#FFFFFF"), Code.CONTRAST);
    assertThat(ok.passed()).isTrue();
    assertThat(ok.message()).contains("2 cặp");
    assertThat(result(document("#777777", "#FFFFFF"), Code.CONTRAST).passed()).isFalse();
    assertThat(result(document("teal", "#FFFFFF"), Code.CONTRAST).errors().get(0).message())
        .contains("#RRGGBB");
  }

  @Test
  void pairsFollowNamingConvention() {
    List<String[]> pairs =
        UiPackStaticChecks.pairs(
            List.of(
                "primary",
                "on-primary",
                "accent",
                "onAccent",
                "text",
                "text-muted",
                "textStrong",
                "surface",
                "surface-alt",
                "background",
                "border",
                "onMissing",
                "brand.base",
                "brand.on-base",
                "brand.text",
                "texture"));
    assertThat(pairs)
        .extracting(p -> p[0] + ">" + p[1])
        .containsExactlyInAnyOrder(
            "on-primary>primary",
            "onAccent>accent",
            "text>surface",
            "text>surface-alt",
            "text>background",
            "text-muted>surface",
            "text-muted>surface-alt",
            "text-muted>background",
            "textStrong>surface",
            "textStrong>surface-alt",
            "textStrong>background",
            "brand.on-base>brand.base");
  }

  @Test
  void elderModeNeedsSevenToOneLargeTextAndTouchTargets() throws Exception {
    ObjectNode doc = document("#5A5A5A", "#FFFFFF");
    elderly(
        doc,
        "{\"font\":{\"size\":{\"body\":{\"$value\":\"18px\"}}},\"color\":{\"primary\":{\"$value\":\"#084F8C\"}}}",
        "48px");
    Result r = result(doc, Code.ELDER_MODE);
    assertThat(r.passed()).isFalse();
    assertThat(r.msg()).isEqualTo("MSG-18-01");
    assertThat(r.message()).contains("6,89 : 1").contains("7 : 1");
    assertThat(fields(r)).containsExactly("tokens.color.text");

    ObjectNode ok = document("#5A5A5A", "#FFFFFF");
    elderly(
        ok,
        "{\"font\":{\"size\":{\"body\":{\"$value\":\"18px\"}}},\"color\":{\"primary\":{\"$value\":\"#084F8C\"},\"text\":{\"$value\":\"#595959\"}}}",
        "48px");
    assertThat(result(ok, Code.ELDER_MODE).passed()).isTrue();
    assertThat(result(ok, Code.CONTRAST).passed()).isTrue();

    ObjectNode small = document("#000000", "#FFFFFF");
    elderly(small, "{\"color\":{\"primary\":{\"$value\":\"#084F8C\"}}}", "44px");
    Result s = result(small, Code.ELDER_MODE);
    assertThat(s.passed()).isFalse();
    assertThat(s.msg()).isNull();
    assertThat(fields(s))
        .containsExactly(
            "tokens.$extensions.modes.elderly.font.size.body",
            "tokens.$extensions.modes.elderly.flags.minTouchTarget");
    assertThat(result(document("#000000", "#FFFFFF"), Code.ELDER_MODE)).isNull();
  }

  @Test
  void bundleOverThirtyKilobytesIsBlocked() throws Exception {
    ObjectNode doc = document("#000000", "#FFFFFF");
    assertThat(result(doc, Code.SIZE).passed()).isTrue();
    byte[] noise = new byte[30 * 1024 + 400];
    new Random(7).nextBytes(noise);
    doc.with("tokens")
        .putObject("$extensions")
        .put("padding", java.util.Base64.getEncoder().encodeToString(noise));
    int size = checks.gzipSize(doc);
    assertThat(size).isBetween(30 * 1024 + 1, 32 * 1024);
    Result r = result(doc, Code.SIZE);
    assertThat(r.passed()).isFalse();
    assertThat(r.msg()).isEqualTo("MSG-18-05");
    assertThat(r.message())
        .isEqualTo(
            "Kích thước UI Pack là " + ((size + 1023) / 1024) + " KB, vượt ngân sách 30 KB.");
  }

  @Test
  void tokenLimitsForFontAndRadius() throws Exception {
    ObjectNode doc = document("#000000", "#FFFFFF");
    assertThat(result(doc, Code.SCHEMA)).isNull();
    ((ObjectNode) doc.at("/tokens/font/size/body")).put("$value", "18px");
    ((ObjectNode) doc.at("/tokens/radius/card")).put("$value", "24px");
    ((ObjectNode) doc.at("/tokens/radius/button")).put("$value", "2em");
    assertThat(fields(result(doc, Code.SCHEMA)))
        .containsExactly("tokens.font.size.body", "tokens.radius.card", "tokens.radius.button");
    assertThat(UiPackStaticChecks.px(json.readTree("\"0\""), doc)).isZero();
    assertThat(UiPackStaticChecks.px(json.readTree("\"12\""), doc)).isNull();
  }

  private ObjectNode asset(ObjectNode doc, String media, byte[] bytes, String path) {
    files.put(path, bytes);
    ArrayNode list = doc.withArray("assets");
    return list.addObject()
        .put("key", "logo")
        .put("path", path)
        .put("mediaType", media)
        .put("sha256", UiPackStaticChecks.sha256(bytes))
        .put("sizeBytes", bytes.length);
  }

  @Test
  void assetsAreSanitizedSizedAndHashed() throws Exception {
    byte[] svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"1\" height=\"1\"/></svg>"
            .getBytes(StandardCharsets.UTF_8);
    ObjectNode ok = document("#000000", "#FFFFFF");
    asset(ok, "image/svg+xml", svg, "assets/logo.svg");
    byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0};
    asset(ok, "image/png", png, "assets/logo.png");
    asset(ok, "font/woff2", new byte[] {'w', 'O', 'F', '2', 1}, "assets/font.woff2");
    assertThat(result(ok, Code.SCHEMA)).isNull();

    ObjectNode bad = document("#000000", "#FFFFFF");
    asset(
        bad,
        "image/svg+xml",
        "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>"
            .getBytes(StandardCharsets.UTF_8),
        "assets/a.svg");
    byte[] big = new byte[51 * 1024];
    System.arraycopy(png, 0, big, 0, png.length);
    asset(bad, "image/png", big, "assets/big.png");
    asset(bad, "image/png", svg, "assets/fake.png")
        .put("sha256", "0".repeat(64))
        .put("sizeBytes", 1);
    bad.withArray("assets")
        .addObject()
        .put("key", "x")
        .put("path", "assets/missing.svg")
        .put("mediaType", "image/svg+xml")
        .put("sha256", "0".repeat(64));
    bad.withArray("assets")
        .addObject()
        .put("key", "y")
        .put("path", "assets/a.gif")
        .put("mediaType", "image/gif")
        .put("sha256", "0".repeat(64));
    asset(bad, "font/woff2", png, "assets/f.woff2");
    assertThat(fields(result(bad, Code.SCHEMA)))
        .containsExactly(
            "assets[0].path",
            "assets[1].sizeBytes",
            "assets[2].sizeBytes",
            "assets[2].sha256",
            "assets[2].path",
            "assets[3].path",
            "assets[4].mediaType",
            "assets[5].path");
  }

  @Test
  void formReferencesResolveThroughPolicyPack() throws Exception {
    ObjectNode doc = document("#000000", "#FFFFFF");
    doc.set(
        "forms",
        json.readTree(
            """
            {"tu-danh-gia":{"schemaRef":"policy://khoi-chinh-quyen/nd335/forms/mau-1b@1.2.0",
              "ui":{"sections":[{"fields":["ketQuaNhiemVu","hanChe"]}]}},
             "la":{"schemaRef":"policy://x/y@9"}}
            """));
    assertThat(fields(result(doc, Code.SCHEMA)))
        .containsExactly("forms.tu-danh-gia.ui.sections[0].fields[1]", "forms.la.schemaRef");
    UiPackStaticChecks noPort = new UiPackStaticChecks(json, null, null);
    assertThat(
            noPort.check(TENANT, doc).stream()
                .filter(r -> r.code() == Code.SCHEMA)
                .findFirst()
                .orElseThrow()
                .errors())
        .hasSize(2);
  }

  @Test
  void twentyWidgetPackChecksQuickly() throws Exception {
    ComponentRegistryService registry =
        new ComponentRegistryService(new InMemoryComponentRegistry(), PluginWidgets.NONE, json);
    registry.load(ComponentRegistryServiceTest.seed());
    LayoutValidator layout = new LayoutValidator(registry, json, List.of("/"));
    ObjectNode doc = document("#000000", "#FFFFFF");
    for (String name : List.of("home", "dashboard")) {
      ObjectNode grid = doc.with("layouts").putObject(name).put("component", "vt.layout.grid");
      ArrayNode children = grid.putArray("children");
      for (int i = 0; i < 10; i++) {
        children
            .addObject()
            .put("component", "vt.widget.myTasksToday")
            .put("visibleWhen", "'leader' in user.roles")
            .putObject("props")
            .put("limit", 5);
      }
    }
    long start = System.nanoTime();
    List<Result> all = new java.util.ArrayList<>(layout.check(TENANT, doc));
    all.addAll(checks.check(TENANT, doc));
    long millis = (System.nanoTime() - start) / 1_000_000;
    assertThat(all).allMatch(Result::passed);
    assertThat(millis).isLessThan(60_000);
  }
}
