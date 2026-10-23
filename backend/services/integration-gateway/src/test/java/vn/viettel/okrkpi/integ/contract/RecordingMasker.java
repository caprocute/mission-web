package vn.viettel.okrkpi.integ.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Công cụ che dữ liệu bản ghi tương tác với hệ ngoài trước khi đưa vào repo (DV-13-11). Thay họ
 * tên, số định danh (CCCD, CMND), số điện thoại, thư điện tử, địa chỉ bằng giá trị giả nhất quán
 * (cùng giá trị gốc cho cùng giá trị giả trong một lần che, nhờ HMAC-SHA256 với khoá của lần che),
 * giữ ngày sinh ở mức năm, xoá header xác thực và cookie. Giá trị giả không thể trùng dữ liệu thật:
 * số định danh mở đầu {@code 000} (không có mã tỉnh 000), số điện thoại mở đầu {@code 0000}, thư
 * điện tử thuộc miền {@code example.invalid}.
 *
 * <p>Chạy: {@code java ... RecordingMasker <thư mục bản ghi thô> <thư mục ra>}; khoá đọc từ biến
 * môi trường {@code VT_MASK_KEY} (base64, lấy từ OpenBao), thiếu thì sinh ngẫu nhiên cho lần chạy.
 * Bản ghi thô không bao giờ commit; chỉ bản ra (có {@code daChe: true}) được đặt vào {@code
 * data/golden/integ-recordings}.
 */
public final class RecordingMasker {

  static final Set<String> NAME_KEYS =
      Set.of(
          "hoten",
          "hovaten",
          "fullname",
          "signername",
          "nguoiky",
          "tennguoi",
          "tencanbo",
          "personname",
          "displayname");
  static final Set<String> ID_KEYS =
      Set.of(
          "nationalid",
          "sodinhdanh",
          "cccd",
          "socccd",
          "cmnd",
          "socmnd",
          "madinhdanh",
          "identitynumber",
          "socancuoc");
  static final Set<String> PHONE_KEYS =
      Set.of("phone", "phonenumber", "mobile", "sodienthoai", "dienthoai", "sdt");
  static final Set<String> EMAIL_KEYS = Set.of("email", "mail", "thudientu");
  static final Set<String> ADDRESS_KEYS = Set.of("diachi", "address", "noio", "quequan");
  static final Set<String> BIRTH_KEYS = Set.of("birthdate", "ngaysinh", "dateofbirth", "dob");
  static final Set<String> DROP_HEADERS =
      Set.of("cookie", "set-cookie", "proxy-authorization", "x-api-key");

  /** Mẫu quét dữ liệu cá nhân còn sót, dùng chung với kiểm thử quét bản ghi trong repo. */
  public static final Pattern CCCD = Pattern.compile("(?<!\\d)(?!000)\\d{12}(?!\\d)");

