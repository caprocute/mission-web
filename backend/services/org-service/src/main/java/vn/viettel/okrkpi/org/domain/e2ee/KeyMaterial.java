package vn.viettel.okrkpi.org.domain.e2ee;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;

/**
 * Quy tắc khoá công khai của danh bạ khoá C3 (ADR-27, TL-03 {@code org.person_public_key}): kiểm SubjectPublicKeyInfo
 * đúng thuật toán, vân tay SHA-256 của SPKI và chuỗi chuẩn mà khoá danh bạ của tenant ký.
 *
 * <p>{@code BCY} là thuật toán của Ban Cơ yếu mà JDK không phân tích được, nên chỉ kiểm độ dài (suy luận, ghi ở wiki
 * trang 14).
 */
public final class KeyMaterial {

  public static final String VALIDATION = "COMMON-422-VALIDATION";
  public static final int MAX_SPKI_BYTES = 1536;
  /** Bậc của nhóm điểm P-256 (secp256r1), dùng để nhận đúng đường cong. */
  private static final BigInteger P256_ORDER =
      new BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);

  private KeyMaterial() {
  }

  /** Giải mã base64 và kiểm SPKI theo {@code keyAlg}; trả DER của SPKI. */
  public static byte[] spki(String keyAlg, String base64, String field) {
    byte[] der;
    try {
      der = Base64.getDecoder().decode(base64 == null ? "" : base64.trim());
    } catch (IllegalArgumentException e) {
      throw invalid(field, "Khoá công khai không phải base64.");
    }
    if (der.length == 0 || der.length > MAX_SPKI_BYTES) {
      throw invalid(field, "Khoá công khai rỗng hoặc quá dài.");
    }
    switch (keyAlg == null ? "" : keyAlg) {
      case "ECDH-P256" -> {
        if (!(parse("EC", der, field) instanceof ECPublicKey ec)
            || !P256_ORDER.equals(ec.getParams().getOrder())) {
          throw invalid(field, "Khoá công khai không phải đường cong P-256.");
        }
      }
      case "RSA-OAEP-3072" -> {
        if (!(parse("RSA", der, field) instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() != 3072) {
          throw invalid(field, "Khoá công khai không phải RSA 3072 bit.");
        }
      }
      case "BCY" -> {
        if (der.length < 32) {
          throw invalid(field, "Khoá công khai quá ngắn.");
        }
      }
      default -> throw invalid("keyAlg", "Thuật toán khoá không được hỗ trợ.");
    }
    return der;
  }

  public static byte[] fingerprint(byte[] spki) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(spki);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Vân tay dạng hex 64 ký tự thường; sai dạng trả 422 ở {@code field}. */
  public static byte[] hexFingerprint(String hex, String field) {
    if (hex == null || !hex.matches("^[0-9a-f]{64}$")) {
      throw invalid(field, "Vân tay khoá không hợp lệ.");
    }
    return HexFormat.of().parseHex(hex);
  }

  /**
   * Chuỗi chuẩn được ký: {@code v1|tenant|loại|id|chủ khoá|keyAlg|vân tay hex|validFrom|validTo}, thời điểm ISO 8601
   * UTC làm tròn tới micro giây (độ chính xác của {@code timestamptz}); {@code validTo} rỗng khi không giới hạn.
   */
  public static String canonical(UUID tenant, String kind, UUID id, UUID owner, String keyAlg, byte[] fingerprint,
      Instant validFrom, Instant validTo) {
    return String.join("|", "v1", tenant.toString(), kind, id.toString(), owner == null ? "" : owner.toString(),
        keyAlg, HexFormat.of().formatHex(fingerprint), micros(validFrom).toString(),
        validTo == null ? "" : micros(validTo).toString());
  }

  /**
   * Chuỗi chuẩn của một lần phục hồi: {@code v1|tenant|RECOVERY|id|khoá phục hồi|loại|đối tượng|người nhận|vân tay
   * hex|người giữ mảnh sắp tăng dần, phân cách bằng dấu phẩy|performedAt}.
   */
  public static String recoveryCanonical(UUID tenant, UUID id, UUID recoveryKeyId, String objectType, UUID objectId,
      UUID newRecipientId, byte[] newKeyFingerprint, java.util.Collection<UUID> custodians, Instant performedAt) {
    return String.join("|", "v1", tenant.toString(), "RECOVERY", id.toString(), recoveryKeyId.toString(), objectType,
        objectId.toString(), newRecipientId.toString(), HexFormat.of().formatHex(newKeyFingerprint),
        String.join(",", custodians.stream().map(UUID::toString).sorted().toList()),
        micros(performedAt).toString());
  }

  public static Instant micros(Instant at) {
    return at.truncatedTo(ChronoUnit.MICROS);
  }

  private static PublicKey parse(String alg, byte[] der, String field) {
    try {
      return KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(der));
    } catch (InvalidKeySpecException | ClassCastException e) {
      throw invalid(field, "Khoá công khai không đúng dạng SubjectPublicKeyInfo.");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static OrgRuleViolation invalid(String field, String detail) {
    return new OrgRuleViolation(VALIDATION, null, null, field, detail);
  }
}
