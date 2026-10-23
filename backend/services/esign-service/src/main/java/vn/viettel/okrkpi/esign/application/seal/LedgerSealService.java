package vn.viettel.okrkpi.esign.application.seal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Ký niêm phong sổ cái cho ledger-service ({@code signLedgerSeal}, TL-01 "Niêm phong Merkle, dấu
 * thời gian và bản neo"): gốc Merkle 32 byte được ký ECDSA P-256 (SHA-256 trên 32 byte gốc) bằng
 * khoá niêm phong của cell trong HSM, và được đóng dấu thời gian RFC 3161 với message imprint chính
 * là gốc (kiểm bằng {@code openssl ts -verify -digest <gốc>}). Không qua xác nhận của người dùng.
 *
 * <p>Chỉ nhận lời gọi của tài khoản dịch vụ sổ cái: AuthorizationPolicy của mesh chặn ở tầng mạng
 * (task hạ tầng), dịch vụ kiểm lại mã client của token ({@code azp}).
 */
public class LedgerSealService {

  static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";
  static final String VALIDATION = "COMMON-422-VALIDATION";

  private final SealSigner signer;
  private final TimestampAuthority tsa;
  private final Set<String> allowedClients;
  private final Clock clock;

  public LedgerSealService(
      SealSigner signer, TimestampAuthority tsa, Set<String> allowedClients, Clock clock) {
    this.signer = Objects.requireNonNull(signer, "signer");
    this.tsa = Objects.requireNonNull(tsa, "tsa");
    this.allowedClients = Set.copyOf(allowedClients);
    this.clock = clock;
  }

  /**
   * Lệnh ký niêm phong.
   *
   * @param callerClientId mã client của token gọi ({@code azp}), có thể {@code null}
   */
  public record Command(
      String callerClientId,
      LocalDate sealDate,
      String merkleRoot,
      String prevMerkleRoot,
      long eventCount) {}

  /**
   * Kết quả.
   *
   * @param signature chữ ký DER
   * @param timestampToken token TSA DER
   */
  public record Result(
      LocalDate sealDate,
      String merkleRoot,
      byte[] signature,
      byte[] timestampToken,
      String certificateSerial,
      byte[] certificateSha256,
      Instant signedAt) {}

  public Result seal(Command c) {
    if (c.callerClientId() == null || !allowedClients.contains(c.callerClientId())) {
      throw new BusinessException(OUT_OF_SCOPE, "Chỉ dịch vụ sổ cái được yêu cầu ký niêm phong.");
    }
    byte[] root = root(c.merkleRoot());
    SealSigner.Sealed sealed = signer.sign(Hashes.sha256(root));
    byte[] token = tsa.timestampDigest(root);
    return new Result(
        c.sealDate(),
        c.merkleRoot(),
        sealed.signature(),
        token,
        sealed.certificateSerial(),
        sealed.certificateSha256(),
        clock.instant());
  }

  private static byte[] root(String hex) {
    if (hex == null || !hex.matches("[0-9a-f]{64}")) {
      throw new BusinessException(VALIDATION, "Gốc Merkle phải là 64 ký tự hex thường.");
    }
    return HexFormat.of().parseHex(hex);
  }
}
