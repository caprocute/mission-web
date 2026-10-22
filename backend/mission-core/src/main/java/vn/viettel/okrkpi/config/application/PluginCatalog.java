package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.config.domain.PluginRelease;

/**
 * Danh mục plugin chuyên ngành đã duyệt của nền tảng (CORE-08-15): đọc từ tệp seed có phiên bản và
 * kiểm từng bản dựng (đã quét, nguồn được phép, SRI SHA-384, chữ ký cosign). Danh mục bất biến sau
 * khi nạp.
 */
public final class PluginCatalog {

  /** SRI SHA-384: 48 byte mã base64. */
  public static final Pattern SRI_SHA384 = Pattern.compile("^sha384-[A-Za-z0-9+/]{64}$");

  private final String catalogVersion;
  private final List<PluginRelease> releases;

  public PluginCatalog(String catalogVersion, List<PluginRelease> releases) {
    this.catalogVersion = catalogVersion;
    this.releases = List.copyOf(releases);
  }

  public static PluginCatalog empty() {
    return new PluginCatalog("", List.of());
  }

  /** Đọc tệp {@code catalog.json}. */
  public static PluginCatalog parse(String content, ObjectMapper json) {
    try {
      JsonNode root = json.readTree(content);
      List<PluginRelease> out = new ArrayList<>();
      for (JsonNode p : root.path("plugins")) {
        List<PluginRelease.Widget> widgets = new ArrayList<>();
        for (JsonNode w : p.path("widgets")) {
          widgets.add(
              new PluginRelease.Widget(
                  w.path("name").asText(),
                  json.writeValueAsString(w.path("propsSchema")),
                  w.path("allowedClassification").asText("C1"),
                  w.path("since").asText(null),
                  w.path("mobile").asBoolean(false),
                  w.path("registry").asText(null)));
        }
        out.add(
            new PluginRelease(
                p.path("name").asText(),
                p.path("displayName").asText(null),
                p.path("version").asText(),
                p.path("remoteEntry").asText(null),
                p.path("integrity").asText(null),
                p.path("signature").asText(null),
                p.path("publicKey").asText(null),
                p.path("scan").path("status").asText(null),
                widgets));
      }
      return new PluginCatalog(root.path("catalogVersion").asText(""), out);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Danh mục plugin không đọc được", e);
    }
  }

  public String catalogVersion() {
    return catalogVersion;
  }

  public List<PluginRelease> releases() {
    return releases;
  }

  /**
   * @param version {@code null} chọn phiên bản cao nhất của tên
   */
  public Optional<PluginRelease> find(String name, String version) {
    return releases.stream()
        .filter(r -> r.name().equals(name) && (version == null || r.version().equals(version)))
        .max(Comparator.comparing(PluginRelease::version, PluginCatalog::compareVersions));
  }

  /**
   * Lý do bản dựng không được nạp, rỗng khi đạt: chưa quét đạt, nguồn không được phép, mã toàn vẹn
   * sai dạng, chữ ký không kiểm được.
   *
   * @param origins nguồn {@code https://host[:port]} được phép ngoài cùng nguồn
   */
  public static List<String> problems(PluginRelease r, List<String> origins) {
    List<String> out = new ArrayList<>();
    if (!PluginRelease.SCAN_PASSED.equals(r.scanStatus())) {
      out.add("Plugin " + r.displayName() + " chưa đạt quét bảo mật.");
    }
    if (!allowedSource(r.remoteEntry(), origins)) {
      out.add("Plugin " + r.displayName() + " có nguồn nạp không được phép.");
    }
    if (r.integrity() == null || !SRI_SHA384.matcher(r.integrity()).matches()) {
      out.add("Plugin " + r.displayName() + " có mã toàn vẹn không hợp lệ.");
    } else if (!signatureValid(r)) {
      out.add("Chữ ký bản dựng của plugin " + r.displayName() + " không hợp lệ.");
    }
    return out;
  }

  /** Đường dẫn tương đối cùng nguồn, hoặc {@code https} thuộc nguồn khai báo (khớp CSP). */
  static boolean allowedSource(String remoteEntry, List<String> origins) {
    if (remoteEntry == null || remoteEntry.isBlank() || remoteEntry.contains("..")) {
      return false;
    }
    if (remoteEntry.startsWith("/") && !remoteEntry.startsWith("//")) {
      return true;
    }
    if (!remoteEntry.startsWith("https://")) {
      return false;
    }
    for (String o : origins) {
      String origin = o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
      if (!origin.isBlank() && remoteEntry.startsWith(origin + "/")) {
        return true;
      }
    }
    return false;
  }

  /** {@code cosign verify-blob}: ECDSA P-256 SHA-256 trên chuỗi {@code integrity}. */
  static boolean signatureValid(PluginRelease r) {
    if (r.signature() == null || r.publicKey() == null) {
      return false;
    }
    try {
      String pem =
          r.publicKey()
              .replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("\\s", "");
      PublicKey key =
          KeyFactory.getInstance("EC")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(key);
      verifier.update(r.integrity().getBytes(StandardCharsets.UTF_8));
      return verifier.verify(Base64.getDecoder().decode(r.signature().strip()));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  /** So phiên bản theo từng đoạn số, đoạn không phải số so theo chuỗi. */
  static int compareVersions(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      String p = i < x.length ? x[i] : "0";
      String q = i < y.length ? y[i] : "0";
      int c =
          p.matches("\\d{1,9}") && q.matches("\\d{1,9}")
              ? Integer.compare(Integer.parseInt(p), Integer.parseInt(q))
              : p.compareTo(q);
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }
}
