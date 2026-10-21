package vn.viettel.okrkpi.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.application.CelCompiledProgram;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelExecutionException;
import vn.viettel.okrkpi.cel.domain.CelRounding;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.MissionCelFunctions;
import vn.viettel.okrkpi.cel.domain.RatingLevel;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;

/**
 * Kiểm thử đơn vị cho {@link CelEngine}, giới hạn an toàn (sandbox), làm tròn điểm, bộ nhớ đệm
 * {@code Program} và hiệu năng thực thi (&lt; 100 µs/eval).
 */
class CelEngineTest {

  @Test
  void shouldCompileCacheAndEvaluateTypedExpressions() {
    CelEngine engine = new CelEngine();
    Map<String, CelValueType> schema =
        Map.of(
            "diem_kpi", CelValueType.DOUBLE,
            "diem_chung", CelValueType.DECIMAL,
            "muc_de_xuat", CelValueType.STRING,
            "hop_le", CelValueType.BOOL);

    assertThat(engine.cachedProgramCount()).isZero();

    double score =
        engine.evaluateRoundedDouble(
            "diem_kpi * 0.7 + diem_chung * 0.3",
            schema,
            Map.of("diem_kpi", 90, "diem_chung", new BigDecimal("80.00")),
            2,
            RoundingMode.HALF_UP);
    assertThat(score).isEqualTo(87.0);
    assertThat(engine.cachedProgramCount()).isEqualTo(1);

    boolean valid =
        engine.evaluateBoolean(
            "hop_le && muc_de_xuat == 'HTXSNV'",
            schema,
            Map.of("hop_le", true, "muc_de_xuat", "HTXSNV"));
    assertThat(valid).isTrue();
    assertThat(engine.cachedProgramCount()).isEqualTo(2);

    String level =
        engine.evaluateString(
            "min_muc(muc_de_xuat, 'HTTNV')", schema, Map.of("muc_de_xuat", "HTXSNV"));
    assertThat(level).isEqualTo("HTTNV");

    engine.clearCache();
    assertThat(engine.cachedProgramCount()).isZero();
  }

  @Test
  void shouldEvictOldestProgramWhenCacheExceedsMaxSize() {
    CelSandboxOptions options =
        new CelSandboxOptions(2048, 24, 512, 1000, Duration.ofMillis(50), 2);
    CelEngine engine = new CelEngine(StaticWorkingCalendar.weekendsOnly(), options);
    assertThat(engine.sandboxOptions()).isEqualTo(options);

    engine.compile("1.0 + 1.0", Map.of(), CelValueType.DOUBLE);
    engine.compile("2.0 + 2.0", Map.of(), CelValueType.DOUBLE);
    engine.compile("3.0 + 3.0", Map.of(), CelValueType.DOUBLE);

    assertThat(engine.cachedProgramCount()).isEqualTo(2);
  }

