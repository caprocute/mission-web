package vn.viettel.okrkpi.policy.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Một lần chạy bộ ca vàng trên một phiên bản ({@code policy.test_run}, TL-03, CORE-05-07). {@code
 * engine} là engine chính ({@code CEL_JAVA}); kết quả đối chiếu cel-js nằm ở {@code engineMatch} và
 * {@code casesJson} (suy luận: bảng chỉ có một cột engine).
 *
 * @param propertyResultJson kết quả kiểm thử thuộc tính dạng JSON
 * @param casesJson tóm tắt lần chạy dạng JSON: engine đã chạy, ca lệch engine, so sánh tác động
 */
public record PolicyTestRun(
    UUID id,
    UUID versionId,
    String engine,
    Instant startedAt,
    Instant finishedAt,
    int total,
    int passed,
    int failed,
    TestRunResult result,
    boolean engineMatch,
    String propertyResultJson,
    String casesJson,
    UUID createdBy,
    long rowVersion) {

  public static final String CEL_JAVA = "CEL_JAVA";
  public static final String CEL_JS = "CEL_JS";

  public PolicyTestRun {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(engine, "engine");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(createdBy, "createdBy");
    if (total < 0 || passed < 0 || failed < 0 || passed + failed != total) {
      throw new IllegalArgumentException("Số ca đạt và không đạt phải cộng bằng tổng số ca");
    }
  }

  /** Lần chạy mới ở {@code DANG_CHAY}, chưa có số liệu. */
  public static PolicyTestRun started(UUID id, UUID versionId, UUID actor, Instant at) {
    return new PolicyTestRun(
        id,
        versionId,
        CEL_JAVA,
        at,
        null,
        0,
        0,
        0,
        TestRunResult.DANG_CHAY,
        false,
        null,
        null,
        actor,
        0);
  }

  public boolean running() {
    return result == TestRunResult.DANG_CHAY;
  }

  /** Lần chạy dở quá {@code stale} kể từ lúc bắt đầu (pod dừng giữa chừng). */
  public boolean staleAt(Instant now, Duration stale) {
    return running() && startedAt.plus(stale).isBefore(now);
  }

  /**
   * Kết thúc lần chạy: đạt khi có ít nhất một ca, mọi ca đạt, hai engine khớp và không vi phạm bất
   * biến (BR-15-07).
   */
  public PolicyTestRun finish(
      int totalCases,
      int passedCases,
      boolean enginesMatch,
      int violations,
      String propertyJson,
      String summaryJson,
      Instant at) {
    if (!running()) {
      throw new IllegalStateException("Lần chạy " + id + " đã kết thúc");
    }
    boolean ok = totalCases > 0 && passedCases == totalCases && enginesMatch && violations == 0;
    return new PolicyTestRun(
        id,
        versionId,
        engine,
        startedAt,
        at,
        totalCases,
        passedCases,
        totalCases - passedCases,
        ok ? TestRunResult.DAT : TestRunResult.KHONG_DAT,
        enginesMatch,
        propertyJson,
        summaryJson,
        createdBy,
        rowVersion + 1);
  }

  /** Lần chạy hỏng (lỗi hệ thống hoặc pod dừng): không đạt, giữ số liệu đã có. */
  public PolicyTestRun abort(String summaryJson, Instant at) {
    return finish(total, passed, false, 0, propertyResultJson, summaryJson, at);
  }

  /** Tỷ lệ ca đạt, {@code 0} khi không có ca. */
  public java.math.BigDecimal passRate() {
    if (total == 0) {
      return java.math.BigDecimal.ZERO;
    }
    return java.math.BigDecimal.valueOf(passed)
        .divide(java.math.BigDecimal.valueOf(total), 4, java.math.RoundingMode.DOWN);
  }

  public Long durationMs() {
    return finishedAt == null ? null : Duration.between(startedAt, finishedAt).toMillis();
  }
}
