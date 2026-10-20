package vn.viettel.okrkpi.cel.domain;

import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.SimpleType;
import java.util.Locale;
import java.util.Objects;

/**
 * Kiểu dữ liệu biến ngữ cảnh và kết quả biểu thức CEL trong Policy Pack và biểu mẫu động ({@code
 * x-cel}).
 *
 * <p>Tham chiếu: NEN-041, ADR-19, TL-01 Mục 1.7 và Mục 7.3.
 */
public enum CelValueType {
  INT(SimpleType.INT),
  DOUBLE(SimpleType.DOUBLE),
  DECIMAL(SimpleType.DOUBLE),
  STRING(SimpleType.STRING),
  NULLABLE_STRING(NullableType.create(SimpleType.STRING)),
  BOOL(SimpleType.BOOL),
  LIST_INT(ListType.create(SimpleType.INT)),
  LIST_DOUBLE(ListType.create(SimpleType.DOUBLE)),
  LIST_STRING(ListType.create(SimpleType.STRING)),
  LIST_DYN(ListType.create(SimpleType.DYN)),
  MAP_STRING_DYN(MapType.create(SimpleType.STRING, SimpleType.DYN)),
  DYN(SimpleType.DYN);

  private final CelType celType;

  CelValueType(CelType celType) {
    this.celType = Objects.requireNonNull(celType, "celType");
  }

  public CelType toCelType() {
    return celType;
  }

  public boolean isNumeric() {
    return this == INT || this == DOUBLE || this == DECIMAL;
  }

  /**
   * Ánh xạ tên kiểu trong Policy Pack hoặc tệp kiểm thử YAML sang {@link CelValueType}.
   *
   * @param rawName tên kiểu (không phân biệt hoa thường)
   * @return kiểu CEL tương ứng
   */
  public static CelValueType fromName(String rawName) {
    if (rawName == null || rawName.isBlank()) {
      throw new IllegalArgumentException("Tên kiểu dữ liệu CEL không được để trống");
    }
    String normalized = rawName.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    return switch (normalized) {
      case "INT", "INTEGER", "LONG" -> INT;
      case "DOUBLE", "FLOAT", "NUMBER" -> DOUBLE;
      case "DECIMAL" -> DECIMAL;
      case "STRING", "TEXT" -> STRING;
      case "NULLABLE_STRING" -> NULLABLE_STRING;
      case "BOOL", "BOOLEAN" -> BOOL;
      case "LIST_INT", "LIST<INT>", "LIST<LONG>" -> LIST_INT;
      case "LIST_DOUBLE", "LIST_DECIMAL", "LIST_NUMBER", "LIST<DOUBLE>", "LIST<DECIMAL>" ->
          LIST_DOUBLE;
      case "LIST_STRING", "LIST<STRING>" -> LIST_STRING;
      case "LIST_DYN", "LIST", "ARRAY", "LIST<DYN>", "LIST<MAP>" -> LIST_DYN;
      case "MAP", "MAP_STRING_DYN", "OBJECT", "MAP<STRING,DYN>" -> MAP_STRING_DYN;
      case "DYN", "ANY" -> DYN;
      default ->
          throw new IllegalArgumentException("Kiểu dữ liệu CEL không được hỗ trợ: " + rawName);
    };
  }
}
