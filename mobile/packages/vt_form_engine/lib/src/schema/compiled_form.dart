/// Lược đồ biểu mẫu đã biên dịch một lần để kiểm dữ liệu phiếu, chuyển nguyên từ `CompiledForm` của
/// mission-core (CORE-08-14, ADR-05 lớp 3) để kết quả kiểm trên máy trùng máy chủ (BR-18-05).
///
/// Hiểu bộ từ khoá JSON Schema mà trình dựng SCR-1802 sinh ra: `type`, `enum`, `const`, `minimum`,
/// `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`, `minLength`, `maxLength`,
/// `pattern`, `format` (date, date-time, email), `properties`, `required`,
/// `additionalProperties: false`, `items`, `minItems`, `maxItems`; cùng `x-cel` (biến `self`,
/// `form`) và `celRules`. Biến `form` là bảng phẳng khoá dữ liệu → giá trị; `number` thành double,
/// `integer` thành int. `x-cel` và quy tắc chỉ chạy khi trường có giá trị; biểu thức lỗi khi chạy
/// coi như không đạt. Kiểm ở máy chỉ để phản hồi nhanh, máy chủ là kết luận (AGENTS.md quy tắc 6).
library;

import '../cel/cel_type.dart';
import '../cel/mission_cel.dart';
import '../cel/mission_functions.dart';

const defaultFormMessage = 'Giá trị không hợp lệ';

/// Một lỗi của phiếu: đường dẫn chấm của trường (`sang_kien.1.ten`) và thông báo cho người dùng.
class FormViolation {
  const FormViolation(this.path, this.message);
  final String path;
  final String message;
  @override
  String toString() => '$path: $message';
}

/// Định nghĩa biểu mẫu không dùng được (lược đồ hoặc biểu thức không biên dịch được).
class FormDefinitionException implements Exception {
  FormDefinitionException(this.message, [this.cause]);
  final String message;
  final Object? cause;
  @override
  String toString() => 'FormDefinitionException: $message${cause == null ? '' : ' ($cause)'}';
}

/// Quy tắc CEL theo trường của `UiFormVersion.celRules`.
class FormCelRule {
  const FormCelRule({
    required this.field,
    required this.expr,
    this.message = defaultFormMessage,
    this.serverOnly = false,
  });

  factory FormCelRule.fromJson(Map<String, Object?> j) => FormCelRule(
    field: j['field'] as String? ?? '',
    expr: j['expr'] as String? ?? '',
    message: j['message'] as String? ?? defaultFormMessage,
    serverOnly: j['serverOnly'] == true,
  );

  final String field;
  final String expr;
  final String message;

  /// Quy tắc dùng hàm chưa có ở máy khách: chỉ máy chủ kiểm khi gửi.
  final bool serverOnly;
}

final _email = RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$');
final _date = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$');
final _dateTime = RegExp(
  r'^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(Z|[+-](\d{2}):(\d{2}))$',
);

class _Node {
  _Node(this.key, this.path);

  _Node.copyOf(_Node o, this.path)
    : key = o.key,
      types = o.types,
      primary = o.primary,
      enums = o.enums,
      hasConst = o.hasConst,
      constant = o.constant,
      minimum = o.minimum,
      maximum = o.maximum,
      exclusiveMinimum = o.exclusiveMinimum,
      exclusiveMaximum = o.exclusiveMaximum,
      multipleOf = o.multipleOf,
      minLength = o.minLength,
      maxLength = o.maxLength,
      minItems = o.minItems,
      maxItems = o.maxItems,
      pattern = o.pattern,
      format = o.format,
      required = o.required,
      closed = o.closed,
      items = o.items,
      xcel = o.xcel,
      celMessage = o.celMessage;

  final String? key;
  final String path;
  Set<String> types = const {};
  String primary = '';
  List<Object?>? enums;
  bool hasConst = false;
  Object? constant;
  num? minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf;
  int? minLength, maxLength, minItems, maxItems;
  RegExp? pattern;
  String? format;
  final properties = <String, _Node>{};
  List<String> required = const [];
  bool closed = false;
  _Node? items;
  MissionCelProgram? xcel;
  String celMessage = defaultFormMessage;
}

class _Rule {
  const _Rule(this.field, this.program, this.message);
  final String field;
  final MissionCelProgram program;
  final String message;
}

