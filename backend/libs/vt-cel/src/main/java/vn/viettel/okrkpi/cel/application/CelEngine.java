package vn.viettel.okrkpi.cel.application;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.compiler.CelCompiler;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelExecutionException;
import vn.viettel.okrkpi.cel.domain.CelIssueDetail;
import vn.viettel.okrkpi.cel.domain.CelRounding;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;

/**
 * Động cơ biên dịch, kiểm kiểu tĩnh, lưu đệm {@code Program} và thực thi biểu thức CEL dùng chung
 * cho {@code mission-core} và các dịch vụ vệ tinh (TL-01 §4.3, ADR-10).
 */
public final class CelEngine {

  private final WorkingCalendar workingCalendar;
  private final CelSandboxOptions sandboxOptions;
  private final CelRuntime runtime;
  private final Map<ProgramCacheKey, CelCompiledProgram> programCache;

  public CelEngine() {
    this(StaticWorkingCalendar.weekendsOnly(), CelSandboxOptions.defaults());
  }

  public CelEngine(WorkingCalendar workingCalendar) {
    this(workingCalendar, CelSandboxOptions.defaults());
  }

  public CelEngine(WorkingCalendar workingCalendar, CelSandboxOptions sandboxOptions) {
    this.workingCalendar =
        Objects.requireNonNull(workingCalendar, "workingCalendar must not be null");
    this.sandboxOptions = Objects.requireNonNull(sandboxOptions, "sandboxOptions must not be null");
    this.runtime = CelEnvironmentFactory.createRuntime(workingCalendar, sandboxOptions);
    int maxEntries = sandboxOptions.programCacheMaxSize();
    this.programCache =
        Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(
                  Map.Entry<ProgramCacheKey, CelCompiledProgram> eldest) {
                return size() > maxEntries;
              }
            });
  }

  public CelSandboxOptions sandboxOptions() {
    return sandboxOptions;
  }

  public int cachedProgramCount() {
    return programCache.size();
  }

  public void clearCache() {
    programCache.clear();
  }

  /**
   * Biên dịch và kiểm kiểu tĩnh biểu thức CEL với kiểu trả về mong đợi, có lưu đệm {@link
   * CelCompiledProgram} theo khóa {@code (expression, variableTypes, expectedReturnType)}.
   *
   * @param expression chuỗi biểu thức CEL
   * @param variableTypes tập biến được phép và kiểu tương ứng
   * @param expectedReturnType kiểu trả về mong đợi ({@link CelValueType#DOUBLE}, {@link
   *     CelValueType#BOOL}, {@link CelValueType#STRING}, {@link CelValueType#INT}, hoặc {@link
   *     CelValueType#DYN})
   * @return chương trình đã biên dịch {@link CelCompiledProgram}
   * @throws CelCompilationException nếu biểu thức vi phạm cú pháp, kiểu, biến không khai báo hoặc
   *     vượt giới hạn AST/độ dài (mã lỗi {@code POLICY-422-CEL-TYPE})
   */
  public CelCompiledProgram compile(
      String expression, Map<String, CelValueType> variableTypes, CelValueType expectedReturnType) {
    Objects.requireNonNull(expression, "expression must not be null");
    Objects.requireNonNull(variableTypes, "variableTypes must not be null");
    Objects.requireNonNull(expectedReturnType, "expectedReturnType must not be null");

    String trimmed = expression.trim();
    if (trimmed.isEmpty()) {
      throw new CelCompilationException(
          expression, List.of(new CelIssueDetail(1, 0, "SYNTAX", "Expression must not be blank")));
    }

    int codePoints = expression.codePointCount(0, expression.length());
    if (codePoints > sandboxOptions.maxExpressionLength()) {
      throw new CelCompilationException(
          expression,
          List.of(
              new CelIssueDetail(
                  1,
                  0,
                  "EXPRESSION_TOO_LONG",
                  "Expression code point length "
                      + codePoints
                      + " exceeds maximum allowed "
                      + sandboxOptions.maxExpressionLength())));
    }

    ProgramCacheKey cacheKey =
        new ProgramCacheKey(expression, Map.copyOf(variableTypes), expectedReturnType);
    CelCompiledProgram cached = programCache.get(cacheKey);
    if (cached != null) {
      return cached;
    }

    CelCompiler compiler = CelEnvironmentFactory.createCompiler(variableTypes, sandboxOptions);
    CelValidationResult validationResult = compiler.compile(expression);
    if (validationResult.hasError()) {
      throw toCompilationException(expression, validationResult.getAllIssues(), null);
    }

    final CelAbstractSyntaxTree ast;
    try {
      ast = validationResult.getAst();
    } catch (CelValidationException ex) {
      throw toCompilationException(expression, ex.getErrors(), ex);
    }

    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);
    int astDepth = navigableAst.getRoot().height();
    if (astDepth > sandboxOptions.maxAstDepth()) {
      throw new CelCompilationException(
          expression,
          List.of(
              new CelIssueDetail(
                  1,
                  0,
                  "AST_DEPTH_EXCEEDED",
                  "AST depth "
                      + astDepth
                      + " exceeds maximum allowed "
                      + sandboxOptions.maxAstDepth())));
    }

    long nodeCount = navigableAst.getRoot().allNodes().count();
    if (nodeCount > sandboxOptions.maxAstNodeCount()) {
      throw new CelCompilationException(
          expression,
          List.of(
              new CelIssueDetail(
                  1,
                  0,
                  "AST_NODE_COUNT_EXCEEDED",
                  "AST node count "
                      + nodeCount
                      + " exceeds maximum allowed "
                      + sandboxOptions.maxAstNodeCount())));
    }

    validateReturnType(expression, ast.getResultType(), expectedReturnType);

    Set<String> referencedVars =
        extractReferencedTopLevelVars(navigableAst, variableTypes.keySet());

    final CelRuntime.Program program;
    try {
      program = runtime.createProgram(ast);
    } catch (CelEvaluationException ex) {
      throw new CelCompilationException(
          expression,
          List.of(
              new CelIssueDetail(
                  1, 0, "RUNTIME_BINDING", "Failed to bind CEL program: " + ex.getMessage())),
          ex);
    }

    CelCompiledProgram compiled =
        new CelCompiledProgram(
            expression, variableTypes, referencedVars, expectedReturnType, astDepth, ast, program);
    programCache.put(cacheKey, compiled);
    return compiled;
  }

  /**
   * Thực thi chương trình CEL đã biên dịch với tập giá trị biến đầu vào.
   *
   * @param compiled chương trình CEL đã biên dịch
   * @param variables giá trị biến đầu vào lúc chạy
   * @return kết quả thực thi đã chuẩn hóa kiểu
   * @throws CelExecutionException nếu thiếu biến, vượt ngưỡng vòng lặp, quá thời gian chạy 50 ms
   *     hoặc gặp lỗi lúc chạy
   */
  public Object evaluate(CelCompiledProgram compiled, Map<String, Object> variables) {
    Objects.requireNonNull(compiled, "compiled must not be null");
    if (variables == null) {
      throw new CelExecutionException(
          CelExecutionException.CODE_MISSING_VARIABLE,
          compiled.expression(),
          "Variables map must not be null");
    }

    for (String requiredVar : compiled.referencedVariables()) {
      if (!variables.containsKey(requiredVar) || variables.get(requiredVar) == null) {
        throw new CelExecutionException(
            CelExecutionException.CODE_MISSING_VARIABLE,
            compiled.expression(),
            "Missing or null required variable: '" + requiredVar + "'");
      }
    }

    Map<String, Object> normalizedVars =
        normalizeInputVariables(compiled.expression(), compiled.variableTypes(), variables);

    long startNanos = System.nanoTime();
    final Object rawResult;
    try {
      rawResult = compiled.program().eval(normalizedVars);
    } catch (CelEvaluationException ex) {
      throw classifyEvaluationException(compiled.expression(), ex);
    } catch (RuntimeException ex) {
      throw new CelExecutionException(
          CelExecutionException.CODE_EVAL_ERROR,
          compiled.expression(),
          "CEL runtime failure: " + ex.getMessage(),
          ex);
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    if (elapsedNanos > sandboxOptions.evaluationTimeout().toNanos()) {
      throw new CelExecutionException(
          CelExecutionException.CODE_TIMEOUT,
          compiled.expression(),
          "CEL evaluation took "
              + (elapsedNanos / 1_000_000.0)
              + " ms, exceeding timeout of "
              + sandboxOptions.evaluationTimeout().toMillis()
              + " ms");
    }

    return normalizeResult(compiled.expression(), rawResult, compiled.expectedReturnType());
  }

  /** Biên dịch (hoặc lấy từ bộ nhớ đệm) và thực thi biểu thức CEL trong một bước. */
  public Object evaluate(
      String expression,
      Map<String, CelValueType> variableTypes,
      CelValueType expectedReturnType,
      Map<String, Object> variables) {
    CelCompiledProgram compiled = compile(expression, variableTypes, expectedReturnType);
    return evaluate(compiled, variables);
  }

  /** Tính biểu thức kiểu số thực ({@code double}) và trả về kết quả nguyên bản chưa làm tròn. */
  public double evaluateDouble(
      String expression, Map<String, CelValueType> variableTypes, Map<String, Object> variables) {
    Object result = evaluate(expression, variableTypes, CelValueType.DOUBLE, variables);
    return ((Number) result).doubleValue();
  }

  /**
   * Tính biểu thức kiểu số thực ({@code double}) và làm tròn ở lớp sau biểu thức CEL bằng {@link
   * BigDecimal} theo cấu hình {@code scale} và {@code roundingMode} ({@code HALF_UP}/{@code
   * NUA_LEN} hoặc {@code DOWN}/{@code CAT_BO}).
   */
  public double evaluateRoundedDouble(
      String expression,
      Map<String, CelValueType> variableTypes,
      Map<String, Object> variables,
      int scale,
      RoundingMode roundingMode) {
    double raw = evaluateDouble(expression, variableTypes, variables);
    return CelRounding.round(raw, scale, roundingMode);
  }

  /** Tính biểu thức kiểu logic ({@code bool}) phục vụ SDUI {@code visibleWhen} và ràng buộc. */
  public boolean evaluateBoolean(
      String expression, Map<String, CelValueType> variableTypes, Map<String, Object> variables) {
    Object result = evaluate(expression, variableTypes, CelValueType.BOOL, variables);
    return (Boolean) result;
  }

  /** Tính biểu thức kiểu chuỗi ({@code string}) phục vụ quy tắc xếp loại {@code min_muc}. */
  public String evaluateString(
      String expression, Map<String, CelValueType> variableTypes, Map<String, Object> variables) {
    Object result = evaluate(expression, variableTypes, CelValueType.STRING, variables);
    return (String) result;
  }

  private static void validateReturnType(
      String expression, CelType actualType, CelValueType expectedReturnType) {
    if (expectedReturnType == CelValueType.DYN || actualType.kind() == CelKind.DYN) {
      return;
    }
    boolean compatible =
        switch (expectedReturnType) {
          case DOUBLE, DECIMAL ->
              actualType.kind() == CelKind.DOUBLE || actualType.kind() == CelKind.INT;
          case INT -> actualType.kind() == CelKind.INT;
          case BOOL -> actualType.kind() == CelKind.BOOL;
          case STRING, NULLABLE_STRING -> actualType.kind() == CelKind.STRING;
          case LIST_DOUBLE, LIST_INT, LIST_STRING, LIST_DYN -> actualType.kind() == CelKind.LIST;
          default -> actualType.kind() == CelKind.MAP;
        };
    if (!compatible) {
      throw new CelCompilationException(
          expression,
          List.of(
              new CelIssueDetail(
                  1,
                  0,
                  "RETURN_TYPE_MISMATCH",
                  "Expected return type "
                      + expectedReturnType
                      + " but expression evaluates to "
                      + actualType.name())));
    }
  }

  private static Set<String> extractReferencedTopLevelVars(
      CelNavigableAst navigableAst, Set<String> declaredVars) {
    Set<String> referenced = new LinkedHashSet<>();
    navigableAst
        .getRoot()
        .allNodes()
        .map(CelNavigableExpr::expr)
        .forEach(
            expr -> {
              String name = expr.identOrDefault().name();
              if (!name.isEmpty() && declaredVars.contains(name)) {
                referenced.add(name);
              }
            });
    return referenced;
  }

  private static Map<String, Object> normalizeInputVariables(
      String expression,
      Map<String, CelValueType> variableTypes,
      Map<String, Object> rawVariables) {
    Map<String, Object> normalized = new HashMap<>(rawVariables.size());
    for (Map.Entry<String, Object> entry : rawVariables.entrySet()) {
      String key = entry.getKey();
      Object val = entry.getValue();
      if (val != null) {
        CelValueType declaredType = variableTypes.getOrDefault(key, CelValueType.DYN);
        normalized.put(key, normalizeValue(expression, key, val, declaredType));
      }
    }
    return normalized;
  }

  private static Object normalizeValue(
      String expression, String varName, Object value, CelValueType declaredType) {
    return switch (declaredType) {
      case DOUBLE, DECIMAL -> {
        if (!(value instanceof Number num)) {
          throw new CelExecutionException(
              CelExecutionException.CODE_EVAL_ERROR,
              expression,
              "Variable '" + varName + "' expects numeric value for DOUBLE, got: " + value);
        }
        yield ensureFiniteDouble(expression, num.doubleValue());
      }
      case INT -> {
        if (!(value instanceof Number num)) {
          throw new CelExecutionException(
              CelExecutionException.CODE_EVAL_ERROR,
              expression,
              "Variable '" + varName + "' expects integer value for INT, got: " + value);
        }
        yield num.longValue();
      }
      case LIST_DOUBLE -> {
        if (!(value instanceof Collection<?> col)) {
          throw new CelExecutionException(
              CelExecutionException.CODE_EVAL_ERROR,
              expression,
              "Variable '" + varName + "' expects list for LIST_DOUBLE");
        }
        List<Double> list = new ArrayList<>(col.size());
        for (Object item : col) {
          if (!(item instanceof Number num)) {
            throw new CelExecutionException(
                CelExecutionException.CODE_EVAL_ERROR,
                expression,
                "List '" + varName + "' expects numeric elements, got: " + item);
          }
          list.add(ensureFiniteDouble(expression, num.doubleValue()));
        }
        yield list;
      }
      case LIST_INT -> {
        if (!(value instanceof Collection<?> col)) {
          throw new CelExecutionException(
              CelExecutionException.CODE_EVAL_ERROR,
              expression,
              "Variable '" + varName + "' expects list for LIST_INT");
        }
        List<Long> list = new ArrayList<>(col.size());
        for (Object item : col) {
          if (!(item instanceof Number num)) {
            throw new CelExecutionException(
                CelExecutionException.CODE_EVAL_ERROR,
                expression,
                "List '" + varName + "' expects integer elements, got: " + item);
          }
          list.add(num.longValue());
        }
        yield list;
      }
      default -> normalizeDynamicValue(expression, value);
    };
  }

  private static Object normalizeDynamicValue(String expression, Object value) {
    if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
      return ((Number) value).longValue();
    }
    if (value instanceof Number num && !(value instanceof Long)) {
      return ensureFiniteDouble(expression, num.doubleValue());
    }
    if (value instanceof Collection<?> col) {
      List<Object> list = new ArrayList<>(col.size());
      for (Object item : col) {
        list.add(item == null ? null : normalizeDynamicValue(expression, item));
      }
      return list;
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> normalizedMap = new LinkedHashMap<>(map.size());
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Object v = entry.getValue();
        normalizedMap.put(entry.getKey(), v == null ? null : normalizeDynamicValue(expression, v));
      }
      return normalizedMap;
    }
    return value;
  }

  private static Object normalizeResult(
      String expression, Object rawResult, CelValueType expectedReturnType) {
    if (rawResult instanceof Number num) {
      double d = ensureFiniteDouble(expression, num.doubleValue());
      if (expectedReturnType == CelValueType.DOUBLE || expectedReturnType == CelValueType.DECIMAL) {
        return d;
      }
      if (expectedReturnType == CelValueType.INT) {
        return num.longValue();
      }
    }
    return rawResult;
  }

  private static double ensureFiniteDouble(String expression, double value) {
    if (!Double.isFinite(value)) {
      throw new CelExecutionException(
          CelExecutionException.CODE_EVAL_ERROR,
          expression,
          "Non-finite floating-point value is not allowed: " + value);
    }
    return value;
  }

  private static CelCompilationException toCompilationException(
      String expression, List<CelIssue> issues, Throwable cause) {
    List<CelIssueDetail> details = new ArrayList<>(issues.size());
    for (CelIssue issue : issues) {
      int line = issue.getSourceLocation().getLine();
      int col = issue.getSourceLocation().getColumn();
      String msg = Objects.requireNonNullElse(issue.getMessage(), "CEL validation error");
      details.add(new CelIssueDetail(line, col, classifyIssueKind(msg), msg));
    }
    return new CelCompilationException(expression, details, cause);
  }

  private static String classifyIssueKind(String message) {
    String lower = message.toLowerCase(Locale.ROOT);
    if (lower.contains("max code point") || lower.contains("code point size")) {
      return "EXPRESSION_TOO_LONG";
    }
    if (lower.contains("max recursion") || lower.contains("recursion depth")) {
      return "AST_DEPTH_EXCEEDED";
    }
    if (lower.contains("max expression node")) {
      return "AST_NODE_COUNT_EXCEEDED";
    }
    if (lower.contains("undeclared reference")) {
      return "UNDECLARED_IDENT";
    }
    if (lower.contains("found no matching overload")) {
      return "TYPE_MISMATCH";
    }
    if (lower.contains("syntax error")
        || lower.contains("mismatched input")
        || lower.contains("extraneous input")) {
      return "SYNTAX";
    }
    return "VALIDATION_ERROR";
  }

  private static CelExecutionException classifyEvaluationException(
      String expression, CelEvaluationException ex) {
    String msg = Objects.requireNonNullElse(ex.getMessage(), "CEL evaluation error");
    String lower = msg.toLowerCase(Locale.ROOT);
    if (lower.contains("iteration") || lower.contains("comprehension")) {
      return new CelExecutionException(
          CelExecutionException.CODE_SANDBOX_LIMIT, expression, msg, ex);
    }
    if (lower.contains("no such key")
        || lower.contains("not present in map")
        || lower.contains("unresolved attribute")) {
      return new CelExecutionException(
          CelExecutionException.CODE_MISSING_VARIABLE, expression, msg, ex);
    }
    return new CelExecutionException(CelExecutionException.CODE_EVAL_ERROR, expression, msg, ex);
  }

  private record ProgramCacheKey(
      String expression,
      Map<String, CelValueType> variableTypes,
      CelValueType expectedReturnType) {}
}
