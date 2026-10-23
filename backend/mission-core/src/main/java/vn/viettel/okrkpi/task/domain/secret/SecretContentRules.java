package vn.viettel.okrkpi.task.domain.secret;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Kiểm dạng bản mã và khoá đã bọc của nội dung nhiệm vụ mật (ADR-27, TL-03 {@code
 * task.task_secret_content}, {@code task.e2ee_key_wrap}). Máy chủ không đọc được nội dung, nên chỉ
 * kiểm cấu trúc: nonce 96 bit, băm dạng hex, SHA-256 khai báo khớp bản mã nhận được, mỗi khoá công
 * khai một bản bọc, thuật toán thuộc danh sách của hợp đồng và luôn có bản bọc cho khoá phục hồi tổ
 * chức (Shamir 3 trên 5 ở máy trạm cách ly).
 */
public final class SecretContentRules {

  /** Trần bản mã sau khi giải base64: tiêu đề thật, mô tả và chỉ đạo (suy luận). */
  public static final int MAX_CIPHERTEXT_BYTES = 1 << 20;

  public static final int MAX_WRAPS = 200;
  public static final int MAX_WRAPPED_KEY_BYTES = 1024;
  public static final int MAX_EPHEMERAL_BYTES = 256;
  public static final int NONCE_BYTES = 12;
  public static final String ECDH = "ECDH-ES+A256KW";
  public static final String RSA = "RSA-OAEP-256";
  public static final Set<String> ALGORITHMS = Set.of(ECDH, RSA);

  private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

  private SecretContentRules() {}

  /** Bản mã đã kiểm. */
  public record Sealed(byte[] ciphertext, byte[] nonce, byte[] aadHash, byte[] sha256) {}

  /** Khoá phục hồi tổ chức: vân tay các khoá được chấp nhận và mã người nhận đại diện. */
  public record RecoveryKey(Set<String> deviceIds, UUID recipientId) {

    public RecoveryKey {
      deviceIds = Set.copyOf(deviceIds);
    }

    public boolean covers(KeyWrap w) {
      return deviceIds.contains(w.deviceId());
    }
  }

  /** Lỗi dữ liệu đầu vào, kèm tên trường theo hợp đồng. */
  public static final class Violation extends IllegalArgumentException {

    private final String field;

    public Violation(String field, String message) {
      super(message);
      this.field = field;
    }

    public String field() {
      return field;
    }
  }

  public static Sealed seal(String ciphertext, String nonce, String aadHash, String sha256) {
    byte[] c = base64("ciphertext", ciphertext);
    if (c.length == 0 || c.length > MAX_CIPHERTEXT_BYTES) {
      throw new Violation("ciphertext", "Bản mã rỗng hoặc vượt quá 1 MB");
    }
    byte[] n = base64("nonce", nonce);
    if (n.length != NONCE_BYTES) {
      throw new Violation("nonce", "Nonce phải dài 96 bit");
    }
    byte[] aad = hex("aadHash", aadHash);
    byte[] declared = hex("sha256", sha256);
    if (!MessageDigest.isEqual(declared, sha256(c))) {
      throw new Violation("sha256", "Mã băm không khớp bản mã đã gửi");
    }
    return new Sealed(c, n, aad, declared);
  }

