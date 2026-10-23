/// Kiểu tĩnh của CEL dùng khi kiểm kiểu biểu thức.
library;

// Tên giá trị của CelVariableType giữ đúng như hợp đồng CelValueType của vt-cel.
// ignore_for_file: constant_identifier_names

enum CelKind { int, double, bool, string, nullType, list, map, dyn }

final class CelType {
  const CelType._(this.kind, [this.elem, this.key]);

  final CelKind kind;

  /// Kiểu phần tử của `list`, kiểu giá trị của `map`.
  final CelType? elem;

  /// Kiểu khoá của `map`.
  final CelType? key;

  static const intType = CelType._(CelKind.int);
  static const doubleType = CelType._(CelKind.double);
  static const boolType = CelType._(CelKind.bool);
  static const stringType = CelType._(CelKind.string);
  static const nullType = CelType._(CelKind.nullType);
  static const dyn = CelType._(CelKind.dyn);

  static CelType list(CelType elem) => CelType._(CelKind.list, elem);
  static CelType map(CelType key, CelType value) => CelType._(CelKind.map, value, key);

  bool get isDyn => kind == CelKind.dyn;
  bool get isNumeric => kind == CelKind.int || kind == CelKind.double;

  /// `this` dùng được ở chỗ cần `other` (dyn khớp mọi kiểu, null khớp kiểu có thể null).
  bool assignableTo(CelType other) {
    if (isDyn || other.isDyn) return true;
    if (kind != other.kind) return false;
    return switch (kind) {
      CelKind.list => elem!.assignableTo(other.elem!),
      CelKind.map => key!.assignableTo(other.key!) && elem!.assignableTo(other.elem!),
      _ => true,
    };
  }

  @override
  bool operator ==(Object other) => other is CelType && other.kind == kind && other.elem == elem && other.key == key;

  @override
  int get hashCode => Object.hash(kind, elem, key);

  @override
  String toString() => switch (kind) {
    CelKind.list => 'list(${elem!})',
    CelKind.map => 'map(${key!}, ${elem!})',
    CelKind.nullType => 'null_type',
    _ => kind.name,
  };
}

/// Kiểu biến trong hợp đồng biểu thức CEL, như `CelValueType` của vt-cel.
enum CelVariableType {
  INT,
  DOUBLE,
  DECIMAL,
  STRING,
  NULLABLE_STRING,
  BOOL,
  LIST_INT,
  LIST_DOUBLE,
  LIST_STRING,
  LIST_DYN,
  MAP_STRING_DYN,
  DYN;

  CelType get celType => switch (this) {
    INT => CelType.intType,
    DOUBLE || DECIMAL => CelType.doubleType,
    STRING => CelType.stringType,
    // NullableType(string) của cel-java: kiểm tĩnh như string nhưng nhận null
    NULLABLE_STRING => CelType.stringType,
    BOOL => CelType.boolType,
    LIST_INT => CelType.list(CelType.intType),
    LIST_DOUBLE => CelType.list(CelType.doubleType),
    LIST_STRING => CelType.list(CelType.stringType),
    LIST_DYN => CelType.list(CelType.dyn),
    MAP_STRING_DYN => CelType.map(CelType.stringType, CelType.dyn),
    DYN => CelType.dyn,
  };

  /// Đọc tên kiểu như `CelValueType.fromName` (không phân biệt hoa thường, nhận các bí danh).
  static CelVariableType fromName(String raw) {
    final n = raw.trim().toUpperCase().replaceAll('-', '_');
    return switch (n) {
      'INT' || 'INTEGER' || 'LONG' => INT,
      'DOUBLE' || 'FLOAT' || 'NUMBER' => DOUBLE,
      'DECIMAL' => DECIMAL,
      'STRING' || 'TEXT' => STRING,
      'NULLABLE_STRING' => NULLABLE_STRING,
      'BOOL' || 'BOOLEAN' => BOOL,
      'LIST_INT' || 'LIST<INT>' || 'LIST<LONG>' => LIST_INT,
      'LIST_DOUBLE' || 'LIST_DECIMAL' || 'LIST_NUMBER' || 'LIST<DOUBLE>' || 'LIST<DECIMAL>' => LIST_DOUBLE,
      'LIST_STRING' || 'LIST<STRING>' => LIST_STRING,
      'LIST_DYN' || 'LIST' || 'ARRAY' || 'LIST<DYN>' || 'LIST<MAP>' => LIST_DYN,
      'MAP' || 'MAP_STRING_DYN' || 'OBJECT' || 'MAP<STRING,DYN>' => MAP_STRING_DYN,
      'DYN' || 'ANY' => DYN,
      _ => throw ArgumentError.value(raw, 'raw', 'Kiểu dữ liệu CEL không được hỗ trợ'),
    };
  }
}
