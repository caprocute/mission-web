package vn.viettel.okrkpi.shared.datasource;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tám mô-đun nghiệp vụ của {@code SVC-05 mission-core} (MOD-01..MOD-08) cùng thông số pool mặc định
 * theo TL-01 "Quản lý kết nối CSDL" và TL-04 "Ngân sách kết nối CSDL mỗi pod".
 *
 * <p>Tổng {@code defaultMaxPoolSize} của 8 mô-đun là {@code 8 + 4 + 4 + 3 + 2 + 1 + 1 + 1 = 24},
 * nhỏ hơn trần {@code 35} kết nối/pod.
 */
public enum ModuleName {
  OKR("okr", "MOD-01", 4, 2),
  TASK("task", "MOD-02", 8, 2),
  PRODUCT("product", "MOD-03", 3, 1),
  APPRAISAL("appraisal", "MOD-04", 4, 1),
  POLICY("policy", "MOD-05", 1, 1),
  DOSSIER("dossier", "MOD-06", 1, 1),
  OVERSIGHT("oversight", "MOD-07", 1, 1),
  CONFIG("config", "MOD-08", 2, 1);

  /** Trần tổng kích thước pool tối đa của 8 mô-đun nghiệp vụ trên 1 pod (TL-04). */
  public static final int MAX_TOTAL_POOL_PER_POD = 35;

  private static final Map<String, ModuleName> BY_SCHEMA =
      Arrays.stream(values())
          .collect(Collectors.toUnmodifiableMap(ModuleName::schema, Function.identity()));

  private final String schema;
  private final String moduleCode;
  private final int defaultMaxPoolSize;
  private final int defaultMinIdle;

  ModuleName(String schema, String moduleCode, int defaultMaxPoolSize, int defaultMinIdle) {
    this.schema = schema;
    this.moduleCode = moduleCode;
    this.defaultMaxPoolSize = defaultMaxPoolSize;
    this.defaultMinIdle = defaultMinIdle;
  }

  /** Tên schema PostgreSQL (trùng khoá cấu hình {@code okrkpi.datasources.<schema>}). */
  public String schema() {
    return schema;
  }

  /** Mã định danh mô-đun theo baseline ({@code MOD-01}..{@code MOD-08}). */
  public String moduleCode() {
    return moduleCode;
  }

  /** Kích thước pool tối đa mặc định trên một pod. */
  public int defaultMaxPoolSize() {
    return defaultMaxPoolSize;
  }

  /** Số kết nối nhàn rỗi tối thiểu mặc định. */
  public int defaultMinIdle() {
    return defaultMinIdle;
  }

  /**
   * Gói chứa thực thể JPA của mô-đun ({@code
   * vn.viettel.okrkpi.<schema>.infrastructure.persistence}).
   */
  public String entityPackage() {
    return "vn.viettel.okrkpi." + schema + ".infrastructure.persistence";
  }

  /** Tên ứng dụng gắn vào kết nối PostgreSQL ({@code mission-core-<schema>}). */
  public String applicationName() {
    return "mission-core-" + schema;
  }

  /** Tra cứu theo tên schema (không phân biệt hoa thường). */
  public static ModuleName fromSchema(String schema) {
    if (schema == null) {
      throw new IllegalArgumentException("Tên schema không được rỗng");
    }
    ModuleName found = BY_SCHEMA.get(schema.toLowerCase(Locale.ROOT));
    if (found == null) {
      throw new IllegalArgumentException("Không nhận diện được mô-đun nghiệp vụ: " + schema);
    }
    return found;
  }
}
