/// Bảng hàm có kiểu của môi trường CEL Mission: hàm chuẩn của cel-spec mà Mission dùng, phần mở
/// rộng `math.least`, `math.greatest`, chuỗi, và các hàm `sum`, `min_muc`, `ngay_lam_viec`, `workdays`.
library;

import 'cel_type.dart';
import 'mission_functions.dart';

/// Lỗi khi chạy; `kind` quyết định mã lỗi chuẩn ở tầng trên.
class CelRuntimeError implements Exception {
  CelRuntimeError(this.message, [this.kind = CelRuntimeErrorKind.eval]);
  final String message;
  final CelRuntimeErrorKind kind;
  @override
  String toString() => message;
}

enum CelRuntimeErrorKind { eval, missing, sandbox, timeout }

typedef CelImpl = Object? Function(List<Object?> args);

class CelOverload {
  const CelOverload(this.params, this.result, this.impl, {this.receiver = false});

  /// Kiểu tham số; với hàm thành viên, phần tử đầu là kiểu của đối tượng nhận.
  final List<CelType> params;
  final CelType result;
  final CelImpl impl;
  final bool receiver;
}

const _i = CelType.intType, _d = CelType.doubleType, _s = CelType.stringType, _b = CelType.boolType;
final _li = CelType.list(_i), _ld = CelType.list(_d);

const int _minInt = -9223372036854775808;
const int _maxInt = 9223372036854775807;

int _addInt(int a, int b) {
  final r = a + b;
  if (((a ^ r) & (b ^ r)) < 0) throw CelRuntimeError('return error for overflow');
  return r;
}

int _subInt(int a, int b) {
  final r = a - b;
  if (((a ^ b) & (a ^ r)) < 0) throw CelRuntimeError('return error for overflow');
  return r;
}

int _mulInt(int a, int b) {
  final r = a * b;
  if ((a == -1 && b == _minInt) || (b == -1 && a == _minInt) || (a != 0 && r ~/ a != b)) {
    throw CelRuntimeError('return error for overflow');
  }
  return r;
}

Object _pick(List<Object?> args, bool least) {
  final a = args[0] as num, b = args[1] as num;
  return least ? (b < a ? b : a) : (b > a ? b : a);
}

Object _pickList(List<Object?> args, String name, bool least) {
  final list = args[0] as List<Object?>;
  if (list.isEmpty) throw CelRuntimeError('math.$name() requires a non-empty list');
  return list.cast<num>().reduce((a, b) => least ? (b < a ? b : a) : (b > a ? b : a));
}

int _toIntChecked(double v) {
  if (!v.isFinite || v <= -9223372036854775809.0 || v >= 9223372036854775808.0) {
    throw CelRuntimeError('double is out of range for int');
  }
  return v.truncate();
}

String _str(Object? v) => v as String;

