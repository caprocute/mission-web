package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.domain.PolicyRule;

/**
 * Kiểm thử thuộc tính của CORE-05-07: 10.000 bộ dữ liệu trên gói khối Đảng chạy dưới 2 phút, không
 * vi phạm bất biến và đạt ca biên; quy tắc cho điểm ngoài [0; 100] bị phát hiện; hạt giống cố định
 * cho cùng bộ dữ liệu.
 */
class PolicyPropertyCheckTest {

  private static final String PACK = "khoi-dang/quy-dinh-moi";

  @Test
  void tenThousandDatasetsHoldAllInvariantsUnderTwoMinutes() throws Exception {
    PolicyEvaluator ev = PolicyEvaluatorTest.evaluator(PACK, List.of());
    long start = System.nanoTime();
    PolicyPropertyCheck.Report r = new PolicyPropertyCheck(ev, 42L).run(10_000);
    Duration took = Duration.ofNanos(System.nanoTime() - start);
    assertThat(took).isLessThan(Duration.ofMinutes(2));
    assertThat(r.samples()).isEmpty();
    assertThat(r.violations()).isZero();
    assertThat(r.datasets()).isEqualTo(10_000);
    assertThat(r.invariants())
        .containsExactly(
            PolicyPropertyCheck.INV_SCORE,
            PolicyPropertyCheck.INV_TIMELINESS,
            PolicyPropertyCheck.INV_MONOTONE,
            PolicyPropertyCheck.INV_OBJECTIVES);
    assertThat(r.edges())
        .containsEntry(PolicyPropertyCheck.EDGE_ROUNDING, true)
        .containsEntry(PolicyPropertyCheck.EDGE_ZERO_PLAN, true);
    assertThat(r.json()).containsEntry("violations", 0).containsEntry("seed", 42L);
  }

  @Test
  void scoreOutOfRangeAndBrokenRoundingAreViolations() throws Exception {
    UUID v = UUID.randomUUID();
    PolicyRule tooHigh =
        new PolicyRule(
            UUID.randomUUID(),
            v,
            "diem",
            "diem_ket_qua + 150.0",
            "DECIMAL",
            "{}",
            "",
            100,
            "{}",
            true);
    PolicyPropertyCheck.Report r =
        new PolicyPropertyCheck(PolicyEvaluatorTest.evaluator(PACK, List.of(tooHigh)), 7L).run(50);
    assertThat(r.violations()).isGreaterThanOrEqualTo(50);
    assertThat(r.samples()).hasSize(5).first().asString().contains("ngoài [0; 100]");
    assertThat(r.edges()).containsEntry(PolicyPropertyCheck.EDGE_ZERO_PLAN, false);
  }

  @Test
  void sameSeedSameDatasets() throws Exception {
    PolicyEvaluator ev = PolicyEvaluatorTest.evaluator(PACK, List.of());
    assertThat(new PolicyPropertyCheck(ev, 9L).dataset())
        .isEqualTo(new PolicyPropertyCheck(ev, 9L).dataset());
    assertThat(new PolicyPropertyCheck(ev, 9L).run(0).invariants()).isEmpty();
  }
}
