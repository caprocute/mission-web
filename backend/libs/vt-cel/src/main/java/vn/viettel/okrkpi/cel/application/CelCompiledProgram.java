package vn.viettel.okrkpi.cel.application;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.runtime.CelRuntime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.viettel.okrkpi.cel.domain.CelValueType;

/**
 * Biểu diễn một chương trình CEL đã biên dịch, kiểm kiểu tĩnh và đóng gói sẵn {@link
 * CelRuntime.Program} để tái sử dụng nhiều lần mà không cần parse lại (TL-01 §4.3).
 */
public final class CelCompiledProgram {

  private final String expression;
  private final Map<String, CelValueType> variableTypes;
  private final Set<String> referencedVariables;
  private final CelValueType expectedReturnType;
  private final int astDepth;
  private final CelAbstractSyntaxTree ast;
  private final CelRuntime.Program program;

  CelCompiledProgram(
      String expression,
      Map<String, CelValueType> variableTypes,
      Set<String> referencedVariables,
      CelValueType expectedReturnType,
      int astDepth,
      CelAbstractSyntaxTree ast,
      CelRuntime.Program program) {
    this.expression = Objects.requireNonNull(expression, "expression must not be null");
    this.variableTypes = Map.copyOf(Objects.requireNonNull(variableTypes));
    this.referencedVariables = Set.copyOf(Objects.requireNonNull(referencedVariables));
    this.expectedReturnType =
        Objects.requireNonNull(expectedReturnType, "expectedReturnType must not be null");
    this.astDepth = astDepth;
    this.ast = Objects.requireNonNull(ast, "ast must not be null");
    this.program = Objects.requireNonNull(program, "program must not be null");
  }

  public String expression() {
    return expression;
  }

  public Map<String, CelValueType> variableTypes() {
    return variableTypes;
  }

  public Set<String> referencedVariables() {
    return referencedVariables;
  }

  public CelValueType expectedReturnType() {
    return expectedReturnType;
  }

  public int astDepth() {
    return astDepth;
  }

  CelAbstractSyntaxTree ast() {
    return ast;
  }

  CelRuntime.Program program() {
    return program;
  }
}
