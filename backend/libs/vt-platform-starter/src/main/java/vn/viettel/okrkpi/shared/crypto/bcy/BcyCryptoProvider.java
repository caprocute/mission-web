package vn.viettel.okrkpi.shared.crypto.bcy;

import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.shared.crypto.CryptoException;
import vn.viettel.okrkpi.shared.crypto.CryptoProvider;

/**
 * {@link CryptoProvider} của chế độ {@code okrkpi.crypto.provider=bcy} (DV-10-05, ADR-06): mọi thao
 * tác đi qua thư viện Ban Cơ yếu, không gọi JCA. Định dạng phong bì C2 giữ nguyên, chỉ byte phiên
 * bản thuật toán là {@link #VERSION_BCY} để bản mã của hai chế độ không bị đọc nhầm lẫn nhau.
 *
 * <p>Ngoài giao diện {@link CryptoProvider}, lớp này cho esign-service ký và kiểm chữ ký bằng khoá
 * trong thiết bị ({@link #sign}, {@link #verify}).
 */
public final class BcyCryptoProvider implements CryptoProvider, AutoCloseable {

  /** Phiên bản thuật toán ghi ở byte đầu bản mã khi mã hoá bằng thư viện Ban Cơ yếu. */
  public static final byte VERSION_BCY = 0x02;

  private static final int MIN_MAC_KEY_BYTES = 16;
  private static final int MAC_LENGTH_BYTES = 32;

  private final BcyCryptoLibrary library;

  public BcyCryptoProvider(BcyCryptoLibrary library) {
    this.library = Objects.requireNonNull(library, "library");
  }

  /** Mô tả thư viện và thuật toán đang dùng, để ghi nhật ký khởi động. */
  public String describe() {
    return library.description() + " " + library.algorithms();
  }

  public Map<BcyCryptoLibrary.Role, String> algorithms() {
    return library.algorithms();
  }

  public boolean simulated() {
    return library.simulated();
  }

  @Override
  public byte algorithmVersion() {
    return VERSION_BCY;
  }

  @Override
  public byte[] generateKey() {
    return exact(library.random(KEY_LENGTH_BYTES), KEY_LENGTH_BYTES, "khoá");
  }

  @Override
  public byte[] generateNonce() {
    return exact(library.random(NONCE_LENGTH_BYTES), NONCE_LENGTH_BYTES, "nonce");
  }

  @Override
  public byte[] encrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
    validate(key, nonce, aad);
    Objects.requireNonNull(plaintext, "plaintext");
    try {
      byte[] out = library.aeadEncrypt(key, nonce, aad, plaintext);
      if (out == null || out.length != plaintext.length + TAG_LENGTH_BYTES) {
        throw new CryptoException("Thư viện Ban Cơ yếu trả bản mã sai độ dài");
      }
      return out;
    } catch (CryptoException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CryptoException("Lỗi mã hoá bằng thư viện Ban Cơ yếu", e);
    }
  }

  @Override
  public byte[] decrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextWithTag) {
    validate(key, nonce, aad);
    Objects.requireNonNull(ciphertextWithTag, "ciphertextWithTag");
    if (ciphertextWithTag.length < TAG_LENGTH_BYTES) {
      throw new CryptoException("Bản mã ngắn hơn độ dài thẻ xác thực (16 byte)");
    }
    try {
      return library.aeadDecrypt(key, nonce, aad, ciphertextWithTag);
    } catch (RuntimeException e) {
      throw new CryptoException("Xác thực thẻ hoặc AAD thất bại khi giải mã (Ban Cơ yếu)", e);
    }
  }

  /**
   * Mã xác thực 32 byte của thư viện Ban Cơ yếu. Tên phương thức theo giao diện chung; ở chế độ này
   * thuật toán là thuật toán MAC mà thư viện khai, không phải HMAC-SHA256.
   */
  @Override
  public byte[] hmacSha256(byte[] key, byte[] data) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(data, "data");
    if (key.length < MIN_MAC_KEY_BYTES) {
      throw new CryptoException("Khoá MAC phải có độ dài tối thiểu 16 byte");
    }
    try {
      return exact(library.mac(key, data), MAC_LENGTH_BYTES, "MAC");
    } catch (CryptoException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CryptoException("Lỗi tính MAC bằng thư viện Ban Cơ yếu", e);
    }
  }

  /** Băm tài liệu bằng thuật toán băm của Ban Cơ yếu. */
  public byte[] digest(byte[] data) {
    return library.digest(Objects.requireNonNull(data, "data"));
  }

  /** Ký băm tài liệu bằng khoá ở khe {@code keyLabel}, kèm dấu thời gian. */
  public BcyCryptoLibrary.Signature sign(String keyLabel, byte[] documentDigest) {
    Objects.requireNonNull(keyLabel, "keyLabel");
    Objects.requireNonNull(documentDigest, "documentDigest");
    try {
      return library.sign(keyLabel, documentDigest);
    } catch (RuntimeException e) {
      throw new CryptoException("Lỗi ký bằng thiết bị Ban Cơ yếu", e);
    }
  }

  public boolean verify(BcyCryptoLibrary.Signature signature, byte[] documentDigest) {
    Objects.requireNonNull(signature, "signature");
    Objects.requireNonNull(documentDigest, "documentDigest");
    try {
      return library.verify(signature, documentDigest);
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public void close() {
    library.close();
  }

  private static void validate(byte[] key, byte[] nonce, byte[] aad) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(aad, "aad");
    if (key.length != KEY_LENGTH_BYTES) {
      throw new CryptoException("Khoá phải dài đúng 32 byte");
    }
    if (nonce.length != NONCE_LENGTH_BYTES) {
      throw new CryptoException("Nonce phải dài đúng 12 byte");
    }
    if (aad.length == 0) {
      throw new CryptoException("AAD không được rỗng");
    }
  }

  private static byte[] exact(byte[] value, int length, String what) {
    if (value == null || value.length != length) {
      throw new CryptoException(
          "Thư viện Ban Cơ yếu trả " + what + " sai độ dài (cần " + length + " byte)");
    }
    return value;
  }
}
