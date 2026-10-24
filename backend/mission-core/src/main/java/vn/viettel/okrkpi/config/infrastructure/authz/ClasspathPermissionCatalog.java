package vn.viettel.okrkpi.config.infrastructure.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import vn.viettel.okrkpi.config.application.port.PermissionCatalogPort;

/**
 * Danh mục quyền đóng gói kèm mission-core ({@code config/domain/permission-catalog.json}, sinh
 * bằng {@code python3 docs/phan-quyen/gen_matrix.py --rego}); cùng tài liệu được trả nguyên ở
 * {@code GET /config/permission-catalog}.
 */
public class ClasspathPermissionCatalog implements PermissionCatalogPort {

  static final String RESOURCE = "/vn/viettel/okrkpi/config/domain/permission-catalog.json";

  private final JsonNode document;
  private final Map<String, Map<String, String>> scopes = new HashMap<>();

  public ClasspathPermissionCatalog(ObjectMapper json) {
    try (InputStream in = ClasspathPermissionCatalog.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu danh mục quyền " + RESOURCE);
      }
      this.document = json.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (JsonNode g : document.path("groups")) {
      for (JsonNode s : g.path("screens")) {
        for (JsonNode f : s.path("functions")) {
          Map<String, String> byRole = new HashMap<>();
          f.path("grants")
              .fields()
              .forEachRemaining(e -> byRole.put(e.getKey(), e.getValue().path("scope").asText()));
          scopes.put(f.path("key").asText(), byRole);
        }
      }
    }
  }

  @Override
  public Optional<String> scopeOf(String functionKey, String role) {
    return Optional.ofNullable(scopes.getOrDefault(functionKey, Map.of()).get(role));
  }

  @Override
  public String version() {
    return document.path("version").asText();
  }

  /** Tài liệu đầy đủ theo lược đồ {@code PermissionCatalog} của hợp đồng. */
  public JsonNode document() {
    return document;
  }
}
