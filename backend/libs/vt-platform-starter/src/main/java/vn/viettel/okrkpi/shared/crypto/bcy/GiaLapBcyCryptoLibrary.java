package vn.viettel.okrkpi.shared.crypto.bcy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Thư viện Ban Cơ yếu giả lập cho dev và kiểm thử (DV-10-05, YC-PT-13): cùng giao diện bọc {@link
 * BcyCryptoLibrary}, bên trong mượn JCA. Không phải mật mã Ban Cơ yếu; {@link #simulated()} trả
 * {@code true} nên cấu hình từ chối nó ở hồ sơ production.
 *
 * <p>Tham số {@code open}: {@code slot} (bắt buộc, giả lập "thiết bị có mặt"), {@code device-file}
 * (tuỳ chọn: tệp phải tồn tại, để thử nhánh thiếu thiết bị), {@code key-labels} (danh sách nhãn
 * khoá ký, phân tách bằng dấu phẩy).
 */
public final class GiaLapBcyCryptoLibrary implements BcyCryptoLibrary {

  public static final String FORMAT = "CADES_B_T";
  static final Map<Role, String> ALGORITHMS = algorithms0();

  private final SecureRandom random = new SecureRandom();
  private final Clock clock;
  private final Map<String, KeyPair> keys = new ConcurrentHashMap<>();
  private byte[] tsaKey;
  private volatile boolean open;

  public GiaLapBcyCryptoLibrary() {
    this(Clock.systemUTC());
  }

  GiaLapBcyCryptoLibrary(Clock clock) {
    this.clock = clock;
  }

  private static Map<Role, String> algorithms0() {
    Map<Role, String> m = new EnumMap<>(Role.class);
    m.put(Role.AEAD, "GIA_LAP_AEAD");
    m.put(Role.MAC, "GIA_LAP_MAC");
    m.put(Role.DIGEST, "GIA_LAP_BAM");
    m.put(Role.SIGNATURE, "GIA_LAP_KY");
    m.put(Role.TIMESTAMP, "GIA_LAP_DAU_THOI_GIAN");
    m.put(Role.RANDOM, "GIA_LAP_NGAU_NHIEN");
    return Map.copyOf(m);
  }

  @Override
  public String description() {
    return "Thư viện Ban Cơ yếu giả lập 1.0";
  }

  @Override
  public boolean simulated() {
    return true;
  }

  @Override
  public void open(Map<String, String> params) {
    String slot = params.get("slot");
    if (slot == null || slot.isBlank()) {
      throw new IllegalStateException("không có khe khoá (tham số slot)");
    }
    String deviceFile = params.get("device-file");
    if (deviceFile != null && !Files.exists(Path.of(deviceFile))) {
      throw new IllegalStateException("không thấy thiết bị " + deviceFile);
    }
    for (String label : params.getOrDefault("key-labels", "ky-van-ban").split(",")) {
      if (!label.isBlank()) {
        keys.put(label.trim(), newKeyPair());
      }
    }
    tsaKey = random(32);
    open = true;
  }

  @Override
  public Map<Role, String> algorithms() {
    return ALGORITHMS;
  }

  @Override
  public byte[] random(int length) {
    byte[] out = new byte[length];
    random.nextBytes(out);
    return out;
  }

  @Override
  public byte[] aeadEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
    return gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);
  }

  @Override
  public byte[] aeadDecrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextWithTag) {
    return gcm(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertextWithTag);
  }

  @Override
  public byte[] mac(byte[] key, byte[] data) {
    requireOpen();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("MAC giả lập lỗi", e);
    }
  }

  @Override
  public byte[] digest(byte[] data) {
    requireOpen();
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("Băm giả lập lỗi", e);
    }
  }

  @Override
  public Signature sign(String keyLabel, byte[] documentDigest) {
    requireOpen();
    KeyPair pair = keys.get(keyLabel);
    if (pair == null) {
      throw new IllegalArgumentException("không có khoá ký ở nhãn " + keyLabel);
    }
    Instant at = clock.instant();
    try {
      java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
      s.initSign(pair.getPrivate());
      s.update(documentDigest);
      byte[] value = s.sign();
      return new Signature(
          value, FORMAT, timestamp(value, at), certificate(keyLabel, pair, at), at);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("Ký giả lập lỗi", e);
    }
  }

  @Override
  public boolean verify(Signature signature, byte[] documentDigest) {
    requireOpen();
    KeyPair pair =
        keys.values().stream()
            .filter(p -> java.util.Arrays.equals(certDer(p), signature.certificate().der()))
            .findFirst()
            .orElse(null);
    if (pair == null
        || !java.util.Arrays.equals(
            timestamp(signature.value(), signature.signedAt()), signature.timestampToken())) {
      return false;
    }
    try {
      java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
      s.initVerify(pair.getPublic());
      s.update(documentDigest);
      return s.verify(signature.value());
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  @Override
  public void close() {
    open = false;
    keys.clear();
  }

  private byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
    requireOpen();
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad);
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("AEAD giả lập: thẻ hoặc AAD không khớp", e);
    }
  }

  private byte[] timestamp(byte[] signatureValue, Instant at) {
    byte[] data =
        ByteBuffer.allocate(signatureValue.length + Long.BYTES)
            .put(signatureValue)
            .putLong(at.toEpochMilli())
            .array();
    return mac(tsaKey, data);
  }

  private Certificate certificate(String label, KeyPair pair, Instant at) {
    return new Certificate(
        certDer(pair),
        "CN=Khoá ký giả lập " + label,
        "CN=Ban Cơ yếu giả lập",
        "GIA-LAP-" + Integer.toHexString(Objects.hash(label)),
        at.minus(Duration.ofDays(1)),
        at.plus(Duration.ofDays(365)));
  }

  private static byte[] certDer(KeyPair pair) {
    byte[] prefix = "GIA-LAP-BCY-CERT:".getBytes(StandardCharsets.US_ASCII);
    byte[] pub = pair.getPublic().getEncoded();
    return ByteBuffer.allocate(prefix.length + pub.length).put(prefix).put(pub).array();
  }

  private static KeyPair newKeyPair() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Không sinh được khoá giả lập", e);
    }
  }

  private void requireOpen() {
    if (!open) {
      throw new IllegalStateException("Phiên thư viện Ban Cơ yếu giả lập chưa mở");
    }
  }
}
