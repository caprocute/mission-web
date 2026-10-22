package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.domain.PluginRelease;
import vn.viettel.okrkpi.config.domain.UiComponent;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * CORE-08-15: plugin ngoài danh mục, mã toàn vẹn sai, chữ ký sai bị chặn; plugin hợp lệ vào
 * manifest và danh mục component.
 */
class PluginManifestServiceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

  private static KeyPair keys() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  private static String pem(KeyPair k) {
    return "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(k.getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----";
  }

  private static String sign(KeyPair k, String integrity) throws Exception {
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(k.getPrivate());
    s.update(integrity.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(s.sign());
  }

  private static String sri(String content) throws Exception {
    return "sha384-"
        + Base64.getEncoder()
            .encodeToString(
                MessageDigest.getInstance("SHA-384")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
  }

  private static PluginRelease release(
      String name, String version, String entry, String integrity, String signature, KeyPair k) {
    return new PluginRelease(
        name,
        null,
        version,
        entry,
        integrity,
        signature,
        pem(k),
        "DAT",
        List.of(
            new PluginRelease.Widget(
                "vt.widget." + name.replace("-", ""), "{}", "C1", "1.0.0", false, null)));
  }

  private static PluginManifestService service(PluginCatalog catalog, String... enabled) {
    ArrayNode value = JSON.createArrayNode();
    for (String e : enabled) {
      value.add(e);
    }
    return new PluginManifestService(
        catalog,
        (t, key, unit) ->
            PluginManifestService.SETTING_KEY.equals(key)
                ? Optional.<JsonNode>of(value)
                : Optional.empty(),
        List.of("https://plugins.okrkpi.vn"),
        JSON);
  }

  @Test
  void blocksUnknownTamperedAndWronglySignedPlugins() throws Exception {
    KeyPair k = keys();
    KeyPair other = keys();
    String integrity = sri("bản dựng y tế");
    PluginCatalog catalog =
        new PluginCatalog(
            "t",
            List.of(
                release(
                    "yte",
                    "1.0.0",
                    "/plugins/yte/remoteEntry.json",
                    integrity,
                    sign(k, integrity),
                    k),
                release(
                    "giaoduc",
                    "1.0.0",
                    "/plugins/gd/remoteEntry.json",
                    sri("bản dựng khác"),
                    sign(k, integrity),
                    k),
                release(
                    "llvt",
                    "1.0.0",
                    "/plugins/llvt/remoteEntry.json",
                    integrity,
                    sign(other, integrity),
                    k),
                release("hong", "1.0.0", "/plugins/hong/remoteEntry.json", "md5-abc", "x", k),
                release(
                    "ngoai",
                    "1.0.0",
                    "https://evil.example/remoteEntry.json",
                    integrity,
                    sign(k, integrity),
                    k)));

    PluginManifestService svc =
        service(catalog, "khong-co", "giaoduc", "llvt", "hong", "ngoai", "yte");
    List<UiPackChecker.Result> results = svc.check(TENANT, JSON.createObjectNode());
    assertThat(results).hasSize(1);
    assertThat(results.get(0).passed()).isFalse();
    assertThat(results.get(0).code()).isEqualTo(UiPackChecker.Code.REGISTRY);
    assertThat(results.get(0).errors())
        .extracting(e -> e.field() + " " + e.message())
        .containsExactly(
            "plugins[0] Plugin khong-co không có trong danh mục plugin đã được duyệt.",
            "plugins[1] Chữ ký bản dựng của plugin giaoduc không hợp lệ.",
            "plugins[2] Chữ ký bản dựng của plugin llvt không hợp lệ.",
            "plugins[3] Plugin hong có mã toàn vẹn không hợp lệ.",
            "plugins[4] Plugin ngoai có nguồn nạp không được phép.");
    // Chỉ widget của plugin đạt vào danh mục component.
    assertThat(svc.forTenant(TENANT, "2026.3"))
        .extracting(UiComponent::componentKey, UiComponent::plugin)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("vt.widget.yte", "yte"));
    assertThatThrownBy(() -> svc.manifest(TENANT)).hasMessage("COMMON-422-VALIDATION");
  }

  @Test
  void validPluginGoesIntoManifestWithPinnedVersion() throws Exception {
    KeyPair k = keys();
    String v1 = sri("1"), v2 = sri("2");
    PluginCatalog catalog =
        new PluginCatalog(
            "t",
            List.of(
                release(
                    "yte",
                    "1.2.0",
                    "https://plugins.okrkpi.vn/yte/1.2.0/remoteEntry.json",
                    v1,
                    sign(k, v1),
                    k),
                release(
                    "yte",
                    "1.10.0",
                    "https://plugins.okrkpi.vn/yte/1.10.0/remoteEntry.json",
                    v2,
                    sign(k, v2),
                    k)));
    ArrayNode latest = service(catalog, "yte").manifest(TENANT);
    assertThat(latest.get(0).get("integrity").asText()).isEqualTo(v2);
    assertThat(latest.get(0).get("remoteEntry").asText()).contains("1.10.0");
    ArrayNode pinned = service(catalog, "yte@1.2.0").manifest(TENANT);
    assertThat(pinned.get(0).get("integrity").asText()).isEqualTo(v1);
    List<UiPackChecker.Result> ok = service(catalog, "yte").check(TENANT, JSON.createObjectNode());
    assertThat(ok.get(0).passed()).isTrue();
    assertThat(service(catalog).check(TENANT, JSON.createObjectNode())).isEmpty();
    assertThat(service(catalog).manifest(TENANT)).isEmpty();
  }

  /** Tệp seed {@code data/seed/plugins/catalog.json} đọc được và mục giả lập kiểm chữ ký đạt. */
  @Test
  void seedCatalogVerifies() throws Exception {
    Path seed =
        List.of("../../data/seed/plugins/catalog.json", "../data/seed/plugins/catalog.json")
            .stream()
            .map(Path::of)
            .filter(Files::exists)
            .findFirst()
            .orElseThrow();
    PluginCatalog catalog = PluginCatalog.parse(Files.readString(seed), JSON);
    assertThat(catalog.releases()).isNotEmpty();
    for (PluginRelease r : catalog.releases()) {
      assertThat(PluginCatalog.problems(r, List.of())).as(r.name()).isEmpty();
    }
  }
}
