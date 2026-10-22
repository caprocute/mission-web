package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.config.application.port.UiPackAssetStore;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.domain.WcagContrast;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm tra tĩnh UI Pack khi lưu nháp và khi gửi duyệt (CORE-08-10, tầng hai của kiểm thử hồi quy
 * theo tenant, TL-01): tương phản WCAG 2.1 AA ≥ 4,5 : 1 cho mọi cặp token chữ và nền (BR-18-01,
 * MSG-18-01), chế độ cao tuổi ≥ 7 : 1, chữ ≥ 17 px, vùng chạm ≥ 48 px (BR-18-09), gói ≤ 30 KB gzip
 * (BR-18-08, MSG-18-05), giới hạn cỡ chữ và bo góc, tài sản an toàn, tham chiếu biểu mẫu (ADR-25).
 *
 * <p>Cặp chữ và nền suy ra từ tên token trong cùng nhóm màu (quy ước ở wiki trang 03): {@code
 * on-X}/{@code onX} là chữ trên nền {@code X}; {@code text}, {@code text-*}, {@code textX} là chữ
 * trên mọi nền {@code surface*}, {@code background*} cùng nhóm.
 */
public class UiPackStaticChecks implements UiPackChecker {

  /** Ngân sách gói UI Pack (BR-18-08). */
  public static final int BUNDLE_MAX_BYTES = 30 * 1024;

  /** Giới hạn mỗi tài nguyên, kể cả logo (hợp đồng {@code UiPackAsset.sizeBytes}). */
  public static final int ASSET_MAX_BYTES = 50 * 1024;

  static final BigDecimal FONT_MIN = BigDecimal.valueOf(12);
  static final BigDecimal FONT_MAX = BigDecimal.valueOf(16);
  static final BigDecimal RADIUS_MAX = BigDecimal.valueOf(16);
  static final BigDecimal ELDER_FONT_MIN = BigDecimal.valueOf(17);
  static final BigDecimal TOUCH_MIN = BigDecimal.valueOf(48);

  private static final Pattern ALIAS = Pattern.compile("^\\{([A-Za-z0-9_.-]+)\\}$");
  private static final Pattern DIMENSION = Pattern.compile("^(\\d+(?:\\.\\d+)?)(px|rem)?$");
  private static final Set<String> MEDIA = Set.of("image/svg+xml", "image/png", "font/woff2");
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  private static final byte[] WOFF2 = {'w', 'O', 'F', '2'};

  private final ObjectMapper json;
  private final UiPackAssetStore assets;
  private final PolicySchemaPort schemas;

  /**
   * @param schemas {@code null} khi MOD-05 chưa cung cấp; khi đó UI Pack có biểu mẫu không qua được
   *     bước kiểm tham chiếu
   */
  public UiPackStaticChecks(ObjectMapper json, UiPackAssetStore assets, PolicySchemaPort schemas) {
    this.json = Objects.requireNonNull(json, "json");
    this.assets = assets == null ? UiPackAssetStore.NONE : assets;
    this.schemas = schemas;
  }

  @Override
  public List<Result> check(TenantId tenantId, JsonNode document) {
    JsonNode tokens = document.path("tokens");
    List<Result> results = new ArrayList<>();
    results.add(contrast(Code.CONTRAST, tokens, WcagContrast.AA, "AA (4,5 : 1)"));
    JsonNode elderly = document.path("modes").path("elderly");
    if (elderly.isObject()) {
      results.add(elderMode(tokens, elderly));
    }
    results.add(size(document));
    List<ProblemFieldError> structure = new ArrayList<>();
    limits(tokens, structure);
    assets(tenantId, document.path("assets"), structure);
    forms(tenantId, document, structure);
    if (!structure.isEmpty()) {
      results.add(
          new Result(
              Code.SCHEMA, false, "Token, tài nguyên hoặc biểu mẫu chưa hợp lệ.", null, structure));
    }
    return results;
  }

  // ----- Tương phản -----