  /** Một khoá đã bọc từ hợp đồng {@code TaskKeyWrap}. */
  public static KeyWrap wrap(
      int index,
      UUID recipientId,
      String deviceId,
      String algorithm,
      String ephemeralPublicKey,
      String wrappedKey) {
    String at = "keyWraps[" + index + "].";
    if (recipientId == null) {
      throw new Violation(at + "recipientPersonId", "Thiếu người nhận");
    }
    byte[] fp = hex(at + "deviceId", deviceId);
    String alg = algorithm == null ? ECDH : algorithm;
    if (!ALGORITHMS.contains(alg)) {
      throw new Violation(at + "algorithm", "Thuật toán bọc khoá không được hỗ trợ");
    }
    byte[] key = base64(at + "wrappedKey", wrappedKey);
    if (key.length == 0 || key.length > MAX_WRAPPED_KEY_BYTES) {
      throw new Violation(at + "wrappedKey", "Khoá đã bọc rỗng hoặc quá dài");
    }
    byte[] eph = null;
    if (ephemeralPublicKey != null && !ephemeralPublicKey.isEmpty()) {
      eph = base64(at + "ephemeralPublicKey", ephemeralPublicKey);
      if (eph.length > MAX_EPHEMERAL_BYTES) {
        throw new Violation(at + "ephemeralPublicKey", "Khoá công khai tạm thời quá dài");
      }
    }
    if (ECDH.equals(alg) && (eph == null || eph.length == 0)) {
      throw new Violation(
          at + "ephemeralPublicKey", "Bọc khoá ECDH-ES cần khoá công khai tạm thời");
    }
    if (RSA.equals(alg) && eph != null) {
      throw new Violation(at + "ephemeralPublicKey", "Bọc khoá RSA-OAEP không dùng khoá tạm thời");
    }
    return new KeyWrap(recipientId, fp, alg, eph, key, null, null, null);
  }

  /** Danh sách bọc của một lần gửi: không rỗng, không quá trần, mỗi khoá công khai một lần. */
  public static List<KeyWrap> distinct(List<KeyWrap> wraps) {
    if (wraps.isEmpty()) {
      throw new Violation("keyWraps", "Cần ít nhất một khoá đã bọc");
    }
    if (wraps.size() > MAX_WRAPS) {
      throw new Violation("keyWraps", "Tối đa " + MAX_WRAPS + " khoá đã bọc mỗi lần");
    }
    Set<String> seen = new HashSet<>();
    List<KeyWrap> out = new ArrayList<>();
    for (int i = 0; i < wraps.size(); i++) {
      if (!seen.add(wraps.get(i).deviceId())) {
        throw new Violation("keyWraps[" + i + "].deviceId", "Khoá công khai bị lặp");
      }
      out.add(wraps.get(i));
    }
    return out;
  }

  /**
   * Khoá phục hồi chỉ đứng tên người nhận đại diện; bọc cho khoá phục hồi mà ghi người khác là sai.
   */
  public static void requireRecoveryRecipient(List<KeyWrap> wraps, RecoveryKey recovery) {
    for (int i = 0; i < wraps.size(); i++) {
      KeyWrap w = wraps.get(i);
      if (recovery.covers(w) && !w.recipientId().equals(recovery.recipientId())) {
        throw new Violation(
            "keyWraps[" + i + "].recipientPersonId", "Khoá phục hồi phải ghi người nhận đại diện");
      }
    }
  }

  /** Tập bọc còn hiệu lực sau thao tác phải còn ít nhất một bản cho khoá phục hồi tổ chức. */
  public static void requireRecovery(Collection<KeyWrap> active, RecoveryKey recovery) {
    if (recovery == null || recovery.deviceIds().isEmpty()) {
      throw new Violation("keyWraps", "Tổ chức chưa khai báo khoá phục hồi cho nội dung mật");
    }
    if (active.stream().filter(KeyWrap::active).noneMatch(recovery::covers)) {
      throw new Violation("keyWraps", "Thiếu khoá đã bọc cho khoá phục hồi của tổ chức");
    }
  }

  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] base64(String field, String value) {
    if (value == null) {
      throw new Violation(field, "Thiếu giá trị");
    }
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new Violation(field, "Giá trị không phải base64");
    }
  }

  private static byte[] hex(String field, String value) {
    if (value == null || !HEX64.matcher(value).matches()) {
      throw new Violation(field, "Giá trị phải là 64 ký tự hex chữ thường");
    }
    return HexFormat.of().parseHex(value);
  }
}
