package vn.viettel.okrkpi.cel.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Cấu hình giới hạn an toàn (sandbox) cho trình biên dịch và thực thi CEL (TL-01 §4.3, SEC).
 *
 * @param maxExpressionLength độ dài tối đa của chuỗi biểu thức (số code point)
 * @param maxAstDepth độ sâu cây cú pháp (AST height) tối đa cho phép
 * @param maxAstNodeCount tổng số nút AST tối đa cho phép
 * @param maxComprehensionIterations giới hạn số vòng lặp của comprehension ({@code all}, {@code
 *     exists}, {@code filter}, {@code map})
 * @param evaluationTimeout thời gian chạy tối đa cho một lần tính biểu thức (mặc định 50 ms)
 * @param programCacheMaxSize kích thước tối đa của bộ nhớ đệm {@code Program} đã biên dịch
 */
public record CelSandboxOptions(
    int maxExpressionLength,
    int maxAstDepth,
    int maxAstNodeCount,
    int maxComprehensionIterations,
    Duration evaluationTimeout,
    int programCacheMaxSize) {

  public static final int DEFAULT_MAX_EXPRESSION_LENGTH = 2048;
  public static final int DEFAULT_MAX_AST_DEPTH = 24;
  public static final int DEFAULT_MAX_AST_NODE_COUNT = 512;
  public static final int DEFAULT_MAX_COMPREHENSION_ITERATIONS = 1000;
  public static final Duration DEFAULT_EVALUATION_TIMEOUT = Duration.ofMillis(50);
  public static final int DEFAULT_PROGRAM_CACHE_MAX_SIZE = 1024;

  public CelSandboxOptions {
    if (maxExpressionLength <= 0) {
      throw new IllegalArgumentException("maxExpressionLength must be > 0");
    }
    if (maxAstDepth <= 0) {
      throw new IllegalArgumentException("maxAstDepth must be > 0");
    }
    if (maxAstNodeCount <= 0) {
      throw new IllegalArgumentException("maxAstNodeCount must be > 0");
    }
    if (maxComprehensionIterations <= 0) {
      throw new IllegalArgumentException("maxComprehensionIterations must be > 0");
    }
    Objects.requireNonNull(evaluationTimeout, "evaluationTimeout must not be null");
    if (evaluationTimeout.isZero() || evaluationTimeout.isNegative()) {
      throw new IllegalArgumentException("evaluationTimeout must be positive");
    }
    if (programCacheMaxSize <= 0) {
      throw new IllegalArgumentException("programCacheMaxSize must be > 0");
    }
  }

  /** Trả về cấu hình sandbox mặc định theo chuẩn an toàn của Mission. */
  public static CelSandboxOptions defaults() {
    return new CelSandboxOptions(
        DEFAULT_MAX_EXPRESSION_LENGTH,
        DEFAULT_MAX_AST_DEPTH,
        DEFAULT_MAX_AST_NODE_COUNT,
        DEFAULT_MAX_COMPREHENSION_ITERATIONS,
        DEFAULT_EVALUATION_TIMEOUT,
        DEFAULT_PROGRAM_CACHE_MAX_SIZE);
  }
}
