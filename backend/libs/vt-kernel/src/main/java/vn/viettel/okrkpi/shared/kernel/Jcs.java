package vn.viettel.okrkpi.shared.kernel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Chuẩn hoá JSON theo RFC 8785 (JSON Canonicalization Scheme) cộng chuẩn hoá Unicode NFC trên mọi
 * khoá và chuỗi, rồi băm SHA-256 (DV-07-01, TL-03 "Schema scoring": {@code input_hash}, {@code
 * result_hash}). Cùng quy tắc với gói {@code canon} của ledger-service (Go) để hai bên cho cùng băm
 * trên bộ vector {@code data/golden/ledger-canon} và {@code data/golden/jcs}:
 *
 * <ul>
 *   <li>khoá đối tượng sắp theo đơn vị mã UTF-16; hai khoá trùng nhau sau NFC là lỗi;
 *   <li>chuỗi chỉ thoát {@code "}, {@code \}, và ký tự điều khiển U+0000..U+001F;
 *   <li>số in theo {@code Number.prototype.toString} của ECMAScript trên giá trị IEEE-754 double;
 *       số không hữu hạn là lỗi.
 * </ul>
 *
 * <p>Giá trị thập phân cần giữ đúng số chữ số (điểm {@code numeric(6,3)}) nên truyền dạng chuỗi, vì
 * JCS đưa mọi số về double.
 */
public final class Jcs {

  private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

  private Jcs() {}

  /** Dạng chuẩn hoá của một tài liệu JSON dạng văn bản. */
  public static String canonicalize(String json) {
    return write(parse(json));
  }

  /**
   * Đọc tài liệu JSON thành cây giá trị ({@link Map} giữ thứ tự, {@link List}, {@link String} đã
   * NFC ở khoá, {@link Double}, {@link Boolean}, {@code null}); khoá trùng sau NFC là lỗi.
   */
  public static Object parse(String json) {
    Parser parser = new Parser(json);
    Object value = parser.value();
    parser.end();
    return value;
  }

  /**
   * Dạng chuẩn hoá của cây giá trị Java: {@link Map} (khoá là chuỗi), {@link Collection}, {@link
   * CharSequence}, {@link Number}, {@link Boolean}, {@code null}.
   */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  /** SHA-256 của dạng chuẩn hoá (UTF-8) của cây giá trị. */
  public static byte[] hash(Object value) {
    return sha256(write(value));
  }

  /** SHA-256 của một chuỗi đã chuẩn hoá (UTF-8). */
  public static byte[] sha256(String canonical) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }

  /** Băm dạng hex chữ thường, như cột {@code *_hash} hiển thị và vector kiểm thử. */
  public static String hex(byte[] hash) {
    return HexFormat.of().formatHex(hash);
  }

  private static void append(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof Boolean b) {
      out.append(b.booleanValue());
    } else if (value instanceof Number n) {
      out.append(number(n));
    } else if (value instanceof CharSequence s) {
      string(out, nfc(s.toString()));
    } else if (value instanceof Map<?, ?> m) {
      object(out, m);
    } else if (value instanceof Collection<?> c) {
      out.append('[');
      boolean first = true;
      for (Object item : c) {
        if (!first) {
          out.append(',');
        }
        first = false;
        append(out, item);
      }
      out.append(']');
    } else {
      throw new IllegalArgumentException(
          "Kiểu không chuẩn hoá được theo RFC 8785: " + value.getClass().getName());
    }
  }

  private static void object(StringBuilder out, Map<?, ?> m) {
    // String.compareTo so sánh theo đơn vị mã UTF-16 đúng như RFC 8785 mục 3.2.3.
    TreeMap<String, Object> sorted = new TreeMap<>();
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (!(e.getKey() instanceof CharSequence k)) {
        throw new IllegalArgumentException("Khoá đối tượng JSON phải là chuỗi");
      }
      String key = nfc(k.toString());
      if (sorted.containsKey(key)) {
        throw new IllegalArgumentException("Trùng khoá đối tượng sau chuẩn hoá NFC: " + key);
      }
      sorted.put(key, e.getValue());
    }
    out.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> e : sorted.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      string(out, e.getKey());
      out.append(':');
      append(out, e.getValue());
    }
    out.append('}');
  }

  private static String nfc(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFC);
  }

  private static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** Số theo ECMAScript {@code Number.prototype.toString} (RFC 8785 mục 3.2.2.3). */
  static String number(Number n) {
    if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
      long v = n.longValue();
      if (Math.abs(v) <= MAX_SAFE_INTEGER) {
        return Long.toString(v);
      }
    }
    if (n instanceof BigInteger bi
        && bi.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) <= 0) {
      return bi.toString();
    }
    double d = n instanceof BigDecimal bd ? bd.doubleValue() : n.doubleValue();
    return number(d);
  }

  static String number(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("RFC 8785 không cho phép số không hữu hạn");
    }
    if (d == 0) {
      return "0";
    }
    // Java 19+ Double.toString cho dãy chữ số ngắn nhất khôi phục đúng giá trị, như ECMAScript.
    BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
    if (shortest.precision() == 2) {
      // Double.toString luôn in ít nhất hai chữ số (4.9E-324) dù một chữ số đã đủ khôi phục
      // (5e-324).
      BigDecimal one = shortest.round(new java.math.MathContext(1));
      if (one.doubleValue() == Math.abs(d)) {
        shortest = one.stripTrailingZeros();
      }
    }
    String digits = shortest.unscaledValue().toString();
    int k = digits.length();
    int exp = k - shortest.scale(); // giá trị = 0.digits × 10^exp
    StringBuilder out = new StringBuilder();
    if (d < 0) {
      out.append('-');
    }
    if (k <= exp && exp <= 21) {
      out.append(digits).append("0".repeat(exp - k));
    } else if (0 < exp && exp <= 21) {
      out.append(digits, 0, exp).append('.').append(digits, exp, k);
    } else if (-6 < exp && exp <= 0) {
      out.append("0.").append("0".repeat(-exp)).append(digits);
    } else {
      int e = exp - 1;
      out.append(digits.charAt(0));
      if (k > 1) {
        out.append('.').append(digits, 1, k);
      }
      out.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
    }
    return out.toString();
  }

  /** Bộ đọc JSON tối thiểu (RFC 8259), giữ số dạng double như RFC 8785 yêu cầu. */
  private static final class Parser {
    private final String s;
    private int pos;

    Parser(String s) {
      this.s = s;
    }

    Object value() {
      skipWs();
      if (pos >= s.length()) {
        throw error("thiếu giá trị");
      }
      char c = s.charAt(pos);
      return switch (c) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    void end() {
      skipWs();
      if (pos != s.length()) {
        throw error("còn dữ liệu thừa sau giá trị gốc");
      }
    }

    private Map<String, Object> object() {
      pos++;
      Map<String, Object> m = new LinkedHashMap<>();
      skipWs();
      if (peek() == '}') {
        pos++;
        return m;
      }
      while (true) {
        skipWs();
        if (peek() != '"') {
          throw error("khoá đối tượng phải là chuỗi");
        }
        String key = nfc(string());
        skipWs();
        expect(':');
        Object v = value();
        if (m.containsKey(key)) {
          throw error("trùng khoá đối tượng sau chuẩn hoá NFC: " + key);
        }
        m.put(key, v);
        skipWs();
        char c = next();
        if (c == '}') {
          return m;
        }
        if (c != ',') {
          throw error("thiếu dấu phẩy hoặc '}'");
        }
      }
    }

    private List<Object> array() {
      pos++;
      List<Object> list = new ArrayList<>();
      skipWs();
      if (peek() == ']') {
        pos++;
        return list;
      }
      while (true) {
        list.add(value());
        skipWs();
        char c = next();
        if (c == ']') {
          return list;
        }
        if (c != ',') {
          throw error("thiếu dấu phẩy hoặc ']'");
        }
      }
    }

    private String string() {
      pos++;
      StringBuilder out = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') {
          return out.toString();
        }
        if (c < 0x20) {
          throw error("ký tự điều khiển chưa thoát trong chuỗi");
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        char e = next();
        switch (e) {
          case '"', '\\', '/' -> out.append(e);
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (pos + 4 > s.length()) {
              throw error("thoát \\u thiếu chữ số");
            }
            out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> throw error("thoát chuỗi không hợp lệ");
        }
      }
    }

    private Object number() {
      int start = pos;
      while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
        pos++;
      }
      String raw = s.substring(start, pos);
      if (!raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
        throw error("số không hợp lệ: " + raw);
      }
      return Double.parseDouble(raw);
    }

    private Object literal(String word, Object value) {
      if (!s.startsWith(word, pos)) {
        throw error("giá trị không hợp lệ");
      }
      pos += word.length();
      return value;
    }

    private void skipWs() {
      while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) {
        pos++;
      }
    }

    private char peek() {
      return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private char next() {
      if (pos >= s.length()) {
        throw error("kết thúc tài liệu bất ngờ");
      }
      return s.charAt(pos++);
    }

    private void expect(char c) {
      if (next() != c) {
        throw error("thiếu '" + c + "'");
      }
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException("JSON không hợp lệ tại vị trí " + pos + ": " + message);
    }
  }
}