/// Bảng hàm theo tên; tên nội bộ của toán tử theo cel-spec (`_+_`, `_<_`...).
Map<String, List<CelOverload>> celFunctionTable(WorkingCalendar calendar) {
  List<CelOverload> cmp(bool Function(int c) ok) {
    bool numCmp(List<Object?> a) => ok((a[0] as num).compareTo(a[1] as num));
    return [
      for (final l in [_i, _d])
        for (final r in [_i, _d]) CelOverload([l, r], _b, numCmp),
      CelOverload([_s, _s], _b, (a) => ok(_str(a[0]).compareTo(_str(a[1])))),
      CelOverload([_b, _b], _b, (a) => ok((a[0] == true ? 1 : 0).compareTo(a[1] == true ? 1 : 0))),
    ];
  }

  final workdays = CelOverload([_s, _s], _i, (a) => countWorkingDays(_str(a[0]), _str(a[1]), calendar));
  final table = <String, List<CelOverload>>{
    '_+_': [
      CelOverload([_i, _i], _i, (a) => _addInt(a[0] as int, a[1] as int)),
      CelOverload([_d, _d], _d, (a) => (a[0] as double) + (a[1] as double)),
      CelOverload([_s, _s], _s, (a) => _str(a[0]) + _str(a[1])),
    ],
    '_-_': [
      CelOverload([_i, _i], _i, (a) => _subInt(a[0] as int, a[1] as int)),
      CelOverload([_d, _d], _d, (a) => (a[0] as double) - (a[1] as double)),
    ],
    '_*_': [
      CelOverload([_i, _i], _i, (a) => _mulInt(a[0] as int, a[1] as int)),
      CelOverload([_d, _d], _d, (a) => (a[0] as double) * (a[1] as double)),
    ],
    '_/_': [
      CelOverload([_i, _i], _i, (a) {
        final x = a[0] as int, y = a[1] as int;
        if (y == 0) throw CelRuntimeError('divide by zero');
        if (x == _minInt && y == -1) throw CelRuntimeError('return error for overflow');
        return x ~/ y;
      }),
      CelOverload([_d, _d], _d, (a) => (a[0] as double) / (a[1] as double)),
    ],
    '_%_': [
      CelOverload([_i, _i], _i, (a) {
        final x = a[0] as int, y = a[1] as int;
        if (y == 0) throw CelRuntimeError('modulus by zero');
        if (x == _minInt && y == -1) throw CelRuntimeError('return error for overflow');
        return x.remainder(y);
      }),
    ],
    '-_': [
      CelOverload([_i], _i, (a) {
        final x = a[0] as int;
        if (x == _minInt) throw CelRuntimeError('return error for overflow');
        return -x;
      }),
      CelOverload([_d], _d, (a) => -(a[0] as double)),
    ],
    '_<_': cmp((c) => c < 0),
    '_<=_': cmp((c) => c <= 0),
    '_>_': cmp((c) => c > 0),
    '_>=_': cmp((c) => c >= 0),
    'int': [
      CelOverload([_i], _i, (a) => a[0]),
      CelOverload([_d], _i, (a) => _toIntChecked(a[0] as double)),
      CelOverload([_s], _i, (a) {
        final v = int.tryParse(_str(a[0]));
        if (v == null) throw CelRuntimeError('cannot convert string to int');
        return v;
      }),
    ],
    'double': [
      CelOverload([_d], _d, (a) => a[0]),
      CelOverload([_i], _d, (a) => (a[0] as int).toDouble()),
      CelOverload([_s], _d, (a) {
        final v = double.tryParse(_str(a[0]));
        if (v == null) throw CelRuntimeError('cannot convert string to double');
        return v;
      }),
    ],
    'string': [
      CelOverload([_s], _s, (a) => a[0]),
      CelOverload([_i], _s, (a) => '${a[0]}'),
      CelOverload([_d], _s, (a) => '${a[0]}'),
      CelOverload([_b], _s, (a) => '${a[0]}'),
    ],
    'contains': [
      CelOverload([_s, _s], _b, (a) => _str(a[0]).contains(_str(a[1])), receiver: true),
    ],
    'startsWith': [
      CelOverload([_s, _s], _b, (a) => _str(a[0]).startsWith(_str(a[1])), receiver: true),
    ],
    'endsWith': [
      CelOverload([_s, _s], _b, (a) => _str(a[0]).endsWith(_str(a[1])), receiver: true),
    ],
    'matches': [
      CelOverload([_s, _s], _b, (a) => RegExp(_str(a[1])).hasMatch(_str(a[0])), receiver: true),
      CelOverload([_s, _s], _b, (a) => RegExp(_str(a[1])).hasMatch(_str(a[0]))),
    ],
    'lowerAscii': [
      CelOverload([_s], _s, (a) => _str(a[0]).toLowerCase(), receiver: true),
    ],
    'upperAscii': [
      CelOverload([_s], _s, (a) => _str(a[0]).toUpperCase(), receiver: true),
    ],
    'trim': [
      CelOverload([_s], _s, (a) => _str(a[0]).trim(), receiver: true),
    ],
    'math.least': [
      CelOverload([_d, _d], _d, (a) => _pick(a, true)),
      CelOverload([_i, _i], _i, (a) => _pick(a, true)),
      CelOverload([_d, _i], _d, (a) => (_pick(a, true) as num).toDouble()),
      CelOverload([_i, _d], _d, (a) => (_pick(a, true) as num).toDouble()),
      CelOverload([_ld], _d, (a) => _pickList(a, 'least', true)),
      CelOverload([_li], _i, (a) => _pickList(a, 'least', true)),
    ],
    'math.greatest': [
      CelOverload([_d, _d], _d, (a) => _pick(a, false)),
      CelOverload([_i, _i], _i, (a) => _pick(a, false)),
      CelOverload([_d, _i], _d, (a) => (_pick(a, false) as num).toDouble()),
      CelOverload([_i, _d], _d, (a) => (_pick(a, false) as num).toDouble()),
      CelOverload([_ld], _d, (a) => _pickList(a, 'greatest', false)),
      CelOverload([_li], _i, (a) => _pickList(a, 'greatest', false)),
    ],
    'sum': [
      CelOverload([_ld], _d, (a) => sumCelValues(a[0] as List<Object?>)),
      CelOverload([_li], _d, (a) => sumCelValues(a[0] as List<Object?>)),
    ],
    'min_muc': [
      CelOverload([_s, _s], _s, (a) => minMucXepLoai(_str(a[0]), _str(a[1]))),
    ],
    'ngay_lam_viec': [workdays],
    'workdays': [workdays],
  };
  return table;
}

/// Giá trị lúc chạy có khớp kiểu tham số không (chỉ xét loại ngoài; phần tử danh sách do hàm kiểm).
bool celValueMatches(Object? v, CelType t) => switch (t.kind) {
  CelKind.dyn => true,
  CelKind.int => v is int,
  CelKind.double => v is double,
  CelKind.bool => v is bool,
  CelKind.string => v is String,
  CelKind.nullType => v == null,
  CelKind.list => v is List && v.every((e) => celValueMatches(e, t.elem!)),
  CelKind.map => v is Map,
};

/// Kiểu lúc chạy của một giá trị, dùng trong thông điệp lỗi.
String celTypeName(Object? v) => switch (v) {
  null => 'null_type',
  int() => 'int',
  double() => 'double',
  bool() => 'bool',
  String() => 'string',
  List() => 'list',
  Map() => 'map',
  _ => v.runtimeType.toString(),
};

/// Giới hạn của int64, dùng khi đọc giá trị đầu vào.
bool celIntInRange(num v) => v >= _minInt && v <= _maxInt;
