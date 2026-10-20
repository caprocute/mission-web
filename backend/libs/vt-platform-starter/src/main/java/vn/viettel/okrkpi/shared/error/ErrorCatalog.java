package vn.viettel.okrkpi.shared.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Danh mục mã lỗi duy nhất của nền tảng, nạp từ {@code error-codes.json} sinh bằng {@code
 * tools/extract-error-codes.mjs} từ {@code contracts/openapi/**} (TL-01: "Danh mục mã lỗi được quản
 * lý trong kho hợp đồng cùng OpenAPI"). Khi nạp, danh mục kiểm: mã đúng dạng, HTTP status khớp phần
 * số, không trùng mã.
 *
 * <p>Ngoại lệ duy nhất: {@code <MODULE>-409-VERSION-CONFLICT} được hợp đồng định nghĩa chung cho
 * mọi module ({@code common.yaml} tham số {@code If-Match}, BS-02 HD-01), nên {@link
 * #versionConflict(String)} dựng được mã này cho module chưa liệt kê riêng.
 */
public final class ErrorCatalog {

  /** Đường dẫn tài nguyên của danh mục chuẩn. */
  public static final String RESOURCE = "vn/viettel/okrkpi/shared/error/error-codes.json";

  private static final String VERSION_CONFLICT = "VERSION-CONFLICT";
  private static final Pattern MODULE_PATTERN = Pattern.compile("[A-Z][A-Z0-9]*");

  private final Map<String, ErrorCode> byCode;
  private final Set<String> modules;

  private ErrorCatalog(Map<String, ErrorCode> byCode) {
    this.byCode = Collections.unmodifiableMap(byCode);
    Set<String> m = new TreeSet<>();
    byCode.values().forEach(c -> m.add(c.module()));
    this.modules = Collections.unmodifiableSet(m);
  }

  /** Danh mục chuẩn nạp từ classpath (một lần cho cả tiến trình). */
  public static ErrorCatalog standard() {
    return Holder.STANDARD;
  }

  /**
   * Đọc danh mục từ JSON có dạng {@code {"codes":[{"code":..,"status":..,"type":..}]}}.
   *
   * @throws IllegalStateException khi danh mục sai (mã sai dạng, status lệch, mã trùng)
   */
  public static ErrorCatalog fromJson(InputStream json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được danh mục mã lỗi", e);
    }
    JsonNode codes = root == null ? null : root.get("codes");
    if (codes == null || !codes.isArray()) {
      throw new IllegalStateException("Danh mục mã lỗi thiếu mảng codes");
    }
    Map<String, ErrorCode> byCode = new LinkedHashMap<>();
    for (JsonNode node : codes) {
      ErrorCode code;
      try {
        code = toErrorCode(node);
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new IllegalStateException("Mục sai trong danh mục mã lỗi: " + node, e);
      }
      if (byCode.putIfAbsent(code.code(), code) != null) {
        throw new IllegalStateException("Mã lỗi trùng trong danh mục: " + code.code());
      }
    }
    return new ErrorCatalog(byCode);
  }

  private static ErrorCode toErrorCode(JsonNode node) {
    String code = text(node, "code");
    var m = ErrorCode.CODE_PATTERN.matcher(Objects.requireNonNull(code, "code"));
    if (!m.matches()) {
      throw new IllegalArgumentException("Mã lỗi sai dạng: " + code);
    }
    int status = node.path("status").asInt(-1);
    String title = text(node, "title");
    return new ErrorCode(
        code,
        Optional.ofNullable(text(node, "module")).orElse(m.group(1)),
        status,
        Optional.ofNullable(text(node, "name")).orElse(m.group(3)),
        text(node, "type"),
        title != null ? title : ProblemTypes.forStatus(status).title(),
        text(node, "msg"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  public Optional<ErrorCode> find(String code) {
    return Optional.ofNullable(code).map(byCode::get);
  }

  /**
   * Mã lỗi có trong danh mục.
   *
   * @throws IllegalArgumentException khi mã không có trong danh mục
   */
  public ErrorCode require(String code) {
    return find(code)
        .orElseThrow(
            () -> new IllegalArgumentException("Mã lỗi không có trong danh mục hợp đồng: " + code));
  }

  public boolean contains(String code) {
    return code != null && byCode.containsKey(code);
  }

  /** Mọi mã, theo thứ tự trong tệp. */
  public Collection<ErrorCode> all() {
    return byCode.values();
  }

  public int size() {
    return byCode.size();
  }

  /** Các phần MODULE có trong danh mục. */
  public Set<String> modules() {
    return modules;
  }

  /**
   * Mã {@code <MODULE>-409-VERSION-CONFLICT} của module (BS-02 HD-01): lấy từ danh mục nếu có, nếu
   * không thì dựng theo mẫu chung của hợp đồng.
   */
  public ErrorCode versionConflict(String module) {
    Objects.requireNonNull(module, "module");
    if (!MODULE_PATTERN.matcher(module).matches()) {
      throw new IllegalArgumentException("Tên module sai dạng: " + module);
    }
    String code = module + "-409-" + VERSION_CONFLICT;
    return find(code)
        .orElseGet(
            () ->
                new ErrorCode(
                    code,
                    module,
                    409,
                    VERSION_CONFLICT,
                    ProblemTypes.CONCURRENT_MODIFICATION,
                    ProblemTypes.CONCURRENT_MODIFICATION_TITLE,
                    null));
  }

  private static final class Holder {
    private static final ErrorCatalog STANDARD = load();

    private static ErrorCatalog load() {
      ClassLoader cl = ErrorCatalog.class.getClassLoader();
      try (InputStream in = cl.getResourceAsStream(RESOURCE)) {
        if (in == null) {
          throw new IllegalStateException("Thiếu tài nguyên danh mục mã lỗi " + RESOURCE);
        }
        return fromJson(in);
      } catch (IOException e) {
        throw new UncheckedIOException("Không đọc được danh mục mã lỗi " + RESOURCE, e);
      }
    }
  }
}
