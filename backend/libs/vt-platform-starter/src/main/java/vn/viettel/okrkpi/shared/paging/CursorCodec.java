package vn.viettel.okrkpi.shared.paging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * Mã hoá con trỏ mờ của phân trang keyset ({@code common.yaml} tham số {@code Cursor}: "Không tự
 * dựng giá trị").
 *
 * <p>Định dạng: {@code base64url(payload || mac)} không đệm, trong đó {@code payload} là JSON gọn
 * {@code {"v":1,"t":<giây phát hành>,"k":[<keyset>]}} và {@code mac} là 16 byte đầu của
 * HMAC-SHA256(khoá, {@code scope || 0x00 || payload}). {@code scope} (tên danh sách kèm bộ lọc, thứ
 * tự) không nằm trong con trỏ nhưng nằm trong MAC, nên con trỏ của danh sách này không dùng được
 * cho danh sách khác. Con trỏ hết hạn sau {@code ttl}; tối đa 512 ký tự theo hợp đồng.
 */
public final class CursorCodec {

  /** Độ dài tối đa của con trỏ ({@code common.yaml}: {@code maxLength: 512}). */
  public static final int MAX_LENGTH = 512;

  static final int MAC_LENGTH = 16;
  private static final int VERSION = 1;
  private static final int MIN_KEY_BYTES = 32;
  private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
  private static final ObjectMapper JSON = new ObjectMapper();

  static final String INVALID =
      "Vị trí trang không hợp lệ hoặc đã hết hạn. Vui lòng tải lại danh sách.";
  static final String LIMIT_RANGE = "Số dòng mỗi trang phải từ 1 đến 200.";

  private final byte[] key;
  private final Duration ttl;
  private final Clock clock;

  /**
   * @param key khoá HMAC tối thiểu 32 byte (lấy từ tệp bí mật do ESO/CSI gắn, không đặt trong cấu
   *     hình dạng rõ)
   * @param ttl thời hạn con trỏ
   * @param clock đồng hồ
   */
  public CursorCodec(byte[] key, Duration ttl, Clock clock) {
    Objects.requireNonNull(key, "key");
    if (key.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("Khoá ký con trỏ cần tối thiểu 32 byte");
    }
    this.key = key.clone();
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl phải dương");
    }
  }

  /** Con trỏ cho keyset của dòng cuối trang. */
  public String encode(String scope, CursorKey after) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(after, "after");
    ObjectNode node = JSON.createObjectNode();
    node.put("v", VERSION);
    node.put("t", clock.instant().getEpochSecond());
    ArrayNode k = node.putArray("k");
    after.parts().forEach(k::add);
    byte[] payload;
    try {
      payload = JSON.writeValueAsBytes(node);
    } catch (IOException e) {
      throw new IllegalStateException("Không mã hoá được con trỏ", e);
    }
    byte[] mac = mac(scope, payload);
    byte[] token = Arrays.copyOf(payload, payload.length + MAC_LENGTH);
    System.arraycopy(mac, 0, token, payload.length, MAC_LENGTH);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    if (encoded.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("Con trỏ vượt " + MAX_LENGTH + " ký tự, keyset quá dài");
    }
    return encoded;
  }

  /**
   * Đọc con trỏ.
   *
   * @throws InvalidPageRequestException con trỏ sai dạng, bị sửa, sai phạm vi hoặc hết hạn
   */
  public CursorKey decode(String scope, String cursor) {
    Objects.requireNonNull(scope, "scope");
    if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
      throw invalid();
    }
    byte[] token;
    try {
      token = Base64.getUrlDecoder().decode(cursor);
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
    if (token.length <= MAC_LENGTH) {
      throw invalid();
    }
    byte[] payload = Arrays.copyOf(token, token.length - MAC_LENGTH);
    byte[] mac = Arrays.copyOfRange(token, token.length - MAC_LENGTH, token.length);
    if (!MessageDigest.isEqual(mac, mac(scope, payload))) {
      throw invalid();
    }
    JsonNode node;
    try {
      node = JSON.readTree(payload);
    } catch (IOException e) {
      throw invalid();
    }
    if (node == null
        || node.path("v").asInt(-1) != VERSION
        || !node.path("t").canConvertToLong()
        || !node.path("k").isArray()) {
      throw invalid();
    }
    Instant issued = Instant.ofEpochSecond(node.path("t").asLong());
    Instant now = clock.instant();
    if (issued.plus(ttl).isBefore(now) || issued.isAfter(now.plus(CLOCK_SKEW))) {
      throw invalid();
    }
    List<String> parts = new ArrayList<>();
    for (JsonNode part : node.path("k")) {
      if (!part.isTextual()) {
        throw invalid();
      }
      parts.add(part.asText());
    }
    try {
      return CursorKey.ofParts(parts);
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  /**
   * Kiểm tham số trang của hợp đồng.
   *
   * @param limit tham số {@code limit}, {@code null} dùng mặc định 20
   * @param cursor tham số {@code cursor}, {@code null}/rỗng là trang đầu
   * @param scope phạm vi con trỏ: tên danh sách kèm bộ lọc và thứ tự
   */
  public PageRequest pageRequest(Integer limit, String cursor, String scope) {
    int l = limit == null ? PageRequest.DEFAULT_LIMIT : limit;
    if (l < 1 || l > PageRequest.MAX_LIMIT) {
      throw new InvalidPageRequestException(InvalidPageRequestException.LIMIT, LIMIT_RANGE);
    }
    CursorKey after = cursor == null || cursor.isEmpty() ? null : decode(scope, cursor);
    return new PageRequest(l, after, scope);
  }

  /**
   * Dựng trang từ các dòng đã đọc với {@link PageRequest#fetchSize()} ({@code limit + 1}) theo thứ
   * tự keyset: nếu đọc được hơn {@code limit} dòng thì còn trang sau, con trỏ lấy keyset dòng cuối
   * của trang.
   */
  public <T> CursorPage<T> page(
      List<T> fetched, PageRequest request, Function<? super T, CursorKey> keyOf) {
    Objects.requireNonNull(fetched, "fetched");
    if (fetched.size() <= request.limit()) {
      return CursorPage.last(fetched);
    }
    List<T> items = fetched.subList(0, request.limit());
    String next = encode(request.scope(), keyOf.apply(items.get(items.size() - 1)));
    return new CursorPage<>(items, next);
  }

  private static InvalidPageRequestException invalid() {
    return new InvalidPageRequestException(InvalidPageRequestException.CURSOR, INVALID);
  }

  private byte[] mac(String scope, byte[] payload) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(key, "HmacSHA256"));
      hmac.update(scope.getBytes(StandardCharsets.UTF_8));
      hmac.update((byte) 0);
      hmac.update(payload);
      return Arrays.copyOf(hmac.doFinal(), MAC_LENGTH);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Không ký được con trỏ", e);
    }
  }
}