class CompiledForm {
  /// Biên dịch lược đồ và quy tắc; quy tắc `serverOnly` không chạy ở máy khách.
  ///
  /// Ném [FormDefinitionException] khi lược đồ hay biểu thức không biên dịch được.
  CompiledForm(this.cel, Map<String, Object?> schema, [List<FormCelRule> celRules = const []]) {
    _root = _node(schema, null, '');
    for (final r in celRules) {
      if (r.serverOnly) continue;
      _rules.add(_Rule(r.field, _compile(r.expr, null), r.message));
    }
  }

  final MissionCelEngine cel;
  late final _Node _root;
  final _rules = <_Rule>[];
  final _byKey = <String, _Node>{};
  final _c2 = <String>{};

  /// Khoá dữ liệu lớp C2 (BR-18-11): chỉ lưu trong kho mã hoá, không ghi nhật ký.
  Set<String> get c2Fields => Set.unmodifiable(_c2);

  /// Số quy tắc CEL (x-cel và celRules) đã biên dịch.
  int get celCount => _rules.length + _byKey.values.where((n) => n.xcel != null).length + (_root.xcel == null ? 0 : 1);

  /// Bảng phẳng `form` (khoá dữ liệu → giá trị theo kiểu khai báo) mà `x-cel`, `celRules` và
  /// `hideExpression` nhìn thấy.
  Map<String, Object?> flatten(Map<Object?, Object?> payload) {
    final form = <String, Object?>{};
    _flatten(_root, payload, form);
    return form;
  }

  /// Kiểm một phiếu; lỗi theo thứ tự trường trong lược đồ.
  List<FormViolation> check(Object? payload) {
    final errors = <FormViolation>[];
    if (payload is! Map) {
      errors.add(const FormViolation('', 'Dữ liệu biểu mẫu phải là một đối tượng'));
      return errors;
    }
    final form = <String, Object?>{};
    _flatten(_root, payload, form);
    _check(_root, payload, form, errors);
    for (final r in _rules) {
      final field = _byKey[r.field];
      if (form[r.field] == null || field == null) continue;
      if (!_truthy(r.program, {'form': form})) errors.add(FormViolation(field.path, r.message));
    }
    return errors;
  }

  _Node _node(Map<String, Object?> s, String? key, String path) {
    final n = _Node(key, path);
    final type = s['type'];
    if (type != null) {
      final types = <String>{
        if (type is List)
          for (final t in type) '$t'
        else
          '$type',
      };
      n.types = types;
      n.primary = types.firstWhere((t) => t != 'null', orElse: () => '');
    }
    if (s['enum'] is List) n.enums = List.of(s['enum'] as List);
    if (s.containsKey('const')) {
      n.hasConst = true;
      n.constant = s['const'];
    }
    num? dec(String k) => s[k] is num ? s[k] as num : null;
    int? count(String k) =>
        s[k] is int ? s[k] as int : (s[k] is double && (s[k] as double) % 1 == 0 ? (s[k] as double).toInt() : null);
    n.minimum = dec('minimum');
    n.maximum = dec('maximum');
    n.exclusiveMinimum = dec('exclusiveMinimum');
    n.exclusiveMaximum = dec('exclusiveMaximum');
    n.multipleOf = dec('multipleOf');
    n.minLength = count('minLength');
    n.maxLength = count('maxLength');
    n.minItems = count('minItems');
    n.maxItems = count('maxItems');
    if (s.containsKey('pattern')) {
      try {
        n.pattern = RegExp('${s['pattern']}');
      } on FormatException catch (e) {
        throw FormDefinitionException('Mẫu kiểm tra không hợp lệ ở $path', e);
      }
    }
    n.format = s['format'] is String ? s['format'] as String : null;
    if (s['required'] is List) n.required = [for (final r in s['required'] as List) '$r'];
    n.closed = s['additionalProperties'] == false;
    if (s.containsKey('x-cel-message')) n.celMessage = '${s['x-cel-message'] ?? defaultFormMessage}';
    if (s.containsKey('x-cel')) n.xcel = _compile('${s['x-cel']}', _selfType(n.primary));
    if (key != null && s['x-classification'] == 'C2') _c2.add(key);
    if (key != null) _byKey[key] = n;
    final props = s['properties'];
    if (props is Map) {
      for (final p in props.entries) {
        final k = '${p.key}';
        if (p.value is! Map) continue;
        n.properties[k] = _node((p.value as Map).cast<String, Object?>(), k, path.isEmpty ? k : '$path.$k');
      }
    }
    if (s['items'] is Map) n.items = _itemNode((s['items'] as Map).cast<String, Object?>(), path);
    return n;
  }

  /// Phần tử mảng: không có khoá dữ liệu riêng, lỗi mang chỉ số; khoá con không vào bảng phẳng.
  _Node _itemNode(Map<String, Object?> s, String path) {
    final before = Map.of(_byKey);
    final n = _node(s, null, path);
    _byKey
      ..clear()
      ..addAll(before);
    return n;
  }