  @Test
  void shouldRejectInvalidExpressionsAtCompileTimeWithPolicy422CelType() {
    CelEngine engine = new CelEngine();

    assertThatThrownBy(() -> engine.compile("   ", Map.of(), CelValueType.DOUBLE))
        .isInstanceOf(CelCompilationException.class)
        .satisfies(
            ex -> {
              CelCompilationException celEx = (CelCompilationException) ex;
              assertThat(celEx.errorCode()).isEqualTo("POLICY-422-CEL-TYPE");
              assertThat(celEx.issues().get(0).errorKind()).isEqualTo("SYNTAX");
            });

    assertThatThrownBy(() -> engine.compile("1 + 'a'", Map.of(), CelValueType.DOUBLE))
        .isInstanceOf(CelCompilationException.class)
        .satisfies(
            ex -> {
              CelCompilationException celEx = (CelCompilationException) ex;
              assertThat(celEx.errorCode()).isEqualTo("POLICY-422-CEL-TYPE");
              assertThat(celEx.expression()).isEqualTo("1 + 'a'");
              assertThat(celEx.issues().get(0).errorKind()).isEqualTo("TYPE_MISMATCH");
              assertThat(celEx.issues().get(0).line()).isGreaterThanOrEqualTo(1);
            });

    assertThatThrownBy(() -> engine.compile("sum(['a', 'b'])", Map.of(), CelValueType.DOUBLE))
        .isInstanceOf(CelCompilationException.class)
        .satisfies(
            ex ->
                assertThat(((CelCompilationException) ex).errorCode())
                    .isEqualTo("POLICY-422-CEL-TYPE"));

    assertThatThrownBy(() -> engine.compile("khong_khai_bao + 1.0", Map.of(), CelValueType.DOUBLE))
        .isInstanceOf(CelCompilationException.class)
        .satisfies(
            ex ->
                assertThat(((CelCompilationException) ex).issues().get(0).errorKind())
                    .isEqualTo("UNDECLARED_IDENT"));

    assertThatThrownBy(() -> engine.compile("1.0 + 2.0", Map.of(), CelValueType.BOOL))
        .isInstanceOf(CelCompilationException.class)
        .satisfies(
            ex ->
                assertThat(((CelCompilationException) ex).issues().get(0).errorKind())
                    .isEqualTo("RETURN_TYPE_MISMATCH"));
    assertThatThrownBy(() -> engine.compile("1.0", Map.of(), CelValueType.INT))
        .isInstanceOf(CelCompilationException.class);
    assertThatThrownBy(() -> engine.compile("1.0", Map.of(), CelValueType.STRING))
        .isInstanceOf(CelCompilationException.class);
    assertThatThrownBy(() -> engine.compile("1.0", Map.of(), CelValueType.LIST_DOUBLE))
        .isInstanceOf(CelCompilationException.class);
    assertThatThrownBy(() -> engine.compile("1.0", Map.of(), CelValueType.MAP_STRING_DYN))
        .isInstanceOf(CelCompilationException.class);

    assertThatThrownBy(() -> engine.compile("1.0 + * 2.0", Map.of(), CelValueType.DOUBLE))
        .isInstanceOf(CelCompilationException.class)
        .satisfies(
            ex ->
                assertThat(((CelCompilationException) ex).issues().get(0).errorKind())
                    .isEqualTo("SYNTAX"));

    // Runtime input validation and execution error paths
    CelCompiledProgram compiledDouble =
        engine.compile("x + 1.0", Map.of("x", CelValueType.DOUBLE), CelValueType.DOUBLE);
    assertThatThrownBy(() -> engine.evaluate(compiledDouble, null))
        .isInstanceOf(CelExecutionException.class)
        .satisfies(
            ex ->
                assertThat(((CelExecutionException) ex).errorCode())
                    .isEqualTo(CelExecutionException.CODE_MISSING_VARIABLE));
    assertThatThrownBy(() -> engine.evaluate(compiledDouble, Map.of("x", "not-a-num")))
        .isInstanceOf(CelExecutionException.class)
        .satisfies(
            ex ->
                assertThat(((CelExecutionException) ex).errorCode())
                    .isEqualTo(CelExecutionException.CODE_EVAL_ERROR));
    assertThatThrownBy(() -> engine.evaluate(compiledDouble, Map.of("x", Double.NaN)))
        .isInstanceOf(CelExecutionException.class);
    assertThatThrownBy(
            () ->
                engine.evaluate(
                    "n + 1", Map.of("n", CelValueType.INT), CelValueType.INT, Map.of("n", "bad")))
        .isInstanceOf(CelExecutionException.class);
    assertThatThrownBy(
            () ->
                engine.evaluate(
                    "sum(ds)",
                    Map.of("ds", CelValueType.LIST_DOUBLE),
                    CelValueType.DOUBLE,
                    Map.of("ds", "not-list")))
        .isInstanceOf(CelExecutionException.class);
    assertThatThrownBy(
            () ->
                engine.evaluate(
                    "sum(ds)",
                    Map.of("ds", CelValueType.LIST_DOUBLE),
                    CelValueType.DOUBLE,
                    Map.of("ds", List.of("bad"))))
        .isInstanceOf(CelExecutionException.class);
    assertThatThrownBy(
            () ->
                engine.evaluate(
                    "size(ds)",
                    Map.of("ds", CelValueType.LIST_INT),
                    CelValueType.INT,
                    Map.of("ds", "not-list")))
        .isInstanceOf(CelExecutionException.class);
    assertThatThrownBy(
            () ->
                engine.evaluate(
                    "size(ds)",
                    Map.of("ds", CelValueType.LIST_INT),
                    CelValueType.INT,
                    Map.of("ds", List.of("bad"))))
        .isInstanceOf(CelExecutionException.class);
    assertThatThrownBy(
            () ->
                engine.evaluate(
                    "min_muc(a, 'HTTNV')",
                    Map.of("a", CelValueType.STRING),
                    CelValueType.STRING,
                    Map.of("a", "UNKNOWN_RATING")))
        .isInstanceOf(CelExecutionException.class)
        .satisfies(
            ex ->
                assertThat(((CelExecutionException) ex).errorCode())
                    .isEqualTo(CelExecutionException.CODE_EVAL_ERROR));
  }

