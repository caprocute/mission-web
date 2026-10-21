package vn.viettel.okrkpi.shared.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Danh mục chính sách thao tác {@code operationId -> OperationPolicy} nạp từ tài nguyên {@code
 * operation-policies.json} sinh từ {@code contracts/openapi/modules/*.yaml} (NEN-014).
 */
public final class OperationPolicyCatalog {

  public static final String DEFAULT_RESOURCE =
      "vn/viettel/okrkpi/shared/authz/operation-policies.json";

  private static final OperationPolicyCatalog DEFAULT = loadDefault();

  private final Map<String, OperationPolicy> byOperationId;
  private final Map<String, OperationPolicy> byMethodAndPath;
  private final List<OperationPolicy> aal2Operations;
  private final List<OperationPolicy> fourEyesOperations;

  private OperationPolicyCatalog(Map<String, OperationPolicy> entries) {
    this.byOperationId = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    Map<String, OperationPolicy> methodPathMap = new LinkedHashMap<>();
    List<OperationPolicy> aal2 = new ArrayList<>();
    List<OperationPolicy> fourEyes = new ArrayList<>();
    for (OperationPolicy p : entries.values()) {
      if (p.method() != null && p.path() != null) {
        methodPathMap.put(methodPathKey(p.method(), p.path()), p);
      }
      if (p.requiresStepUp()) {
        aal2.add(p);
      }
      if (p.requiresFourEyes()) {
        fourEyes.add(p);
      }
    }
    this.byMethodAndPath = Collections.unmodifiableMap(methodPathMap);
    this.aal2Operations = Collections.unmodifiableList(aal2);
    this.fourEyesOperations = Collections.unmodifiableList(fourEyes);
  }

  public static OperationPolicyCatalog getDefault() {
    return DEFAULT;
  }

  public static OperationPolicyCatalog loadDefault() {
    return loadFromResource(DEFAULT_RESOURCE);
  }

  public static OperationPolicyCatalog loadFromResource(String resourcePath) {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = OperationPolicyCatalog.class.getClassLoader();
    }
    try (InputStream in = cl.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("Không tìm thấy tài nguyên: " + resourcePath);
      }
      ObjectMapper mapper = new ObjectMapper();
      JsonNode root = mapper.readTree(in);
      JsonNode ops = root.path("operations");
      if (!ops.isArray()) {
        throw new IllegalStateException("Tài nguyên không có mảng 'operations': " + resourcePath);
      }
      Map<String, OperationPolicy> map = new LinkedHashMap<>();
      for (JsonNode n : ops) {
        OperationPolicy policy =
            new OperationPolicy(
                n.path("operationId").asText(),
                textOrNull(n, "file"),
                textOrNull(n, "module"),
                textOrNull(n, "errorModule"),
                textOrNull(n, "method"),
                textOrNull(n, "path"),
                textOrNull(n, "owner"),
                stringList(n.path("qt")),
                stringList(n.path("scr")),
                stringList(n.path("roles")),
                textOrNull(n, "scope"),
                n.path("aal").asInt(1),
                n.path("fourEyes").asBoolean(false),
                textOrNull(n, "source"));
        map.put(policy.operationId(), policy);
      }
      return new OperationPolicyCatalog(map);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Không đọc được bảng chính sách thao tác: " + resourcePath, e);
    }
  }

  public Optional<OperationPolicy> find(String operationId) {
    if (operationId == null || operationId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(byOperationId.get(operationId));
  }

  public OperationPolicy require(String operationId) {
    Objects.requireNonNull(operationId, "operationId must not be null");
    OperationPolicy policy = byOperationId.get(operationId);
    if (policy == null) {
      throw new NoSuchElementException(
          "operationId không có trong hợp đồng OpenAPI: " + operationId);
    }
    return policy;
  }

  public Optional<OperationPolicy> findByMethodAndPath(String method, String path) {
    if (method == null || path == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byMethodAndPath.get(methodPathKey(method, path)));
  }

  public Collection<OperationPolicy> all() {
    return byOperationId.values();
  }

  public List<OperationPolicy> aal2Operations() {
    return aal2Operations;
  }

  public List<OperationPolicy> fourEyesOperations() {
    return fourEyesOperations;
  }

  public int size() {
    return byOperationId.size();
  }

  private static String methodPathKey(String method, String path) {
    return method.toUpperCase(Locale.ROOT) + " " + path.trim();
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static List<String> stringList(JsonNode arrayNode) {
    if (arrayNode == null || !arrayNode.isArray()) {
      return List.of();
    }
    List<String> list = new ArrayList<>(arrayNode.size());
    for (JsonNode item : arrayNode) {
      if (item != null && !item.isNull()) {
        list.add(item.asText());
      }
    }
    return list;
  }
}