  private Result contrast(Code code, JsonNode tokens, double threshold, String level) {
    Map<String, String> colors = colors(tokens);
    List<ProblemFieldError> errors = new ArrayList<>();
    String first = null;
    int pairs = 0;
    for (String[] pair : pairs(colors.keySet())) {
      pairs++;
      String text = colors.get(pair[0]);
      String background = colors.get(pair[1]);
      String field = "tokens.color." + pair[0];
      if (!WcagContrast.parsable(text) || !WcagContrast.parsable(background)) {
        errors.add(
            new ProblemFieldError(
                field,
                "Không kiểm được tương phản của màu "
                    + pair[0]
                    + " trên nền "
                    + pair[1]
                    + ", hãy dùng mã màu dạng #RRGGBB",
                null));
        continue;
      }
      double ratio = WcagContrast.ratio(text, background);
      if (ratio < threshold) {
        String message =
            "Màu chữ "
                + pair[0]
                + " trên nền "
                + pair[1]
                + " có tương phản "
                + WcagContrast.format(ratio)
                + " : 1, chưa đạt mức "
                + level
                + ".";
        errors.add(new ProblemFieldError(field, message, null));
        first = first == null ? message : first;
      }
    }
    if (errors.isEmpty()) {
      return Result.ok(
          code, "Đã kiểm " + pairs + " cặp màu chữ và nền, đều đạt mức " + level + ".");
    }
    return new Result(
        code, false, first == null ? errors.get(0).message() : first, "MSG-18-01", errors);
  }

  /** Cặp (chữ, nền) theo quy ước tên token trong cùng nhóm. */
  static List<String[]> pairs(Iterable<String> paths) {
    Map<String, List<String>> groups = new LinkedHashMap<>();
    for (String p : paths) {
      int dot = p.lastIndexOf('.');
      groups.computeIfAbsent(dot < 0 ? "" : p.substring(0, dot), k -> new ArrayList<>()).add(p);
    }
    List<String[]> pairs = new ArrayList<>();
    for (List<String> members : groups.values()) {
      for (String text : members) {
        String name = leaf(text);
        String prefix = text.substring(0, text.length() - name.length());
        String background = onTarget(name);
        if (background != null && members.contains(prefix + background)) {
          pairs.add(new String[] {text, prefix + background});
        } else if (name.equals("text") || name.startsWith("text-") || camel(name, "text")) {
          for (String b : members) {
            String bn = leaf(b);
            if (bn.startsWith("surface") || bn.startsWith("background")) {
              pairs.add(new String[] {text, b});
            }
          }
        }
      }
    }
    return pairs;
  }

  private static String onTarget(String name) {
    if (name.startsWith("on-") && name.length() > 3) {
      return name.substring(3);
    }
    if (camel(name, "on")) {
      return Character.toLowerCase(name.charAt(2)) + name.substring(3);
    }
    return null;
  }

  private static boolean camel(String name, String prefix) {
    return name.length() > prefix.length()
        && name.startsWith(prefix)
        && Character.isUpperCase(name.charAt(prefix.length()));
  }

  private static String leaf(String path) {
    return path.substring(path.lastIndexOf('.') + 1);
  }

  /** Màu lá của nhóm {@code color}, đường dẫn tương đối, bí danh đã giải. */
  private Map<String, String> colors(JsonNode tokens) {
    Map<String, String> out = new LinkedHashMap<>();
    collect(tokens.path("color"), "", tokens, out);
    return out;
  }

  private void collect(JsonNode group, String path, JsonNode root, Map<String, String> out) {
    for (Iterator<Map.Entry<String, JsonNode>> it = group.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      if (e.getKey().startsWith("$") || !e.getValue().isObject()) {
        continue;
      }
      String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
      if (e.getValue().has("$value")) {
        out.put(p, resolve(e.getValue().get("$value"), root, 0));
      } else {
        collect(e.getValue(), p, root, out);
      }
    }
  }

  private static String resolve(JsonNode value, JsonNode root, int depth) {
    String s = value.isTextual() ? value.asText() : value.toString();
    Matcher alias = ALIAS.matcher(s.trim());
    if (!alias.matches() || depth > 8) {
      return s;
    }
    JsonNode target = root;
    for (String part : alias.group(1).split("\\.")) {
      target = target.path(part);
    }
    return target.has("$value") ? resolve(target.get("$value"), root, depth + 1) : s;
  }