  MissionCelProgram _compile(String expr, CelVariableType? self) {
    try {
      return cel.compile(expr, {'form': CelVariableType.MAP_STRING_DYN, 'self': ?self}, CelVariableType.BOOL);
    } on MissionCelError catch (e) {
      throw FormDefinitionException('Biểu thức CEL không hợp lệ: $expr', e);
    }
  }

  void _flatten(_Node n, Map<Object?, Object?> value, Map<String, Object?> form) {
    for (final p in n.properties.entries) {
      final v = value[p.key];
      if (v == null) continue;
      form[p.key] = _toCel(v, p.value);
      if (v is Map) _flatten(p.value, v, form);
    }
  }

  void _check(_Node n, Object v, Map<String, Object?> form, List<FormViolation> errors) {
    if (n.types.isNotEmpty && !n.types.any((t) => _matches(t, v))) {
      errors.add(FormViolation(n.path, _typeMessage(n.primary)));
      return;
    }
    if (n.enums != null && !n.enums!.any((e) => _same(e, v))) {
      errors.add(FormViolation(n.path, 'Giá trị không nằm trong danh sách cho phép'));
    }
    if (n.hasConst && !_same(n.constant, v)) errors.add(FormViolation(n.path, defaultFormMessage));
    if (v is num) _numeric(n, v, errors);
    if (v is String) _text(n, v, errors);
    if (v is List) {
      if (n.minItems != null && v.length < n.minItems!) {
        errors.add(FormViolation(n.path, 'Cần ít nhất ${n.minItems} mục'));
      }
      if (n.maxItems != null && v.length > n.maxItems!) {
        errors.add(FormViolation(n.path, 'Tối đa ${n.maxItems} mục'));
      }
      if (n.items != null) {
        for (var i = 0; i < v.length; i++) {
          final Object? item = v[i];
          if (item != null) _check(_indexed(n.items!, '${n.path}.$i'), item, form, errors);
        }
      }
    }
    if (v is Map) {
      for (final r in n.required) {
        final child = v[r];
        if (child == null || (child is String && child.trim().isEmpty)) {
          errors.add(FormViolation(n.properties[r]?.path ?? _join(n.path, r), 'Trường bắt buộc'));
        }
      }
      for (final e in v.entries) {
        final c = n.properties['${e.key}'];
        final Object? child = e.value;
        if (c == null) {
          if (n.closed) errors.add(FormViolation(_join(n.path, '${e.key}'), 'Trường không có trong biểu mẫu'));
        } else if (child != null) {
          _check(c, child, form, errors);
        }
      }
    }
    if (n.xcel != null && !_truthy(n.xcel!, {'form': form, 'self': _toCel(v, n)})) {
      errors.add(FormViolation(n.path, n.celMessage));
    }
  }

  /// Nút phần tử mảng với đường dẫn có chỉ số; dùng lại cấu hình, đổi đường dẫn của cây con.
  _Node _indexed(_Node item, String path) {
    if (path == item.path) return item;
    final copy = _Node.copyOf(item, path);
    for (final p in item.properties.entries) {
      copy.properties[p.key] = _indexed(p.value, _join(path, p.key));
    }
    return copy;
  }

  bool _truthy(MissionCelProgram program, Map<String, Object?> vars) {
    try {
      return cel.evaluate(program, vars) == true;
    } on MissionCelError {
      // Biểu thức lỗi khi chạy (thiếu khoá, chia cho 0...) coi như không đạt: máy chủ là kết luận.
      return false;
    }
  }

  static void _numeric(_Node n, num d, List<FormViolation> errors) {
    final low =
        (n.minimum != null && compareDecimal(d, n.minimum!) < 0) ||
        (n.exclusiveMinimum != null && compareDecimal(d, n.exclusiveMinimum!) <= 0);
    final high =
        (n.maximum != null && compareDecimal(d, n.maximum!) > 0) ||
        (n.exclusiveMaximum != null && compareDecimal(d, n.exclusiveMaximum!) >= 0);
    if (low || high) errors.add(FormViolation(n.path, _rangeMessage(n)));
    if (n.multipleOf != null && n.multipleOf! > 0 && !isDecimalMultiple(d, n.multipleOf!)) {
      errors.add(FormViolation(n.path, 'Giá trị phải là bội số của ${plainDecimal(n.multipleOf!)}'));
    }
  }

