package vn.viettel.okrkpi.shared.crypto.bcy;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Giao diện bọc thư viện mật mã của Ban Cơ yếu Chính phủ cho bản cách ly (DV-10-05, ADR-06, TL-01
 * "Ranh giới pháp lý với bí mật nhà nước", TL-04 khoá {@code crypto.provider}).
 *
 * <p>Thư viện thật (hoặc lớp chuyển tiếp gọi thư viện gốc của Ban Cơ yếu) hiện thực giao diện này,
 * đóng thành jar riêng và được nạp lúc chạy theo cấu hình {@code okrkpi.crypto.bcy.library-path},
 * {@code library-class}, {@code params}; thay thư viện giả lập bằng thư viện thật chỉ đổi cấu hình,
 * không đổi mã. Khoá riêng ký nằm trong thiết bị, chỉ được tham chiếu qua nhãn khe khoá.
 *
 * <p>Ràng buộc định dạng do lớp {@code CryptoProvider} đặt ra: khoá đối xứng 32 byte, nonce 12
 * byte, bản mã AEAD có thẻ xác thực 16 byte nối ở cuối, MAC 32 byte (suy luận: thư viện thật phải
 * được bọc cho khớp các độ dài này; nếu không khớp, {@link BcyCryptoProvider} từ chối khởi động).
 */
public interface BcyCryptoLibrary extends AutoCloseable {

  /** Tên và phiên bản thư viện, ghi vào nhật ký khởi động. */
  String description();

  /** {@code true} nếu là thư viện giả lập; bị từ chối khi chạy hồ sơ production. */
  default boolean simulated() {
    return false;
  }

  /**
   * Mở phiên với thiết bị theo tham số triển khai (khe khoá, tệp mã PIN do Secret gắn…).
   *
   * @throws IllegalStateException khi không có thiết bị hoặc không đăng nhập được khe khoá
   */
  void open(Map<String, String> params);

  /** Mã các thuật toán mà phiên đang dùng, theo vai trò ({@link Role}). */
  Map<Role, String> algorithms();

  /** Sinh số ngẫu nhiên bằng bộ sinh của thiết bị. */
  byte[] random(int length);

  /** Mã hoá xác thực: trả về {@code bản mã || thẻ 16 byte}. */
  byte[] aeadEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext);

  /** Giải mã xác thực; ném {@link SecurityException} khi thẻ hoặc AAD không khớp. */
  byte[] aeadDecrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextWithTag);

  /** Mã xác thực thông điệp 32 byte (dẫn xuất khoá, chỉ mục mù). */
  byte[] mac(byte[] key, byte[] data);

  /** Băm theo thuật toán băm của Ban Cơ yếu. */
  byte[] digest(byte[] data);

  /**
   * Ký băm tài liệu bằng khoá ở khe {@code keyLabel}, kèm dấu thời gian theo quy định của Ban Cơ
   * yếu.
   */
  Signature sign(String keyLabel, byte[] documentDigest);

  /** Kiểm chữ ký và dấu thời gian đối với băm tài liệu. */
  boolean verify(Signature signature, byte[] documentDigest);

  @Override
  void close();

  /** Vai trò thuật toán trong danh sách được phép. */
  enum Role {
    AEAD,
    MAC,
    DIGEST,
    SIGNATURE,
    TIMESTAMP,
    RANDOM
  }

  /**
   * Chữ ký đã đóng gói theo định dạng của Ban Cơ yếu.
   *
   * @param value khối chữ ký (định dạng {@code format})
   * @param format mã định dạng do thư viện công bố, ví dụ {@code CADES_B_T}
   * @param timestampToken dấu thời gian của chữ ký
   * @param certificate chứng thư công khai của khoá ký
   * @param signedAt thời điểm ký theo dấu thời gian
   */
  record Signature(
      byte[] value,
      String format,
      byte[] timestampToken,
      Certificate certificate,
      Instant signedAt) {
    public Signature {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(timestampToken, "timestampToken");
      Objects.requireNonNull(certificate, "certificate");
      Objects.requireNonNull(signedAt, "signedAt");
      value = value.clone();
      timestampToken = timestampToken.clone();
    }

    @Override
    public byte[] value() {
      return value.clone();
    }

    @Override
    public byte[] timestampToken() {
      return timestampToken.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Signature s && java.util.Arrays.equals(value, s.value);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(value);
    }

    @Override
    public String toString() {
      return "Signature[" + format + ", " + signedAt + "]";
    }
  }

  /** Phần công khai của chứng thư khoá ký trong thiết bị. */
  record Certificate(
      byte[] der,
      String subjectDn,
      String issuerDn,
      String serialNo,
      Instant notBefore,
      Instant notAfter) {
    public Certificate {
      Objects.requireNonNull(der, "der");
      der = der.clone();
    }

    @Override
    public byte[] der() {
      return der.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Certificate c && java.util.Arrays.equals(der, c.der);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(der);
    }

    @Override
    public String toString() {
      return "Certificate[" + subjectDn + ", " + serialNo + "]";
    }
  }

  /** Tập vai trò mà {@link #algorithms()} phải khai đủ. */
  Set<Role> REQUIRED_ROLES = Set.of(Role.values());
}
