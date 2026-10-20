package vn.viettel.okrkpi.platform.db.migration.guard;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình {@code vt.db.schema-guard}: danh sách schema và phiên bản tối thiểu. Ví dụ mission-core
 * khai một mục cho mỗi schema mô-đun ({@code okr}, {@code task}...).
 *
 * <pre>
 * vt.db.schema-guard.schemas[0].name=task
 * vt.db.schema-guard.schemas[0].min-version=1.3
 * </pre>
 */
@ConfigurationProperties(prefix = "vt.db.schema-guard")
public class SchemaVersionGuardProperties {

  /** Bật kiểm tra (mặc định bật khi có ít nhất một schema). */
  private boolean enabled = true;

  private List<SchemaRequirement> schemas = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<SchemaRequirement> getSchemas() {
    return schemas;
  }

  public void setSchemas(List<SchemaRequirement> schemas) {
    this.schemas = new ArrayList<>(schemas);
  }

  /** Một schema và phiên bản Flyway tối thiểu. */
  public static class SchemaRequirement {
    private String name;
    private String minVersion;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getMinVersion() {
      return minVersion;
    }

    public void setMinVersion(String minVersion) {
      this.minVersion = minVersion;
    }
  }
}
