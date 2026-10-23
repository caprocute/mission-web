/// Động cơ CEL của Mission trên Dart: biên dịch, kiểm kiểu tĩnh, lưu đệm và thực thi biểu thức với
/// cùng hợp đồng như `CelEngine` của vt-cel (máy chủ là nguồn sự thật) và `evaluateMissionCel` của
/// @vt/form-engine: cùng kiểu biến, cùng hàm mở rộng, cùng giới hạn an toàn và mã lỗi chuẩn.
library;

import 'ast.dart';
import 'cel_type.dart';
import 'checker.dart';
import 'functions.dart';
import 'interpreter.dart';
import 'mission_functions.dart';
import 'parser.dart';

/// Mã lỗi chuẩn theo NEN-041, TL-01 mục 4.3.
enum MissionCelErrorCode {
  type('POLICY-422-CEL-TYPE'),
  missingVar('POLICY-422-CEL-MISSING-VAR'),
  sandboxLimit('POLICY-422-CEL-SANDBOX-LIMIT'),
  timeout('POLICY-422-CEL-TIMEOUT'),
  evalError('POLICY-422-CEL-EVAL-ERROR');

  const MissionCelErrorCode(this.code);
  final String code;
}

/// Lỗi biên dịch hoặc thực thi, mang mã lỗi đối chiếu được với máy chủ. Thông điệp là kỹ thuật,
/// chỉ ghi nhật ký, không hiện cho người dùng.
class MissionCelError implements Exception {
  MissionCelError(this.errorCode, this.expression, this.message);
  final MissionCelErrorCode errorCode;
  final String expression;
  final String message;
  @override
  String toString() => 'MissionCelError(${errorCode.code}): $message';
}

/// Giới hạn an toàn, trùng `CelSandboxOptions.defaults()` của vt-cel.
class CelSandboxOptions {
  const CelSandboxOptions({
    this.maxExpressionLength = 2048,
    this.maxAstDepth = 24,
    this.maxAstNodeCount = 512,
    this.maxComprehensionIterations = 1000,
    this.evaluationTimeout = const Duration(milliseconds: 50),
    this.programCacheMaxSize = 1024,
  });
  final int maxExpressionLength;
  final int maxAstDepth;
  final int maxAstNodeCount;
  final int maxComprehensionIterations;
  final Duration evaluationTimeout;
  final int programCacheMaxSize;
}

/// Biểu thức đã biên dịch và kiểm kiểu, dùng lại được nhiều lần.
class MissionCelProgram {
  MissionCelProgram._(this.expression, this.variableTypes, this.returnType, this._checked);
  final String expression;
  final Map<String, CelVariableType> variableTypes;
  final CelVariableType returnType;
  final CelChecked _checked;

  /// Biến khai báo mà biểu thức tham chiếu; thiếu hay null khi chạy là `MISSING-VAR`.
  Set<String> get referencedVariables => _checked.referenced;
}

class MissionCelEngine {
  MissionCelEngine({this.calendar = WorkingCalendar.defaultTest, this.sandbox = const CelSandboxOptions()})
    : _functions = celFunctionTable(calendar);

  final WorkingCalendar calendar;
  final CelSandboxOptions sandbox;
  final Map<String, List<CelOverload>> _functions;
  final _cache = <String, MissionCelProgram>{};

  int get cachedProgramCount => _cache.length;

