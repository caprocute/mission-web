package vn.viettel.okrkpi.shared.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * RFC 8785 + NFC + SHA-256 (DV-07-01) trên bộ vector dùng chung với ledger-service: {@code
 * data/golden/jcs/vectors.json} và tải trọng của {@code data/golden/ledger-canon}.
 */
class JcsTest {

  private static final Path GOLDEN = Path.of("..", "..", "..", "data", "golden");

  @Test
  void vectorJcsChoDungDangChuanHoaVaBam() throws IOException {
    Map<?, ?> doc = (Map<?, ?>) Jcs.parse(read(GOLDEN.resolve("jcs/vectors.json")));
    List<?> cases = (List<?>) doc.get("cases");
    assertThat(cases).hasSizeGreaterThanOrEqualTo(4);
    for (Object c : cases) {
      Map<?, ?> v = (Map<?, ?>) c;
      String canonical = Jcs.canonicalize((String) v.get("input"));
      assertThat(canonical).as("%s", v.get("id")).isEqualTo(v.get("canonical"));
      assertThat(Jcs.hex(Jcs.sha256(canonical))).as("%s", v.get("id")).isEqualTo(v.get("sha256"));
      // Chuẩn hoá lần hai không đổi (tính luỹ đẳng).
      assertThat(Jcs.canonicalize(canonical)).isEqualTo(canonical);
    }
    for (Object e : (List<?>) doc.get("errors")) {
      Map<?, ?> v = (Map<?, ?>) e;
      assertThatThrownBy(() -> Jcs.canonicalize((String) v.get("input")))
          .as("%s", v.get("id"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void taiTrongSoCaiChoCungBamVoiLedger() throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.list(GOLDEN.resolve("ledger-canon"))) {
      files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
    assertThat(files).isNotEmpty();
    for (Path f : files) {
      Map<?, ?> v = (Map<?, ?>) Jcs.parse(read(f));
      String canonical = Jcs.canonicalize((String) v.get("input_payload_raw"));
      assertThat(canonical).as("%s", f).isEqualTo(v.get("canonical_payload"));
      assertThat(Jcs.hex(Jcs.sha256(canonical))).as("%s", f).isEqualTo(v.get("payload_hash"));
    }
  }

  @Test
  void cayGiaTriJavaChuanHoaNhuVanBan() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("z", null);
    inner.put("y", List.of(2L, 1, (short) 3, (byte) 4));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("c", inner);
    root.put("b", new BigDecimal("4.50"));
    root.put("a", "Ho\u0300a");
    root.put("d", BigInteger.TEN);
    root.put("e", new StringBuilder("x"));
    root.put("f", true);
    assertThat(Jcs.write(root))
        .isEqualTo(
            "{\"a\":\"Hòa\",\"b\":4.5,\"c\":{\"y\":[2,1,3,4],\"z\":null},\"d\":10,\"e\":\"x\",\"f\":true}");
    assertThat(Jcs.hash(root)).hasSize(32).isEqualTo(Jcs.sha256(Jcs.write(root)));
  }

  @Test
  void soNgoaiVungAnToanVaSoDacBiet() {
    assertThat(Jcs.number(9007199254740993L)).isEqualTo("9007199254740992");
    assertThat(Jcs.number(new BigInteger("9007199254740993"))).isEqualTo("9007199254740992");
    assertThat(Jcs.number(-0.0)).isEqualTo("0");
    assertThat(Jcs.number(5e-324)).isEqualTo("5e-324");
    assertThat(Jcs.number(1.7976931348623157e308)).isEqualTo("1.7976931348623157e+308");
    assertThat(Jcs.number(123e18)).isEqualTo("123000000000000000000");
    assertThat(Jcs.number(0.5f)).isEqualTo("0.5");
    assertThatThrownBy(() -> Jcs.number(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Jcs.number(Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void khoaUtf16VaThoatKyTuDieuKhien() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("\uE000", 1);
    m.put("\uD83D\uDE00", 2);
    m.put("ctl", "\u0000\b\f\r\u001f");
    assertThat(Jcs.write(m))
        .isEqualTo("{\"ctl\":\"\\u0000\\b\\f\\r\\u001f\",\"\uD83D\uDE00\":2,\"\uE000\":1}");
  }

  @Test
  void loiKieuVaKhoaTrung() {
    Map<Object, Object> badKey = new LinkedHashMap<>();
    badKey.put(1, "x");
    assertThatThrownBy(() -> Jcs.write(badKey)).isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> dup = new LinkedHashMap<>();
    dup.put("ho\u0300a", 1);
    dup.put("hòa", 2);
    assertThatThrownBy(() -> Jcs.write(dup)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Jcs.write(new Object())).isInstanceOf(IllegalArgumentException.class);
    List<String> invalid =
        new ArrayList<>(
            List.of(
                "",
                "{\"a\" 1}",
                "{1:2}",
                "[1 2]",
                "{\"a\":1 \"b\":2}",
                "\"\\x\"",
                "\"\\u12\"",
                "\"a",
                "\"\u0001\"",
                "tru",
                "nul",
                "-",
                "1.",
                "[",
                "{\"a\":"));
    for (String s : invalid) {
      assertThatThrownBy(() -> Jcs.canonicalize(s))
          .as(s)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(Jcs.canonicalize(" [true,false,null,\"\\/\\b\\f\\r\\t\",-1E2] "))
        .isEqualTo("[true,false,null,\"/\\b\\f\\r\\t\",-100]");
  }

  private static String read(Path p) throws IOException {
    return Files.readString(p, StandardCharsets.UTF_8);
  }
}
