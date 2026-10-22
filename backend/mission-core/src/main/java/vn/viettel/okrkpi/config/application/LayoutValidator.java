package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelExecutionException;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.domain.UiComponent;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm bố cục SDUI của UI Pack theo component registry (CORE-08-09, BR-18-02, TL-01 "An toàn của
 * SDUI"): component phải có trong phiên bản registry thuộc dải {@code requires.registry} ({@code
 * MSG-18-02}), chỉ component bố cục được chứa nút con, {@code props} đúng {@code propsSchema}, tối
 * đa {@value #WIDGETS_MAX} widget mỗi bố cục, chuỗi không chứa thẻ HTML hay {@code javascript:},
 * URL chỉ {@code https:} hoặc đường dẫn tương đối trong danh sách cho phép, {@code visibleWhen} là
 * CEL kiểu bool chỉ đọc biến {@code user}, trong giới hạn độ dài và độ phức tạp.
 *
 * <p>Ba kết quả theo thứ tự: {@code REGISTRY} (component lạ, {@code UI-422-UNKNOWN-COMPONENT}),
 * {@code SCHEMA} khi thuộc tính hoặc cấu trúc sai ({@code COMMON-422-VALIDATION}), {@code
 * CEL_PARITY} cho {@code visibleWhen} ({@code UI-422-CEL-INVALID}).
 */
public class LayoutValidator implements UiPackChecker {

  public static final int WIDGETS_MAX = 12;

  /**
   * Giới hạn {@code visibleWhen}: độ dài theo hợp đồng {@code SduiNode}, AST nhỏ, tối đa 100 vòng
   * lặp (suy luận). Chi phí bị chặn bởi các giới hạn đó; thời gian chạy thử để rộng 2 giây vì đây
   * là bước kiểm khi lưu, không nằm trên đường phục vụ, và lần chạy đầu trên máy tải nặng có thể
   * vượt 50 ms mặc định (ca chập chờn ghi nhận khi gộp CORE-08-09).
   */
  public static final CelSandboxOptions VISIBLE_WHEN_LIMITS =
      new CelSandboxOptions(500, 12, 64, 100, Duration.ofSeconds(2), 512);

  /** Ngữ cảnh người dùng mà renderer web cung cấp cho {@code visibleWhen} (cel-context.ts). */
  public static final Map<String, CelValueType> CONTEXT =
      Map.of("user", CelValueType.MAP_STRING_DYN);

  /** Ngữ cảnh mẫu đúng hình dạng {@code user} của renderer, để chạy thử {@code visibleWhen}. */
  private static final Map<String, Object> SAMPLE =
      Map.of(
          "user", Map.of("id", "", "roles", List.of("tenant_admin"), "tenant", "", "unitId", ""));

  private static final Pattern HTML_TAG = Pattern.compile("<\\s*[A-Za-z/!?]");
  private static final Pattern SCRIPT_SCHEME =
      Pattern.compile("(?i)(java|vb)script\\s*:|data\\s*:\\s*text/html");
  private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

  private final ComponentRegistryService registry;
  private final CelEngine cel;
  private final ObjectMapper json;
  private final List<String> relativePrefixes;
  private final Map<String, UiPackSchema> propsSchemas = new ConcurrentHashMap<>();

  /**
   * @param relativePrefixes tiền tố đường dẫn tương đối được phép trong thuộc tính, ví dụ {@code /}
   */
  public LayoutValidator(
      ComponentRegistryService registry, ObjectMapper json, List<String> relativePrefixes) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.json = Objects.requireNonNull(json, "json");
    this.relativePrefixes = List.copyOf(relativePrefixes);
    this.cel = new CelEngine(StaticWorkingCalendar.weekendsOnly(), VISIBLE_WHEN_LIMITS);
  }

  @Override
  public List<Result> check(TenantId tenantId, JsonNode document) {
    String range = document.path("requires").path("registry").asText("");
    String version = registry.resolve(range).orElse(null);
    if (version == null) {
      return List.of(
          new Result(
              Code.REGISTRY,
              false,
              "Chưa có danh mục thành phần phù hợp với phiên bản " + range + ".",
              null,
              List.of(
                  new ProblemFieldError(
                      "componentRegistryVersion",
                      "Phiên bản danh mục chưa được phát hành",
                      null))));
    }
    Map<String, UiComponent> components =
        registry.components(tenantId, version).stream()
            .filter(c -> c.status() == UiComponent.Status.HIEU_LUC)
            .collect(Collectors.toMap(UiComponent::componentKey, Function.identity()));
    Walk walk = new Walk(version, components);
    JsonNode layouts = document.path("layouts");
    for (Iterator<Map.Entry<String, JsonNode>> it = layouts.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> layout = it.next();
      String path = "layouts." + layout.getKey();
      int widgets = walk.node(layout.getValue(), path);
      if (widgets > WIDGETS_MAX) {
        walk.structure.add(
            new ProblemFieldError(
                path, "Bố cục có tối đa " + WIDGETS_MAX + " thành phần hiển thị", null));
      }
    }
    List<Result> results = new ArrayList<>();
    results.add(
        walk.unknown.isEmpty()
            ? Result.ok(Code.REGISTRY, "Mọi thành phần đều có trong danh mục " + version + ".")
            : new Result(
                Code.REGISTRY, false, walk.unknown.get(0).message(), "MSG-18-02", walk.unknown));
    if (!walk.structure.isEmpty()) {
      results.add(
          new Result(
              Code.SCHEMA, false, "Thuộc tính của thành phần chưa hợp lệ.", null, walk.structure));
    }
    results.add(
        walk.expressions.isEmpty()
            ? Result.ok(Code.CEL_PARITY, "Điều kiện hiển thị hợp lệ.")
            : new Result(
                Code.CEL_PARITY, false, "Điều kiện hiển thị chưa hợp lệ.", null, walk.expressions));
    return results;
  }

  /** Lỗi gom theo nhóm khi duyệt cây. */
  private final class Walk {
    final String version;
    final Map<String, UiComponent> components;
    final List<ProblemFieldError> unknown = new ArrayList<>();
    final List<ProblemFieldError> structure = new ArrayList<>();
    final List<ProblemFieldError> expressions = new ArrayList<>();

    Walk(String version, Map<String, UiComponent> components) {
      this.version = version;
      this.components = components;
    }

    /**
     * @return số widget trong cây con
     */
    int node(JsonNode node, String path) {
      String key = node.path("component").asText("");
      UiComponent component = components.get(key);
      int widgets = 0;
      if (component == null) {
        unknown.add(
            new ProblemFieldError(
                path + ".component",
                "Component " + key + " không có trong danh mục cho phép phiên bản " + version + ".",
                null));
      } else {
        widgets += component.widget() ? 1 : 0;
        props(component, node.path("props"), path + ".props");
        if (node.path("children").size() > 0 && !component.container()) {
          structure.add(
              new ProblemFieldError(
                  path + ".children", "Thành phần này không chứa được thành phần con", null));
        }
      }
      if (node.hasNonNull("visibleWhen")) {
        expression(node.get("visibleWhen").asText(), path + ".visibleWhen");
      }
      JsonNode children = node.path("children");
      for (int i = 0; i < children.size(); i++) {
        widgets += node(children.get(i), path + ".children[" + i + "]");
      }
      return widgets;
    }

    void props(UiComponent component, JsonNode props, String path) {
      JsonNode value = props.isMissingNode() || props.isNull() ? json.createObjectNode() : props;
      for (ProblemFieldError e : schemaOf(component).validate(value)) {
        String field = e.field().equals("$") ? path : path + "." + e.field();
        structure.add(new ProblemFieldError(field, e.message(), e.rule()));
      }
      strings(value, path);
    }

    void strings(JsonNode value, String path) {
      if (value.isTextual()) {
        String problem = unsafe(value.asText());
        if (problem != null) {
          structure.add(new ProblemFieldError(path, problem, null));
        }
      } else if (value.isArray()) {
        for (int i = 0; i < value.size(); i++) {
          strings(value.get(i), path + "[" + i + "]");
        }
      } else if (value.isObject()) {
        value.fields().forEachRemaining(f -> strings(f.getValue(), path + "." + f.getKey()));
      }
    }

    void expression(String expression, String path) {
      try {
        // Biến kiểu dyn qua được kiểm kiểu tĩnh, nên chạy thử trên ngữ cảnh mẫu để chắc kết quả là
        // bool và chỉ đọc các trường renderer cung cấp.
        Object sample = cel.evaluate(cel.compile(expression, CONTEXT, CelValueType.BOOL), SAMPLE);
        if (!(sample instanceof Boolean)) {
          expressions.add(
              new ProblemFieldError(path, "Điều kiện hiển thị phải trả về đúng hoặc sai", "BOOL"));
        }
      } catch (CelExecutionException e) {
        expressions.add(
            new ProblemFieldError(
                path, "Điều kiện hiển thị dùng thông tin người dùng không có sẵn", "RUNTIME"));
      } catch (CelCompilationException e) {
        expressions.add(
            new ProblemFieldError(
                path,
                "Điều kiện hiển thị không hợp lệ hoặc không trả về đúng/sai",
                e.issues().isEmpty() ? null : e.issues().get(0).errorKind()));
      }
    }
  }

  /** Lý do chuỗi không an toàn, {@code null} khi an toàn. */
  String unsafe(String s) {
    if (HTML_TAG.matcher(s).find()) {
      return "Không được chứa thẻ HTML";
    }
    if (SCRIPT_SCHEME.matcher(s).find()) {
      return "Không được chứa mã lệnh";
    }
    String t = s.trim();
    if (t.isEmpty() || t.chars().anyMatch(Character::isWhitespace)) {
      return null;
    }
    if (t.startsWith("//")) {
      return "Địa chỉ phải bắt đầu bằng https:// hoặc là đường dẫn nội bộ được phép";
    }
    if (SCHEME.matcher(t).find()) {
      return t.toLowerCase(Locale.ROOT).startsWith("https://")
          ? null
          : "Địa chỉ phải bắt đầu bằng https:// hoặc là đường dẫn nội bộ được phép";
    }
    if (t.startsWith("/") || t.startsWith("./") || t.startsWith("../")) {
      boolean allowed = !t.contains("..") && relativePrefixes.stream().anyMatch(t::startsWith);
      return allowed ? null : "Đường dẫn nội bộ không nằm trong danh sách cho phép";
    }
    return null;
  }

  private UiPackSchema schemaOf(UiComponent component) {
    return propsSchemas.computeIfAbsent(
        component.registryVersion() + "|" + component.componentKey(),
        k -> {
          try {
            return UiPackSchema.of(json.readTree(component.propsSchemaJson()));
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("propsSchema hỏng: " + k, e);
          }
        });
  }
}