  static void _text(_Node n, String s, List<FormViolation> errors) {
    final len = s.runes.length;
    if (n.minLength != null && len < n.minLength!) {
      errors.add(FormViolation(n.path, 'Cần ít nhất ${n.minLength} ký tự'));
    }
    if (n.maxLength != null && len > n.maxLength!) {
      errors.add(FormViolation(n.path, 'Tối đa ${n.maxLength} ký tự'));
    }
    if (n.pattern != null && !n.pattern!.hasMatch(s)) {
      errors.add(FormViolation(n.path, 'Giá trị chưa đúng định dạng'));
    }
    if (n.format != null && !_format(n.format!, s)) {
      errors.add(FormViolation(n.path, 'Giá trị chưa đúng định dạng'));
    }
  }

  static bool _validDate(int y, int m, int d) {
    if (m < 1 || m > 12 || d < 1) return false;
    final dt = DateTime.utc(y, m, d);
    return dt.year == y && dt.month == m && dt.day == d;
  }

  static bool _format(String format, String s) {
    switch (format) {
      case 'date':
        final m = _date.firstMatch(s);
        return m != null && _validDate(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!));
      case 'date-time':
        final m = _dateTime.firstMatch(s);
        if (m == null || !_validDate(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!))) return false;
        final h = int.parse(m[4]!), mi = int.parse(m[5]!), sec = int.parse(m[6] ?? '0');
        final oh = int.parse(m[8] ?? '0'), om = int.parse(m[9] ?? '0');
        return h < 24 && mi < 60 && sec < 60 && oh <= 18 && om < 60;
      case 'email':
        return _email.hasMatch(s);
      default:
        return true;
    }
  }

  static String _rangeMessage(_Node n) {
    final lo = n.minimum ?? n.exclusiveMinimum;
    final hi = n.maximum ?? n.exclusiveMaximum;
    if (lo != null && hi != null) return 'Giá trị phải từ ${plainDecimal(lo)} đến ${plainDecimal(hi)}';
    if (lo != null) return '${n.minimum != null ? 'Giá trị phải từ ' : 'Giá trị phải lớn hơn '}${plainDecimal(lo)}';
    return '${n.maximum != null ? 'Giá trị tối đa là ' : 'Giá trị phải nhỏ hơn '}${plainDecimal(hi!)}';
  }

  static String _typeMessage(String type) => switch (type) {
    'number' => 'Giá trị phải là số',
    'integer' => 'Giá trị phải là số nguyên',
    'string' => 'Giá trị phải là văn bản',
    'boolean' => 'Giá trị phải là có hoặc không',
    'array' => 'Giá trị phải là danh sách',
    'object' => 'Giá trị phải là một nhóm trường',
    _ => defaultFormMessage,
  };

  static bool _matches(String type, Object? v) => switch (type) {
    'object' => v is Map,
    'array' => v is List,
    'string' => v is String,
    'number' => v is num,
    'integer' => v is int || (v is double && v.isFinite && v % 1 == 0),
    'boolean' => v is bool,
    'null' => v == null,
    _ => true,
  };

  static bool _same(Object? a, Object? b) {
    if (a is num && b is num) return compareDecimal(a, b) == 0;
    if (a is List && b is List) {
      return a.length == b.length && List.generate(a.length, (i) => _same(a[i], b[i])).every((x) => x);
    }
    if (a is Map && b is Map) {
      return a.length == b.length && a.keys.every((k) => b.containsKey(k) && _same(a[k], b[k]));
    }
    return a == b;
  }

  /// Giá trị cho CEL theo kiểu khai báo.
  static Object? _toCel(Object? v, _Node? n) {
    final type = n?.primary ?? '';
    return switch (v) {
      num() => switch (type) {
        'number' => v.toDouble(),
        'integer' => v.toInt(),
        _ => v is int ? v : v.toDouble(),
      },
      String() || bool() => v,
      List() => [
        for (final x in v)
          if (x != null) _toCel(x, n?.items),
      ],
      Map() => {
        for (final e in v.entries)
          if (e.value != null) '${e.key}': _toCel(e.value, n?.properties['${e.key}']),
      },
      _ => null,
    };
  }

  static CelVariableType _selfType(String type) => switch (type) {
    'number' => CelVariableType.DOUBLE,
    'integer' => CelVariableType.INT,
    'string' => CelVariableType.STRING,
    'boolean' => CelVariableType.BOOL,
    'array' => CelVariableType.LIST_DYN,
    'object' => CelVariableType.MAP_STRING_DYN,
    _ => CelVariableType.DYN,
  };

  static String _join(String path, String key) => path.isEmpty ? key : '$path.$key';
}
