/// Bộ thông dịch cây CEL đã kiểm kiểu. Giá trị lúc chạy là kiểu Dart thuần: `int` (int64 của CEL),
/// `double`, `bool`, `String`, `null`, `List`, `Map`.
library;

import 'ast.dart';
import 'checker.dart';
import 'functions.dart';

class CelInterpreter {
  CelInterpreter(this.checked, this.vars, {required this.maxIterations, required this.deadline});

  final CelChecked checked;
  final Map<String, Object?> vars;
  final int maxIterations;

  /// Trả `true` khi đã quá thời gian chạy; kiểm ở mỗi vòng lặp macro.
  final bool Function() deadline;

  final _scopes = <Map<String, Object?>>[];
  var _iterations = 0;

  Object? run() => _eval(checked.root);

  Object? _lookup(String name) {
    for (final s in _scopes.reversed) {
      if (s.containsKey(name)) return s[name];
    }
    if (!vars.containsKey(name)) {
      throw CelRuntimeError("Unknown variable: '$name'", CelRuntimeErrorKind.missing);
    }
    return vars[name];
  }

  Object? _eval(CelExpr e) {
    switch (e) {
      case CelLiteral(:final value):
        return value;
      case CelIdent(:final name):
        return _lookup(name);
      case CelSelect(:final operand, :final field, :final testOnly):
        final m = _eval(operand);
        if (m is! Map) {
          throw CelRuntimeError("no such overload: select '$field' on ${celTypeName(m)}");
        }
        if (testOnly) return m.containsKey(field);
        if (!m.containsKey(field)) {
          throw CelRuntimeError("no such key: '$field'", CelRuntimeErrorKind.missing);
        }
        return m[field];
      case CelList(:final elements):
        return [for (final x in elements) _eval(x)];
      case CelMap(:final entries):
        final out = <Object?, Object?>{};
        for (final en in entries) {
          final k = _eval(en.key);
          if (out.containsKey(k)) throw CelRuntimeError('Failed with repeated key: $k');
          out[k] = _eval(en.value);
        }
        return out;
      case CelComprehension():
        return _comprehension(e);
      case CelCall():
        return _call(e);
    }
  }

  bool _bool(Object? v, String op) {
    if (v is bool) return v;
    throw CelRuntimeError("no such overload: '$op' applied to ${celTypeName(v)}");
  }

  Object? _comprehension(CelComprehension e) {
    final range = _eval(e.range);
    final Iterable<Object?> items = switch (range) {
      List() => range,
      Map() => range.keys,
      _ => throw CelRuntimeError('expression of type ${celTypeName(range)} cannot be range of a comprehension'),
    };
    final scope = <String, Object?>{};
    _scopes.add(scope);
    try {
      var count = 0;
      Object? error;
      final out = <Object?>[];
      final result = e.macro == CelMacro.all;
      for (final item in items) {
        if (++_iterations > maxIterations) {
          throw CelRuntimeError('comprehension iteration limit exceeded', CelRuntimeErrorKind.sandbox);
        }
        if (deadline()) throw CelRuntimeError('evaluation timeout', CelRuntimeErrorKind.timeout);
        scope[e.variable] = item;
        switch (e.macro) {
          case CelMacro.all || CelMacro.exists:
            // all/exists bỏ qua lỗi của phần tử khác khi đã có kết luận, như cel-spec
            try {
              final v = _bool(_eval(e.body), e.macro.name);
              if (e.macro == CelMacro.all && !v) return false;
              if (e.macro == CelMacro.exists && v) return true;
            } on CelRuntimeError catch (err) {
              if (err.kind == CelRuntimeErrorKind.eval || err.kind == CelRuntimeErrorKind.missing) {
                error ??= err;
              } else {
                rethrow;
              }
            }
          case CelMacro.existsOne:
            if (_bool(_eval(e.body), 'exists_one')) count++;
          case CelMacro.filter:
            if (_bool(_eval(e.body), 'filter')) out.add(item);
          case CelMacro.map:
            if (e.filter == null || _bool(_eval(e.filter!), 'map')) out.add(_eval(e.body));
        }
      }
      if (error != null) throw error;
      return switch (e.macro) {
        CelMacro.all || CelMacro.exists => result,
        CelMacro.existsOne => count == 1,
        CelMacro.filter || CelMacro.map => out,
      };
    } finally {
      _scopes.removeLast();
    }
  }