  /// Biên dịch và kiểm kiểu; lỗi cú pháp, biến chưa khai báo, sai kiểu, sai kiểu trả về hay vượt
  /// giới hạn cây đều là `POLICY-422-CEL-TYPE`.
  MissionCelProgram compile(String expression, Map<String, CelVariableType> variableTypes, CelVariableType returnType) {
    MissionCelError fail(String msg) => MissionCelError(MissionCelErrorCode.type, expression, msg);
    if (expression.trim().isEmpty) throw fail('Expression must not be blank');
    final codePoints = expression.runes.length;
    if (codePoints > sandbox.maxExpressionLength) {
      throw fail('Expression code point length $codePoints exceeds maximum allowed ${sandbox.maxExpressionLength}');
    }
    final key =
        '$expression\u0000${returnType.name}\u0000'
        '${(variableTypes.entries.toList()..sort((a, b) => a.key.compareTo(b.key))).map((e) => '${e.key}:${e.value.name}').join(',')}';
    final cached = _cache.remove(key);
    if (cached != null) {
      _cache[key] = cached;
      return cached;
    }
    final CelExpr ast;
    try {
      ast = parseCel(expression);
    } on CelSyntaxError catch (e) {
      throw fail(e.toString());
    }
    final depth = celDepth(ast);
    if (depth > sandbox.maxAstDepth) {
      throw fail('AST depth $depth exceeds maximum allowed ${sandbox.maxAstDepth}');
    }
    final nodes = celNodes(ast).length;
    if (nodes > sandbox.maxAstNodeCount) {
      throw fail('AST node count $nodes exceeds maximum allowed ${sandbox.maxAstNodeCount}');
    }
    final CelChecked checked;
    try {
      checked = CelChecker({for (final e in variableTypes.entries) e.key: e.value.celType}, _functions).check(ast);
    } on CelCheckError catch (e) {
      throw fail(e.toString());
    }
    _validateReturnType(expression, checked.resultType, returnType);
    final program = MissionCelProgram._(expression, Map.unmodifiable(variableTypes), returnType, checked);
    _cache[key] = program;
    if (_cache.length > sandbox.programCacheMaxSize) _cache.remove(_cache.keys.first);
    return program;
  }

  static void _validateReturnType(String expression, CelType actual, CelVariableType expected) {
    if (expected == CelVariableType.DYN || actual.isDyn) return;
    final ok = switch (expected) {
      CelVariableType.DOUBLE || CelVariableType.DECIMAL => actual.isNumeric,
      CelVariableType.INT => actual.kind == CelKind.int,
      CelVariableType.BOOL => actual.kind == CelKind.bool,
      CelVariableType.STRING || CelVariableType.NULLABLE_STRING => actual.kind == CelKind.string,
      CelVariableType.LIST_DOUBLE ||
      CelVariableType.LIST_INT ||
      CelVariableType.LIST_STRING ||
      CelVariableType.LIST_DYN => actual.kind == CelKind.list,
      _ => actual.kind == CelKind.map,
    };
    if (!ok) {
      throw MissionCelError(
        MissionCelErrorCode.type,
        expression,
        'Expected return type ${expected.name} but expression evaluates to $actual',
      );
    }
  }

  /// Thực thi chương trình với giá trị biến; số nguyên trả `int`, số thực trả `double`.
  Object? evaluate(MissionCelProgram program, Map<String, Object?> inputs) {
    final expr = program.expression;
    for (final name in program.referencedVariables) {
      if (inputs[name] == null) {
        if (program.variableTypes[name] == CelVariableType.NULLABLE_STRING && inputs.containsKey(name)) {
          continue;
        }
        throw MissionCelError(MissionCelErrorCode.missingVar, expr, "Missing or null required variable: '$name'");
      }
    }
    final vars = <String, Object?>{};
    for (final e in inputs.entries) {
      if (e.value == null) {
        if (program.variableTypes[e.key] == CelVariableType.NULLABLE_STRING) vars[e.key] = null;
        continue;
      }
      vars[e.key] = _normalize(expr, e.key, e.value!, program.variableTypes[e.key] ?? CelVariableType.DYN);
    }
    final watch = Stopwatch()..start();
    Object? raw;
    try {
      raw = CelInterpreter(
        program._checked,
        vars,
        maxIterations: sandbox.maxComprehensionIterations,
        deadline: () => watch.elapsed > sandbox.evaluationTimeout,
      ).run();
    } on CelRuntimeError catch (e) {
      throw MissionCelError(
        switch (e.kind) {
          CelRuntimeErrorKind.missing => MissionCelErrorCode.missingVar,
          CelRuntimeErrorKind.sandbox => MissionCelErrorCode.sandboxLimit,
          CelRuntimeErrorKind.timeout => MissionCelErrorCode.timeout,
          CelRuntimeErrorKind.eval => MissionCelErrorCode.evalError,
        },
        expr,
        e.message,
      );
    } on StackOverflowError {
      throw MissionCelError(MissionCelErrorCode.sandboxLimit, expr, 'Evaluation recursion too deep');
    }
    if (watch.elapsed > sandbox.evaluationTimeout) {
      throw MissionCelError(
        MissionCelErrorCode.timeout,
        expr,
        'CEL evaluation took ${watch.elapsedMicroseconds / 1000} ms, exceeding timeout of '
        '${sandbox.evaluationTimeout.inMilliseconds} ms',
      );
    }
    return _normalizeResult(expr, raw, program.returnType);
  }