  public static final Pattern PHONE =
      Pattern.compile("(?<![\\d+])(?:\\+84|0)(?:3|5|7|8|9)\\d{8}(?!\\d)");
  public static final Pattern EMAIL =
      Pattern.compile(
          "[A-Za-z0-9._%+-]+@(?!example\\.(?:invalid|com|org|vn)\\b)[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  private static final List<String> HO =
      List.of("Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Vũ", "Đặng", "Bùi", "Đỗ", "Ngô");
  private static final List<String> DEM =
      List.of("Văn", "Thị", "Hữu", "Minh", "Thu", "Đức", "Ngọc", "Quang");
  private static final List<String> TEN =
      List.of(
          "An", "Bình", "Châu", "Dũng", "Giang", "Hà", "Khánh", "Lan", "Mai", "Nam", "Phương",
          "Quân", "Sơn", "Trang", "Vy", "Yến");
  private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-\\d{2}-\\d{2}");
  private static final Pattern VN_DATE = Pattern.compile("\\d{2}/\\d{2}/(\\d{4})");

  private final Mac mac;

  public RecordingMasker(byte[] key) {
    try {
      mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Bản ghi đã che, đánh dấu {@code daChe: true}; bản gốc không đổi. */
  public JsonNode mask(JsonNode recording) {
    JsonNode out = recording.deepCopy();
    for (JsonNode x : out.path("exchanges")) {
      for (String side : List.of("request", "response")) {
        JsonNode part = x.path(side);
        if (part.path("headers") instanceof ObjectNode h) {
          headers(h);
        }
        if (part instanceof ObjectNode p) {
          if (p.has("body")) {
            p.set("body", value(null, p.get("body")));
          }
          if (p.path("query") instanceof ObjectNode q) {
            p.set("query", value(null, q));
          }
        }
      }
    }
    if (out instanceof ObjectNode o) {
      o.put("daChe", true);
    }
    return out;
  }

  private void headers(ObjectNode h) {
    List<String> names = new java.util.ArrayList<>();
    h.fieldNames().forEachRemaining(names::add);
    for (String n : names) {
      String lower = n.toLowerCase(Locale.ROOT);
      if (DROP_HEADERS.contains(lower)) {
        h.remove(n);
      } else if ("authorization".equals(lower)) {
        h.put(n, "Bearer ma-gia-lap");
      }
    }
  }

  JsonNode value(String key, JsonNode n) {
    if (n.isObject()) {
      ObjectNode o = (ObjectNode) n.deepCopy();
      for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        o.set(e.getKey(), value(e.getKey(), e.getValue()));
      }
      return o;
    }
    if (n.isArray()) {
      ArrayNode a = Pact.JSON.createArrayNode();
      n.forEach(x -> a.add(value(key, x)));
      return a;
    }
    if (n.isNull() || n.isBoolean() || (n.isNumber() && key == null)) {
      return n;
    }
    String k = key == null ? "" : normalize(key);
    String s = n.asText();
    if (NAME_KEYS.contains(k)) {
      return TextNode.valueOf(name(s));
    }
    if (ID_KEYS.contains(k)) {
      return TextNode.valueOf(nationalId(s));
    }
    if (PHONE_KEYS.contains(k)) {
      return TextNode.valueOf(phone(s));
    }
    if (EMAIL_KEYS.contains(k)) {
      return TextNode.valueOf(email(s));
    }
    if (ADDRESS_KEYS.contains(k)) {
      return TextNode.valueOf("Địa chỉ đã che " + digits("dc", s, 4));
    }
    if (BIRTH_KEYS.contains(k)) {
      return TextNode.valueOf(birth(s));
    }
    return n.isTextual() ? TextNode.valueOf(text(s)) : n;
  }

  /** Che số định danh, số điện thoại, thư điện tử nằm trong văn bản tự do. */
  public String text(String s) {
    s = replace(CCCD, s, this::nationalId);
    s = replace(PHONE, s, this::phone);
    return replace(EMAIL, s, this::email);
  }

  private static String replace(Pattern p, String s, java.util.function.UnaryOperator<String> f) {
    Matcher m = p.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m.group())));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  String name(String s) {
    byte[] h = hash("ten", s);
    return HO.get(Byte.toUnsignedInt(h[0]) % HO.size())
        + " "
        + DEM.get(Byte.toUnsignedInt(h[1]) % DEM.size())
        + " "
        + TEN.get(Byte.toUnsignedInt(h[2]) % TEN.size());
  }

  String nationalId(String s) {
    return "000" + digits("cccd", s, 9);
  }

  String phone(String s) {
    return "0000" + digits("sdt", s, 6);
  }

  String email(String s) {
    return "nguoi-" + HexFormat.of().formatHex(hash("mail", s), 0, 4) + "@example.invalid";
  }

  static String birth(String s) {
    Matcher iso = ISO_DATE.matcher(s);
    if (iso.matches()) {
      return iso.group(1) + "-01-01";
    }
    Matcher vn = VN_DATE.matcher(s);
    return vn.matches() ? "01/01/" + vn.group(1) : "1970-01-01";
  }

  private String digits(String kind, String s, int n) {
    byte[] h = hash(kind, s);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; sb.length() < n; i++) {
      sb.append(Byte.toUnsignedInt(h[i % h.length]) % 10);
    }
    return sb.toString();
  }

  private synchronized byte[] hash(String kind, String s) {
    return mac.doFinal((kind + ":" + s.strip()).getBytes(StandardCharsets.UTF_8));
  }

  /** Tên trường bỏ dấu, bỏ khoảng trắng và gạch, chữ thường: "Họ và tên" thành "hovaten". */
  static String normalize(String key) {
    String n = Normalizer.normalize(key.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
    return n.replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("Cách dùng: RecordingMasker <thư mục bản ghi thô> <thư mục ra>");
      System.exit(2);
    }
    String env = System.getenv("VT_MASK_KEY");
    byte[] key;
    if (env == null || env.isBlank()) {
      key = new byte[32];
      new SecureRandom().nextBytes(key);
      System.err.println(
          "Không có VT_MASK_KEY: dùng khoá ngẫu nhiên, giá trị giả chỉ nhất quán"
              + " trong lần chạy này");
    } else {
      key = Base64.getDecoder().decode(env.strip());
    }
    RecordingMasker masker = new RecordingMasker(key);
    Path in = Path.of(args[0]);
    Path out = Path.of(args[1]);
    Files.createDirectories(out);
    try (Stream<Path> files = Files.list(in)) {
      for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
        JsonNode masked = masker.mask(Pact.JSON.readTree(f.toFile()));
        Pact.JSON
            .writerWithDefaultPrettyPrinter()
            .writeValue(out.resolve(f.getFileName()).toFile(), masked);
        System.out.println("Đã che " + f.getFileName());
      }
    }
  }
}
