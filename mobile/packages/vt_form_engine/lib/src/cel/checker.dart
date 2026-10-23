/// Bộ kiểm kiểu tĩnh: biến chưa khai báo, hàm không có trong môi trường, toán hạng sai kiểu đều bị
/// từ chối trước khi chạy, như trình kiểm của cel-java (`POLICY-422-CEL-TYPE`).
library;

import 'dart:collection';

import 'ast.dart';
import 'cel_type.dart';
import 'functions.dart';

class CelCheckError implements Exception {
  CelCheckError(this.message, this.offset);
  final String message;
  final int offset;
  @override
  String toString() => 'ERROR at $offset: $message';
}

/// Kết quả kiểm kiểu: kiểu kết quả, biến khai báo được tham chiếu, và lời gọi đã gán hàm.
class CelChecked {
  CelChecked(this.root, this.resultType, this.referenced, this.calls);
  final CelExpr root;
  final CelType resultType;
  final Set<String> referenced;

  /// Lời gọi theo bảng hàm: tên hàm đã phân giải (vd `math.least`) và các overload còn khả dĩ.
  final Map<CelCall, ({String name, List<CelOverload> overloads})> calls;
}

class CelChecker {
  CelChecker(this.variables, this.functions);

  final Map<String, CelType> variables;
  final Map<String, List<CelOverload>> functions;

  final _scopes = <Map<String, CelType>>[];
  final _referenced = <String>{};
  final _calls = HashMap<CelCall, ({String name, List<CelOverload> overloads})>.identity();

  CelChecked check(CelExpr root) {
    final t = _type(root);
    return CelChecked(root, t, _referenced, _calls);
  }

  CelType? _lookup(String name) {
    for (final s in _scopes.reversed) {
      final t = s[name];
      if (t != null) return t;
    }
    final t = variables[name];
    if (t != null) _referenced.add(name);
    return t;
  }

  Never _fail(String msg, CelExpr e) => throw CelCheckError(msg, e.offset);

  CelType _join(CelType a, CelType b) {
    if (a == b) return a;
    if (a.isDyn || b.isDyn) return CelType.dyn;
    if (a.kind == CelKind.nullType) return b;
    if (b.kind == CelKind.nullType) return a;
    return CelType.dyn;
  }

  CelType _type(CelExpr e) {
    switch (e) {
      case CelLiteral(:final value):
        return switch (value) {
          null => CelType.nullType,
          int() => CelType.intType,
          double() => CelType.doubleType,
          bool() => CelType.boolType,
          String() => CelType.stringType,
          _ => _fail('unsupported literal', e),
        };
      case CelIdent(:final name):
        return _lookup(name) ?? _fail("undeclared reference to '$name' (in container '')", e);
      case CelSelect(:final operand, :final field, :final testOnly):
        final t = _type(operand);
        if (t.kind == CelKind.map) {
          if (!t.key!.assignableTo(CelType.stringType)) {
            _fail("type '$t' does not support field selection", e);
          }
          return testOnly ? CelType.boolType : t.elem!;
        }
        if (t.isDyn) return testOnly ? CelType.boolType : CelType.dyn;
        _fail("type '$t' does not support field selection of '$field'", e);
      case CelList(:final elements):
        // cel-java cho phép danh sách khác kiểu phần tử, khi đó kiểu là list(dyn)
        if (elements.isEmpty) return CelType.list(CelType.dyn);
        var t = _type(elements.first);
        for (final x in elements.skip(1)) {
          t = _join(t, _type(x));
        }
        return CelType.list(t);
      case CelMap(:final entries):
        if (entries.isEmpty) return CelType.map(CelType.dyn, CelType.dyn);
        var k = _type(entries.first.key), v = _type(entries.first.value);
        for (final en in entries.skip(1)) {
          k = _join(k, _type(en.key));
          v = _join(v, _type(en.value));
        }
        if (!{CelKind.int, CelKind.string, CelKind.bool, CelKind.dyn}.contains(k.kind)) {
          _fail("unsupported map key type '$k'", e);
        }
        return CelType.map(k, v);
      case CelComprehension(:final macro, :final range, :final variable, :final body, :final filter):
        final r = _type(range);
        final CelType elem;
        if (r.kind == CelKind.list) {
          elem = r.elem!;
        } else if (r.kind == CelKind.map) {
          elem = r.key!;
        } else if (r.isDyn) {
          elem = CelType.dyn;
        } else {
          _fail("expression of type '$r' cannot be range of a comprehension", e);
        }
        _scopes.add({variable: elem});
        try {
          if (filter != null) _expectBool(filter);
          if (macro == CelMacro.map) return CelType.list(_type(body));
          _expectBool(body);
          return macro == CelMacro.filter ? CelType.list(elem) : CelType.boolType;
        } finally {
          _scopes.removeLast();
        }
      case CelCall():
        return _call(e);
    }
  }

