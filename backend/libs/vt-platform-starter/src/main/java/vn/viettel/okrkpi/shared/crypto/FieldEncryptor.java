package vn.viettel.okrkpi.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;

/**
 * Mã hoá và giải mã trường dữ liệu C2 theo định dạng phong bì chuẩn của TL-01 và TL-03 (NEN-015,
 * ADR-06, ADR-17, ADR-30):
 *
 * <pre>{@code
 * version (1 byte = 0x01)
 * || dek_ref (16 byte UUID big-endian)
 * || nonce (12 byte ngẫu nhiên)
 * || ciphertext (N byte)
 * || tag (16 byte GCM authentication tag)
 * }</pre>
 *
 * <p>Dữ liệu xác thực bổ sung (AAD) bắt buộc có dạng {@code tenant_id|schema.bảng.cột|id dòng}
 * (UTF-8); sao chép bản mã sang dòng khác, cột khác hoặc tenant khác sẽ làm xác thực thẻ GCM thất
 * bại và ném {@link CryptoException}.
 */
public final class FieldEncryptor {

  /** Độ dài phần phiên bản (1 byte). */
  public static final int VERSION_LENGTH = 1;

  /** Độ dài định danh DEK nhị phân (16 byte UUID). */
  public static final int DEK_REF_LENGTH = 16;

  /** Độ dài nonce GCM (12 byte). */
  public static final int NONCE_LENGTH = CryptoProvider.NONCE_LENGTH_BYTES;

  /** Độ dài thẻ xác thực GCM (16 byte). */
  public static final int TAG_LENGTH = CryptoProvider.TAG_LENGTH_BYTES;

  /** Độ dài phần đầu bản mã trước phần thân: {@code 1 + 16 + 12 = 29} byte. */
  public static final int HEADER_LENGTH = VERSION_LENGTH + DEK_REF_LENGTH + NONCE_LENGTH;

  /** Độ dài tối thiểu của một bản mã hợp lệ (khi bản rõ dài 0 byte): {@code 29 + 16 = 45} byte. */
  public static final int MIN_ENVELOPE_LENGTH = HEADER_LENGTH + TAG_LENGTH;

  private final DekService dekService;
  private final CryptoProvider cryptoProvider;

  public FieldEncryptor(DekService dekService, CryptoProvider cryptoProvider) {
    this.dekService = Objects.requireNonNull(dekService, "dekService");
    this.cryptoProvider = Objects.requireNonNull(cryptoProvider, "cryptoProvider");
  }