  @Test
  void shouldEnforceSandboxLimitsOnLengthDepthNodesComprehensionAndTimeout() {
    CelSandboxOptions strictOptions =
        new CelSandboxOptions(40, 4, 64, 10, Duration.ofMillis(50), 64);
    CelEngine strictEngine = new CelEngine(StaticWorkingCalendar.weekendsOnly(), strictOptions);

    // 1. Quá độ dài biểu thức cho phép
    String tooLong = "1.0 + 1.0 + 1.0 + 1.0 + 1.0 + 1.0 + 1.0 + 1.0 + 1.0";
    assertThatThrownBy(() -> strictEngine.compile(tooLong, Map.of(), CelValueType.DOUBLE))
        .isInstanceOf(CelCompilationException.class)
        .satisfies(
            ex ->
                assertThat(((CelCompilationException) ex).issues().get(0).errorKind())
                    .isEqualTo("EXPRESSION_TOO_LONG"));

    // 2. Độ sâu AST vượt ngưỡng
    String tooDeep = "(((((1.0+2.0)+3.0)+4.0)+5.0)+6.0)";
    assertThatThrownBy(() -> strictEngine.compile(tooDeep, Map.of(), CelValueType.DOUBLE))
        .isInstanceOf(CelCompilationException.class)
        .satisfies(
            ex ->
                assertThat(((CelCompilationException) ex).issues().get(0).errorKind())
                    .isEqualTo("AST_DEPTH_EXCEEDED"));

    // 3. Số nút AST vượt ngưỡng (nhưng độ sâu thấp)
    CelSandboxOptions lowNodeOptions =
        new CelSandboxOptions(200, 20, 5, 10, Duration.ofMillis(50), 64);
    CelEngine lowNodeEngine = new CelEngine(StaticWorkingCalendar.weekendsOnly(), lowNodeOptions);
    assertThatThrownBy(
            () -> lowNodeEngine.compile("[1, 2, 3, 4, 5, 6, 7]", Map.of(), CelValueType.LIST_INT))
        .isInstanceOf(CelCompilationException.class);

    // 4. Vượt số vòng lặp comprehension lúc chạy
    List<Long> twentyItems = IntStream.rangeClosed(1, 20).mapToLong(i -> i).boxed().toList();
    assertThatThrownBy(
            () ->
                strictEngine.evaluateBoolean(
                    "ds.all(x, x > 0)",
                    Map.of("ds", CelValueType.LIST_INT),
                    Map.of("ds", twentyItems)))
        .isInstanceOf(CelExecutionException.class)
        .satisfies(
            ex ->
                assertThat(((CelExecutionException) ex).errorCode())
                    .isEqualTo(CelExecutionException.CODE_SANDBOX_LIMIT));

    // 5. Quá thời gian thực thi tối đa (timeout <= 50 ms)
    WorkingCalendar slowCalendar =
        new WorkingCalendar() {
          @Override
          public boolean isWorkingDay(LocalDate date) {
            return true;
          }

          @Override
          public long countWorkingDays(LocalDate fromExclusive, LocalDate toInclusive) {
            try {
              Thread.sleep(25);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return 1L;
          }
        };
    CelSandboxOptions fastTimeout =
        new CelSandboxOptions(2048, 24, 512, 1000, Duration.ofMillis(5), 64);
    CelEngine timeoutEngine = new CelEngine(slowCalendar, fastTimeout);
    assertThatThrownBy(
            () ->
                timeoutEngine.evaluate(
                    "ngay_lam_viec('2026-03-01', '2026-03-05')",
                    Map.of(),
                    CelValueType.INT,
                    Map.of()))
        .isInstanceOf(CelExecutionException.class)
        .satisfies(
            ex ->
                assertThat(((CelExecutionException) ex).errorCode())
                    .isEqualTo(CelExecutionException.CODE_TIMEOUT));
  }

  @Test
  void shouldBenchmarkCachedProgramEvaluationUnder100Microseconds() {
    CelEngine engine = new CelEngine();
    Map<String, CelValueType> schema =
        Map.of(
            "diem_chinh_tri", CelValueType.DOUBLE,
            "diem_chuyen_mon", CelValueType.DOUBLE,
            "diem_pham_chat", CelValueType.DOUBLE,
            "diem_thuong", CelValueType.DOUBLE);
    CelCompiledProgram compiled =
        engine.compile(
            "(diem_chinh_tri * 0.2 + diem_chuyen_mon * 0.6 + diem_pham_chat * 0.2) + diem_thuong",
            schema,
            CelValueType.DOUBLE);
    assertThat(compiled.astDepth()).isGreaterThan(1);

    Map<String, Object> inputs =
        Map.of(
            "diem_chinh_tri", 90.0,
            "diem_chuyen_mon", 85.0,
            "diem_pham_chat", 92.0,
            "diem_thuong", 1.5);

    // Warm up JIT
    for (int i = 0; i < 1_000; i++) {
      engine.evaluate(compiled, inputs);
    }

    int iterations = 10_000;
    long startNanos = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      Object res = engine.evaluate(compiled, inputs);
      assertThat(res).isEqualTo(88.9);
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    double avgMicros = (elapsedNanos / 1_000.0) / iterations;

    assertThat(avgMicros)
        .as(
            "Average cached CEL evaluation latency must be < 100 µs/eval (actual: %.2f µs)",
            avgMicros)
        .isLessThan(100.0);
  }

  @Test
  void shouldValidateDomainHelpersAndEdgeCases() {
    // CelRounding edge cases
    assertThat(CelRounding.round(59.995, 2, "NUA_LEN")).isEqualTo(60.0);
    assertThat(CelRounding.round(59.995, 2, "CAT_BO")).isEqualTo(59.99);
    assertThat(CelRounding.round(89.995, 2, "HALF_UP")).isEqualTo(90.0);
    assertThat(CelRounding.round(89.995, 2, "DOWN")).isEqualTo(89.99);
    assertThat(CelRounding.toBigDecimal(42L)).isEqualTo(BigDecimal.valueOf(42L));
    assertThatThrownBy(() -> CelRounding.parseRoundingMode("UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CelRounding.round(10.0, -1, RoundingMode.HALF_UP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CelRounding.round(10.0, 2, RoundingMode.CEILING))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CelRounding.toBigDecimal(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);

    // RatingLevel edge cases
    assertThat(RatingLevel.KHTNV.rank()).isEqualTo(1);
    assertThat(RatingLevel.HTXSNV.shortCode()).isEqualTo("HTXSNV");
    assertThat(RatingLevel.HTXSNV.longCode()).isEqualTo("HOAN_THANH_XUAT_SAC");
    assertThat(RatingLevel.isAtLeast("HTTNV", "HTNV")).isTrue();
    assertThat(RatingLevel.isAtLeast("KHTNV", "HTNV")).isFalse();
    assertThatThrownBy(() -> RatingLevel.parse(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RatingLevel.parse("INVALID"))
        .isInstanceOf(IllegalArgumentException.class);

    // StaticWorkingCalendar edge cases
    StaticWorkingCalendar cal =
        StaticWorkingCalendar.of(
            Set.of(LocalDate.of(2026, 9, 2)), Set.of(LocalDate.of(2026, 8, 29)));
    assertThat(cal.specialDays()).hasSize(2);
    assertThat(StaticWorkingCalendar.defaultTestCalendar().specialDays()).isNotEmpty();
    assertThat(cal.isWorkingDay(LocalDate.of(2026, 8, 29))).isTrue();
    assertThat(cal.isWorkingDay(LocalDate.of(2026, 8, 30))).isFalse();
    assertThat(cal.isWorkingDay(LocalDate.of(2026, 9, 2))).isFalse();

    // MissionCelFunctions edge cases
    assertThatThrownBy(() -> MissionCelFunctions.sum(List.of("not-a-number")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MissionCelFunctions.ngayLamViec("bad-date", "2026-03-01", cal))
        .isInstanceOf(IllegalArgumentException.class);

    // CelValueType edge cases
    assertThat(CelValueType.INT.isNumeric()).isTrue();
    assertThat(CelValueType.DOUBLE.isNumeric()).isTrue();
    assertThat(CelValueType.DECIMAL.isNumeric()).isTrue();
    assertThat(CelValueType.STRING.isNumeric()).isFalse();
    assertThat(CelValueType.fromName("NULLABLE_STRING")).isEqualTo(CelValueType.NULLABLE_STRING);
    assertThat(CelValueType.fromName("LIST_STRING")).isEqualTo(CelValueType.LIST_STRING);
    assertThat(CelValueType.fromName("DYN")).isEqualTo(CelValueType.DYN);
    assertThatThrownBy(() -> CelValueType.fromName(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CelValueType.fromName("UNSUPPORTED"))
        .isInstanceOf(IllegalArgumentException.class);

    // CelSandboxOptions validation
    assertThatThrownBy(() -> new CelSandboxOptions(0, 10, 10, 10, Duration.ofMillis(10), 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CelSandboxOptions(10, 0, 10, 10, Duration.ofMillis(10), 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CelSandboxOptions(10, 10, 0, 10, Duration.ofMillis(10), 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CelSandboxOptions(10, 10, 10, 0, Duration.ofMillis(10), 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CelSandboxOptions(10, 10, 10, 10, Duration.ZERO, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CelSandboxOptions(10, 10, 10, 10, Duration.ofMillis(10), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