  // ----- Chế độ cao tuổi -----

  private Result elderMode(JsonNode tokens, JsonNode elderly) {
    JsonNode overrides = elderly.path("tokens");
    JsonNode merged = UiPackService.merge(tokens.deepCopy(), overrides);
    Result contrast =
        contrast(Code.ELDER_MODE, merged, WcagContrast.ELDERLY, "dành cho chế độ cao tuổi (7 : 1)");
    List<ProblemFieldError> errors = new ArrayList<>(contrast.errors());
    BigDecimal body = px(merged.path("font").path("size").path("body"), merged);
    if (body == null || body.compareTo(ELDER_FONT_MIN) < 0) {
      errors.add(
          new ProblemFieldError(
              "tokens.$extensions.modes.elderly.font.size.body",
              "Chế độ cao tuổi cần cỡ chữ cơ sở từ 17 px",
              null));
    }
    JsonNode touch = elderly.path("flags").path("minTouchTarget");
    String touchField = "tokens.$extensions.modes.elderly.flags.minTouchTarget";
    for (String key : List.of("minTouchTarget", "min-touch-target")) {
      if (merged.path("size").has(key)) {
        touch = merged.path("size").get(key);
        touchField = "tokens.size." + key;
      }
    }
    if (!touch.isMissingNode()) {
      BigDecimal t = px(touch, merged);
      if (t == null || t.compareTo(TOUCH_MIN) < 0) {
        errors.add(
            new ProblemFieldError(touchField, "Chế độ cao tuổi cần vùng chạm từ 48 px", null));
      }
    }
    if (errors.isEmpty()) {
      return Result.ok(
          Code.ELDER_MODE, "Chế độ cao tuổi đạt tương phản 7 : 1, cỡ chữ và vùng chạm.");
    }
    return new Result(
        Code.ELDER_MODE,
        false,
        contrast.passed() ? errors.get(0).message() : contrast.message(),
        contrast.passed() ? null : "MSG-18-01",
        errors);
  }

  // ----- Kích thước -----

  private Result size(JsonNode document) {
    int bytes = gzipSize(document);
    long kb = (bytes + 1023L) / 1024;
    if (bytes > BUNDLE_MAX_BYTES) {
      return new Result(
          Code.SIZE,
          false,
          "Kích thước UI Pack là " + kb + " KB, vượt ngân sách 30 KB.",
          "MSG-18-05",
          List.of(
              new ProblemFieldError(
                  "$", "Kích thước UI Pack là " + kb + " KB, vượt ngân sách 30 KB.", null)));
    }
    return Result.ok(Code.SIZE, "Kích thước UI Pack là " + kb + " KB, trong ngân sách 30 KB.");
  }