  /**
   * Mã hoá mảng byte bản rõ C2 và trả về cả {@code dekId} lẫn mảng byte phong bì.
   *
   * @param tenantId định danh tenant
   * @param purpose mục đích sử dụng DEK (theo TL-03 {@code keyring.dek.purpose})
   * @param aadParts các thành phần AAD (ví dụ {@code List.of("appraisal.self_review.comment_enc",
   *     rowId.toString())})
   * @param plaintext bản rõ cần mã hoá
   * @return {@link EncryptedField} chứa {@code dekId} và {@code ciphertext}
   */
  public EncryptedField encryptField(
      UUID tenantId, DekPurpose purpose, List<String> aadParts, byte[] plaintext) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(plaintext, "plaintext");
    byte[] aad = buildAad(tenantId, aadParts);
    CachedDek dek = dekService.getOrCreateActiveDek(tenantId, purpose);
    byte[] rawKey = dek.keyBytes();
    try {
      byte[] nonce = cryptoProvider.generateNonce();
      byte[] envelope = packEnvelope(dek.dekId(), rawKey, nonce, aad, plaintext);
      return new EncryptedField(dek.dekId(), envelope);
    } finally {
      DekCache.zeroKey(rawKey);
    }
  }

  /** Mã hoá mảng byte bản rõ C2 và trả về mảng byte bản mã phong bì. */
  public byte[] encrypt(
      UUID tenantId, DekPurpose purpose, List<String> aadParts, byte[] plaintext) {
    return encryptField(tenantId, purpose, aadParts, plaintext).ciphertext();
  }

  /** Mã hoá chuỗi UTF-8 bản rõ C2 và trả về mảng byte bản mã phong bì. */
  public byte[] encrypt(
      UUID tenantId, DekPurpose purpose, List<String> aadParts, String plaintextUtf8) {
    Objects.requireNonNull(plaintextUtf8, "plaintextUtf8");
    return encrypt(tenantId, purpose, aadParts, plaintextUtf8.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Giải mã bản mã phong bì C2, đồng thời kiểm tra mục đích DEK khớp với {@code expectedPurpose}.
   */
  public byte[] decrypt(
      UUID tenantId, DekPurpose expectedPurpose, List<String> aadParts, byte[] envelope) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(envelope, "envelope");
    if (envelope.length < MIN_ENVELOPE_LENGTH) {
      throw new CryptoException(
          "Bản mã C2 không hợp lệ: độ dài "
              + envelope.length
              + " nhỏ hơn tối thiểu "
              + MIN_ENVELOPE_LENGTH
              + " byte");
    }
    byte version = envelope[0];
    if (version != cryptoProvider.algorithmVersion()) {
      throw new CryptoException("Phiên bản bản mã C2 không được hỗ trợ: " + version);
    }
    byte[] aad = buildAad(tenantId, aadParts);
    UUID dekId = extractDekId(envelope);
    CachedDek dek = dekService.getDekForDecryption(tenantId, dekId);
    if (expectedPurpose != null && dek.purpose() != expectedPurpose) {
      throw new CryptoException(
          "Mục đích của DEK ("
              + dek.purpose()
              + ") không khớp mục đích yêu cầu ("
              + expectedPurpose
              + ")");
    }
    byte[] rawKey = dek.keyBytes();
    try {
      byte[] nonce = new byte[NONCE_LENGTH];
      System.arraycopy(envelope, VERSION_LENGTH + DEK_REF_LENGTH, nonce, 0, NONCE_LENGTH);
      int bodyWithTagLength = envelope.length - HEADER_LENGTH;
      byte[] bodyWithTag = new byte[bodyWithTagLength];
      System.arraycopy(envelope, HEADER_LENGTH, bodyWithTag, 0, bodyWithTagLength);
      return cryptoProvider.decrypt(rawKey, nonce, aad, bodyWithTag);
    } finally {
      DekCache.zeroKey(rawKey);
    }
  }

  /** Giải mã bản mã phong bì C2 theo {@code dek_id} nhúng trong phần đầu bản mã. */
  public byte[] decrypt(UUID tenantId, List<String> aadParts, byte[] envelope) {
    return decrypt(tenantId, null, aadParts, envelope);
  }

  /** Giải mã bản mã phong bì C2 sang chuỗi UTF-8 (có kiểm tra {@code expectedPurpose}). */
  public String decryptToString(
      UUID tenantId, DekPurpose expectedPurpose, List<String> aadParts, byte[] envelope) {
    byte[] plain = decrypt(tenantId, expectedPurpose, aadParts, envelope);
    return new String(plain, StandardCharsets.UTF_8);
  }

  /** Giải mã bản mã phong bì C2 sang chuỗi UTF-8. */
  public String decryptToString(UUID tenantId, List<String> aadParts, byte[] envelope) {
    byte[] plain = decrypt(tenantId, null, aadParts, envelope);
    return new String(plain, StandardCharsets.UTF_8);
  }

  /**
   * Mã hoá tất định với {@code dekId}, {@code rawKey} và {@code nonce} cố định — chỉ dùng để tạo
   * hoặc kiểm chứng vector kiểm thử liên ngôn ngữ (Java / Go NEN-017 / Python NEN-018).
   */
  public byte[] encryptDeterministicForVector(
      UUID tenantId,
      UUID dekId,
      byte[] rawKey,
      byte[] nonce,
      List<String> aadParts,
      byte[] plaintext) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(dekId, "dekId");
    Objects.requireNonNull(rawKey, "rawKey");
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(plaintext, "plaintext");
    byte[] aad = buildAad(tenantId, aadParts);
    return packEnvelope(dekId, rawKey, nonce, aad, plaintext);
  }

  /** Trích xuất {@code dek_id} (UUID 16 byte big-endian ở offset 1..17) từ bản mã phong bì. */
  public static UUID extractDekId(byte[] envelope) {
    Objects.requireNonNull(envelope, "envelope");
    if (envelope.length < VERSION_LENGTH + DEK_REF_LENGTH) {
      throw new CryptoException("Bản mã quá ngắn, không đọc được dek_ref (16 byte)");
    }
    ByteBuffer buf = ByteBuffer.wrap(envelope, VERSION_LENGTH, DEK_REF_LENGTH);
    long msb = buf.getLong();
    long lsb = buf.getLong();
    return new UUID(msb, lsb);
  }

  /**
   * Dựng mảng byte AAD chuẩn UTF-8 theo quy ước {@code tenant_id|schema.bảng.cột|id dòng}.
   *
   * <p>Nếu phần tử đầu tiên của {@code aadParts} chưa phải là {@code tenantId.toString()}, phương
   * thức tự động ghép {@code tenantId} vào đầu. Sau khi ghép, chuỗi AAD phải có tối thiểu 3 thành
   * phần phân cách bởi {@code '|'}.
   */
  public static byte[] buildAad(UUID tenantId, List<String> aadParts) {
    Objects.requireNonNull(tenantId, "tenantId");
    if (aadParts == null || aadParts.isEmpty()) {
      throw new CryptoException("aadParts không được rỗng (cần schema.bảng.cột và id dòng)");
    }
    String tenantStr = tenantId.toString();
    StringBuilder sb = new StringBuilder(64);
    int startIndex = 0;
    String first = aadParts.get(0);
    if (first == null || first.isBlank()) {
      throw new CryptoException("Thành phần AAD không được null hoặc trống");
    }
    if (tenantStr.equals(first)) {
      sb.append(tenantStr);
      startIndex = 1;
    } else {
      sb.append(tenantStr);
    }
    for (int i = startIndex; i < aadParts.size(); i++) {
      String part = aadParts.get(i);
      if (part == null || part.isBlank()) {
        throw new CryptoException("Thành phần AAD tại vị trí " + i + " không được null hoặc trống");
      }
      sb.append('|').append(part.trim());
    }
    String aadStr = sb.toString();
    int firstSep = aadStr.indexOf('|');
    int secondSep = firstSep < 0 ? -1 : aadStr.indexOf('|', firstSep + 1);
    if (firstSep <= 0 || secondSep <= firstSep + 1 || secondSep == aadStr.length() - 1) {
      throw new CryptoException("AAD phải có đủ 3 thành phần 'tenant_id|schema.bảng.cột|id dòng'");
    }
    return aadStr.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] packEnvelope(
      UUID dekId, byte[] rawKey, byte[] nonce, byte[] aad, byte[] plaintext) {
    byte[] bodyWithTag = cryptoProvider.encrypt(rawKey, nonce, aad, plaintext);
    ByteBuffer out = ByteBuffer.allocate(HEADER_LENGTH + bodyWithTag.length);
    out.put(cryptoProvider.algorithmVersion());
    out.putLong(dekId.getMostSignificantBits());
    out.putLong(dekId.getLeastSignificantBits());
    out.put(nonce);
    out.put(bodyWithTag);
    return out.array();
  }
}