  /// Biên dịch (có lưu đệm) rồi thực thi; khi có `scale` và `mode` thì làm tròn kết quả số ở lớp
  /// sau biểu thức như `evaluateRoundedDouble` của vt-cel.
  Object? evaluateExpression(
    String expression, {
    required Map<String, CelVariableType> variableTypes,
    required CelVariableType returnType,
    Map<String, Object?> inputs = const {},
    int? scale,
    CelRoundingMode? mode,
  }) {
    final result = evaluate(compile(expression, variableTypes, returnType), inputs);
    if (result is double && scale != null && mode != null) return roundCelScore(result, scale, mode);
    return result;
  }

  static double _finite(String expr, double v) {
    if (!v.isFinite) {
      throw MissionCelError(MissionCelErrorCode.evalError, expr, 'Non-finite floating-point value is not allowed: $v');
    }
    return v;
  }

  static Object? _normalize(String expr, String name, Object v, CelVariableType type) {
    MissionCelError bad(String what) =>
        MissionCelError(MissionCelErrorCode.evalError, expr, "Variable '$name' expects $what for ${type.name}");
    int asInt(Object x) {
      if (x is int) return x;
      if (x is double && x.isFinite && celIntInRange(x)) return x.truncate();
      throw bad('integer value');
    }

    switch (type) {
      case CelVariableType.DOUBLE || CelVariableType.DECIMAL:
        if (v is! num) throw bad('numeric value');
        return _finite(expr, v.toDouble());
      case CelVariableType.INT:
        if (v is! num) throw bad('integer value');
        return asInt(v);
      case CelVariableType.LIST_DOUBLE:
        if (v is! List) throw bad('list');
        return [
          for (final x in v)
            if (x is num) _finite(expr, x.toDouble()) else throw bad('numeric elements'),
        ];
      case CelVariableType.LIST_INT:
        if (v is! List) throw bad('list');
        return [
          for (final x in v)
            if (x is num) asInt(x) else throw bad('integer elements'),
        ];
      case CelVariableType.LIST_STRING:
        if (v is! List) throw bad('list');
        return [
          for (final x in v)
            if (x is String) x else throw bad('string elements'),
        ];
      case CelVariableType.STRING || CelVariableType.NULLABLE_STRING:
        if (v is! String) throw bad('string value');
        return v;
      case CelVariableType.BOOL:
        if (v is! bool) throw bad('boolean value');
        return v;
      default:
        return _dynamic(expr, v);
    }
  }

  /// Giá trị động: số nguyên giữ `int`, số thực giữ `double` (như Long/Double của Jackson), bảng
  /// và danh sách được chép sâu như `normalizeDynamicValue`.
  static Object? _dynamic(String expr, Object? v) => switch (v) {
    double() => _finite(expr, v),
    List() => [for (final x in v) _dynamic(expr, x)],
    Map() => {for (final e in v.entries) e.key: _dynamic(expr, e.value)},
    _ => v,
  };

  static Object? _normalizeResult(String expr, Object? raw, CelVariableType expected) {
    if (raw is num) {
      final d = _finite(expr, raw.toDouble());
      if (expected == CelVariableType.DOUBLE || expected == CelVariableType.DECIMAL) return d;
      if (expected == CelVariableType.INT) return raw.toInt();
    }
    return raw;
  }
}