  /** Kích thước gzip của nội dung phiên bản (bản đóng gói khi phát hành, suy luận). */
  public int gzipSize(JsonNode document) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
      gz.write(json.writeValueAsBytes(document));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.size();
  }

  // ----- Giới hạn token -----

  private void limits(JsonNode tokens, List<ProblemFieldError> errors) {
    JsonNode body = tokens.path("font").path("size").path("body");
    if (!body.isMissingNode()) {
      BigDecimal v = px(body, tokens);
      if (v == null || v.compareTo(FONT_MIN) < 0 || v.compareTo(FONT_MAX) > 0) {
        errors.add(
            new ProblemFieldError(
                "tokens.font.size.body", "Cỡ chữ cơ sở phải từ 12 px đến 16 px", null));
      }
    }
    Map<String, JsonNode> radii = new LinkedHashMap<>();
    leaves(tokens.path("radius"), "tokens.radius", radii);
    radii.forEach(
        (field, node) -> {
          BigDecimal v = px(node, tokens);
          if (v == null || v.compareTo(RADIUS_MAX) > 0) {
            errors.add(new ProblemFieldError(field, "Bo góc phải từ 0 px đến 16 px", null));
          }
        });
  }

  private static void leaves(JsonNode group, String path, Map<String, JsonNode> out) {
    for (Iterator<Map.Entry<String, JsonNode>> it = group.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      if (e.getKey().startsWith("$") || !e.getValue().isObject()) {
        continue;
      }
      if (e.getValue().has("$value")) {
        out.put(path + "." + e.getKey(), e.getValue());
      } else {
        leaves(e.getValue(), path + "." + e.getKey(), out);
      }
    }
  }

  /** Giá trị kích thước theo px ({@code rem} tính 16 px), {@code null} khi không đọc được. */
  static BigDecimal px(JsonNode token, JsonNode root) {
    JsonNode value = token.has("$value") ? token.get("$value") : token;
    String s = resolve(value, root, 0).trim().toLowerCase(Locale.ROOT);
    Matcher m = DIMENSION.matcher(s);
    if (!m.matches()) {
      return null;
    }
    BigDecimal n = new BigDecimal(m.group(1));
    if (m.group(2) == null && n.signum() != 0) {
      return null;
    }
    return "rem".equals(m.group(2)) ? n.multiply(BigDecimal.valueOf(16)) : n;
  }

  // ----- Tài nguyên -----

  private void assets(TenantId tenantId, JsonNode list, List<ProblemFieldError> errors) {
    for (int i = 0; i < list.size(); i++) {
      JsonNode a = list.get(i);
      String field = "assets[" + i + "]";
      String media = a.path("mediaType").asText("");
      if (!MEDIA.contains(media)) {
        errors.add(new ProblemFieldError(field + ".mediaType", "Loại tệp không được phép", null));
        continue;
      }
      Optional<byte[]> content = assets.read(tenantId, a.path("path").asText(""));
      if (content.isEmpty()) {
        errors.add(
            new ProblemFieldError(
                field + ".path", "Chưa có tệp tài nguyên để kiểm tra, hãy tải tệp lên lại", null));
        continue;
      }
      byte[] bytes = content.get();
      if (bytes.length > ASSET_MAX_BYTES) {
        errors.add(new ProblemFieldError(field + ".sizeBytes", "Tệp vượt quá 50 KB", null));
      }
      if (a.has("sizeBytes") && a.get("sizeBytes").asLong() != bytes.length) {
        errors.add(
            new ProblemFieldError(
                field + ".sizeBytes", "Kích thước khai báo không khớp tệp", null));
      }
      if (!sha256(bytes).equals(a.path("sha256").asText(""))) {
        errors.add(new ProblemFieldError(field + ".sha256", "Mã băm không khớp tệp", null));
      }
      List<String> problems =
          switch (media) {
            case "image/svg+xml" -> SvgSanitizer.violations(bytes);
            case "image/png" -> startsWith(bytes, PNG) ? List.of() : List.of("Tệp không phải PNG");
            default -> startsWith(bytes, WOFF2) ? List.of() : List.of("Tệp không phải WOFF2");
          };
      problems.forEach(p -> errors.add(new ProblemFieldError(field + ".path", p, null)));
    }
  }

  private static boolean startsWith(byte[] bytes, byte[] magic) {
    if (bytes.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (bytes[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ----- Biểu mẫu -----

  private void forms(TenantId tenantId, JsonNode document, List<ProblemFieldError> errors) {
    JsonNode forms = document.path("forms");
    String range = document.path("requires").path("policyPack").asText(null);
    for (Iterator<Map.Entry<String, JsonNode>> it = forms.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> form = it.next();
      String field = "forms." + form.getKey();
      String ref = form.getValue().path("schemaRef").asText("");
      Optional<Set<String>> fields =
          schemas == null ? Optional.empty() : schemas.fields(tenantId, ref, range);
      if (fields.isEmpty()) {
        errors.add(
            new ProblemFieldError(
                field + ".schemaRef",
                "Không tìm thấy biểu mẫu trong bộ quy tắc đánh giá đã chọn",
                null));
        continue;
      }
      JsonNode sections = form.getValue().path("ui").path("sections");
      for (int s = 0; s < sections.size(); s++) {
        JsonNode names = sections.get(s).path("fields");
        for (int f = 0; f < names.size(); f++) {
          if (!fields.get().contains(names.get(f).asText())) {
            errors.add(
                new ProblemFieldError(
                    field + ".ui.sections[" + s + "].fields[" + f + "]",
                    "Trường không có trong biểu mẫu của bộ quy tắc đánh giá",
                    null));
          }
        }
      }
    }
  }
}