  void _expectBool(CelExpr e) {
    final t = _type(e);
    if (!t.assignableTo(CelType.boolType)) _fail("expected type 'bool' but found '$t'", e);
  }

  CelType _call(CelCall e) {
    final args = e.args;
    switch (e.function) {
      case '_&&_' || '_||_':
        args.forEach(_expectBool);
        return CelType.boolType;
      case '!_':
        _expectBool(args[0]);
        return CelType.boolType;
      case '_?_:_':
        _expectBool(args[0]);
        final a = _type(args[1]), b = _type(args[2]);
        if (a.assignableTo(b) || a.kind == CelKind.nullType || b.kind == CelKind.nullType) {
          return _join(a, b);
        }
        _fail("found no matching overload for '_?_:_' applied to '(bool, $a, $b)'", e);
      case '_==_' || '_!=_':
        final a = _type(args[0]), b = _type(args[1]);
        final ok =
            a.assignableTo(b) ||
            (a.isNumeric && b.isNumeric) ||
            a.kind == CelKind.nullType ||
            b.kind == CelKind.nullType;
        if (!ok) _fail("found no matching overload for '${e.function}' applied to '($a, $b)'", e);
        return CelType.boolType;
      case '@in':
        final a = _type(args[0]), c = _type(args[1]);
        final CelType? want = switch (c.kind) {
          CelKind.list => c.elem,
          CelKind.map => c.key,
          CelKind.dyn => CelType.dyn,
          _ => null,
        };
        if (want == null || !(a.assignableTo(want) || (a.isNumeric && want.isNumeric))) {
          _fail("found no matching overload for '@in' applied to '($a, $c)'", e);
        }
        return CelType.boolType;
      case '_[_]':
        final c = _type(args[0]), i = _type(args[1]);
        if (c.kind == CelKind.list && i.assignableTo(CelType.intType)) return c.elem!;
        if (c.kind == CelKind.map && i.assignableTo(c.key!)) return c.elem!;
        if (c.isDyn) return CelType.dyn;
        _fail("found no matching overload for '_[_]' applied to '($c, $i)'", e);
      case 'size':
        final targets = [?e.target, ...args];
        if (targets.length != 1) _fail("found no matching overload for 'size'", e);
        final t = _type(targets.single);
        if (t.isDyn || const {CelKind.string, CelKind.list, CelKind.map}.contains(t.kind)) {
          return CelType.intType;
        }
        _fail("found no matching overload for 'size' applied to '($t)'", e);
      case '_+_':
        final a = _type(args[0]), b = _type(args[1]);
        if (a.kind == CelKind.list && b.kind == CelKind.list) return CelType.list(_join(a.elem!, b.elem!));
        return _tableCall(e, '_+_', [a, b], receiver: false);
    }
    // Hàm theo không gian tên (math.least): `math` không phải biến thì `math.least` là tên hàm
    final target = e.target;
    if (target is CelIdent && _lookup(target.name) == null) {
      final name = '${target.name}.${e.function}';
      if (functions.containsKey(name)) {
        return _tableCall(e, name, [for (final a in args) _type(a)], receiver: false);
      }
      _fail("undeclared reference to '$name' (in container '')", e);
    }
    final types = [if (target != null) _type(target), for (final a in args) _type(a)];
    return _tableCall(e, e.function, types, receiver: target != null);
  }

  CelType _tableCall(CelCall e, String name, List<CelType> types, {required bool receiver}) {
    final all = functions[name];
    if (all == null) _fail("undeclared reference to '$name' (in container '')", e);
    final matching = [
      for (final o in all)
        if (o.receiver == receiver &&
            o.params.length == types.length &&
            List.generate(types.length, (i) => types[i].assignableTo(o.params[i])).every((x) => x))
          o,
    ];
    if (matching.isEmpty) {
      _fail("found no matching overload for '$name' applied to '(${types.join(', ')})'", e);
    }
    _calls[e] = (name: name, overloads: matching);
    final results = matching.map((o) => o.result).toSet();
    return results.length == 1 ? results.single : CelType.dyn;
  }
}
