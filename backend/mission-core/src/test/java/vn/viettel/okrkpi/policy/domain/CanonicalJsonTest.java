package vn.viettel.okrkpi.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Chuẩn hoá JSON RFC 8785 cho băm và ký ca kiểm thử vàng (CORE-05-06). */
class CanonicalJsonTest {

  @Test
  void numbersFollowEcmaScriptFormatting() {
    assertThat(CanonicalJson.number(0.0)).isEqualTo("0");
    assertThat(CanonicalJson.number(-0.0)).isEqualTo("0");
    assertThat(CanonicalJson.number(1.0)).isEqualTo("1");
    assertThat(CanonicalJson.number(10)).isEqualTo("10");
    assertThat(CanonicalJson.number(4.5)).isEqualTo("4.5");
    assertThat(CanonicalJson.number(-98.8)).isEqualTo("-98.8");
    assertThat(CanonicalJson.number(0.002)).isEqualTo("0.002");
    assertThat(CanonicalJson.number(0.000001)).isEqualTo("0.000001");
    assertThat(CanonicalJson.number(1e-7)).isEqualTo("1e-7");
    assertThat(CanonicalJson.number(1e21)).isEqualTo("1e+21");
    assertThat(CanonicalJson.number(1.5e22)).isEqualTo("1.5e+22");
    assertThat(CanonicalJson.number(1e20)).isEqualTo("100000000000000000000");
    assertThat(CanonicalJson.number(123456789012345680000.0)).isEqualTo("123456789012345680000");
    assertThat(CanonicalJson.number(333333333.3333333)).isEqualTo("333333333.3333333");
    assertThat(CanonicalJson.number(new BigDecimal("0.1"))).isEqualTo("0.1");
    assertThat(CanonicalJson.number(9007199254740992L)).isEqualTo("9007199254740992");
    assertThatThrownBy(() -> CanonicalJson.number(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.number(Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keysAreSortedByUtf16AndStringsEscapedMinimally() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("", "bmp");
    m.put("😀", "surrogate");
    m.put("b", Arrays.asList(1, true, null, "x"));
    m.put("a", Map.of("z", 1, "y", List.of()));
    m.put("ctrl", "dòng 1\ndòng 2\t\"trích\"\\\b\f\r\u001f</a>");
    assertThat(CanonicalJson.write(m))
        .isEqualTo(
            "{\"a\":{\"y\":[],\"z\":1},\"b\":[1,true,null,\"x\"],"
                + "\"ctrl\":\"dòng 1\\ndòng 2\\t\\\"trích\\\"\\\\\\b\\f\\r\\u001f</a>\","
                + "\"😀\":\"surrogate\",\"\":\"bmp\"}");
    assertThat(CanonicalJson.write(null)).isEqualTo("null");
    assertThatThrownBy(() -> CanonicalJson.write(new Object()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fieldOrderDoesNotChangeTheCanonicalForm() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("diem", 98.0);
    a.put("xep_loai", "HTXSNV");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("xep_loai", "HTXSNV");
    b.put("diem", 98);
    assertThat(CanonicalJson.write(a)).isEqualTo(CanonicalJson.write(b));
  }
}