  Object? _logic(CelCall e, bool isAnd) {
    Object? leftErr;
    bool? left;
    try {
      left = _bool(_eval(e.args[0]), e.function);
      if (left != isAnd) return left;
    } on CelRuntimeError catch (err) {
      if (err.kind == CelRuntimeErrorKind.sandbox || err.kind == CelRuntimeErrorKind.timeout) rethrow;
      leftErr = err;
    }
    final right = _bool(_eval(e.args[1]), e.function);
    if (right != isAnd) return right;
    if (leftErr != null) throw leftErr;
    return isAnd;
  }

  Object? _call(CelCall e) {
    final args = e.args;
    switch (e.function) {
      case '_&&_':
        return _logic(e, true);
      case '_||_':
        return _logic(e, false);
      case '!_':
        return !_bool(_eval(args[0]), '!_');
      case '_?_:_':
        return _bool(_eval(args[0]), '_?_:_') ? _eval(args[1]) : _eval(args[2]);
      case '_==_':
        return celEquals(_eval(args[0]), _eval(args[1]));
      case '_!=_':
        return !celEquals(_eval(args[0]), _eval(args[1]));
      case '@in':
        final x = _eval(args[0]), c = _eval(args[1]);
        if (c is List) return c.any((y) => celEquals(x, y));
        if (c is Map) return c.keys.any((y) => celEquals(x, y));
        throw CelRuntimeError("no such overload: '@in' applied to ${celTypeName(c)}");
      case '_[_]':
        final c = _eval(args[0]), i = _eval(args[1]);
        if (c is List) {
          if (i is! int) throw CelRuntimeError('no such overload: list index ${celTypeName(i)}');
          if (i < 0 || i >= c.length) throw CelRuntimeError('index out of bounds: $i');
          return c[i];
        }
        if (c is Map) {
          final key = c.keys.firstWhere((k) => celEquals(k, i), orElse: () => _absent);
          if (identical(key, _absent)) {
            throw CelRuntimeError("no such key: '$i'", CelRuntimeErrorKind.missing);
          }
          return c[key];
        }
        throw CelRuntimeError("no such overload: '_[_]' applied to ${celTypeName(c)}");
      case 'size':
        final v = _eval(e.target ?? args.single);
        return switch (v) {
          String() => v.runes.length,
          List() => v.length,
          Map() => v.length,
          _ => throw CelRuntimeError('no such overload: size(${celTypeName(v)})'),
        };
      case '_+_':
        final a = _eval(args[0]), b = _eval(args[1]);
        if (a is List && b is List) return [...a, ...b];
        return _dispatch(e, [a, b]);
    }
    final resolved = checked.calls[e];
    final isNamespaced = resolved != null && resolved.name != e.function;
    final values = [if (e.target != null && !isNamespaced) _eval(e.target!), for (final a in args) _eval(a)];
    return _dispatch(e, values);
  }

  Object? _dispatch(CelCall e, List<Object?> values) {
    final resolved = checked.calls[e]!;
    for (final o in resolved.overloads) {
      var ok = true;
      for (var i = 0; i < values.length && ok; i++) {
        ok = celValueMatches(values[i], o.params[i]);
      }
      if (!ok) continue;
      try {
        return o.impl(values);
      } on CelRuntimeError {
        rethrow;
      } on ArgumentError catch (err) {
        throw CelRuntimeError('${resolved.name}: ${err.message}');
      } on FormatException catch (err) {
        throw CelRuntimeError('${resolved.name}: ${err.message}');
      }
    }
    throw CelRuntimeError(
      "no matching overload for '${resolved.name}' applied to '(${values.map(celTypeName).join(', ')})'",
    );
  }
}

const _absent = Object();

/// So sánh bằng của CEL: số khác kiểu so theo giá trị, danh sách và bảng so sâu.
bool celEquals(Object? a, Object? b) {
  // int.== của Dart so theo giá trị với double (1 == 1.0), đúng so sánh số khác kiểu của CEL
  if (a is num && b is num) return a == b;
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!celEquals(a[i], b[i])) return false;
    }
    return true;
  }
  if (a is Map && b is Map) {
    if (a.length != b.length) return false;
    for (final k in a.keys) {
      final match = b.keys.where((x) => celEquals(k, x));
      if (match.isEmpty || !celEquals(a[k], b[match.first])) return false;
    }
    return true;
  }
  return a == b;
}
