package vn.viettel.okrkpi.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelValueType;

/**
 * Kiểm thử bất biến (Property-Based Testing) bằng {@code jqwik} cho các hàm nghiệp vụ CEL {@code
 * sum} và {@code min_muc} (NEN-041).
 */
class CelPropertyTest {

  private final CelEngine engine = new CelEngine();

  @Property(tries = 150)
  void sumShouldBeAdditiveOverConcatenatedLists(
      @ForAll("scoreLists") List<Double> listA, @ForAll("scoreLists") List<Double> listB) {
    List<Double> combined = new ArrayList<>(listA.size() + listB.size());
    combined.addAll(listA);
    combined.addAll(listB);

    Map<String, CelValueType> schema =
        Map.of(
            "a", CelValueType.LIST_DOUBLE,
            "b", CelValueType.LIST_DOUBLE,
            "ab", CelValueType.LIST_DOUBLE);

    double sumSeparate =
        engine.evaluateDouble(
            "sum(a) + sum(b)", schema, Map.of("a", listA, "b", listB, "ab", combined));
    double sumCombined =
        engine.evaluateDouble("sum(ab)", schema, Map.of("a", listA, "b", listB, "ab", combined));

    assertThat(sumSeparate).isCloseTo(sumCombined, within(1e-9));
  }

  @Property(tries = 100)
  void minMucShouldBeCommutativeIdempotentAndHaveHtxsnvAsIdentity(
      @ForAll("shortRatingCodes") String a, @ForAll("shortRatingCodes") String b) {
    Map<String, CelValueType> schema = Map.of("a", CelValueType.STRING, "b", CelValueType.STRING);

    String ab = engine.evaluateString("min_muc(a, b)", schema, Map.of("a", a, "b", b));
    String ba = engine.evaluateString("min_muc(b, a)", schema, Map.of("a", a, "b", b));
    String aa = engine.evaluateString("min_muc(a, a)", schema, Map.of("a", a, "b", b));
    String withTop = engine.evaluateString("min_muc(a, 'HTXSNV')", schema, Map.of("a", a, "b", b));
    String withBottom =
        engine.evaluateString("min_muc(a, 'KHTNV')", schema, Map.of("a", a, "b", b));

    assertThat(ab).isEqualTo(ba);
    assertThat(aa).isEqualTo(a);
    assertThat(withTop).isEqualTo(a);
    assertThat(withBottom).isEqualTo("KHTNV");
  }

  @Provide
  Arbitrary<List<Double>> scoreLists() {
    return Arbitraries.integers().between(0, 10000).map(i -> i / 100.0).list().ofMaxSize(25);
  }

  @Provide
  Arbitrary<String> shortRatingCodes() {
    return Arbitraries.of("KHTNV", "HTNV", "HTTNV", "HTXSNV");
  }
}
