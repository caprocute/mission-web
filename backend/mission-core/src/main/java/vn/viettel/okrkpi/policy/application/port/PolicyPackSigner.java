package vn.viettel.okrkpi.policy.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ký số phê duyệt phát hành trên {@code content_hash} (CORE-05-09, BR-15-09, TL-01 "Phát hành, ký
 * số và vòng đời": CAdES tách rời trên SHA-256 của gói). Hiện thực ký qua SVC-10 cần phiên ký
 * {@code esignSessionId} đã mở; lỗi ký ném {@link SigningFailedException}.
 */
public interface PolicyPackSigner {

  /** Chữ ký và siêu dữ liệu lưu vào {@code policy_pack_version}. */
  record SignedHash(byte[] signature, UUID certificateRef, Instant signedAt, byte[] tsaToken) {
    public SignedHash {
      Objects.requireNonNull(signature, "signature");
      Objects.requireNonNull(signedAt, "signedAt");
      signature = signature.clone();
      tsaToken = tsaToken == null ? null : tsaToken.clone();
    }

    @Override
    public byte[] signature() {
      return signature.clone();
    }

    @Override
    public byte[] tsaToken() {
      return tsaToken == null ? null : tsaToken.clone();
    }
  }

  /** Ký không thành (nhà cung cấp lỗi, phiên ký không hợp lệ hoặc chưa xác nhận). */
  final class SigningFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SigningFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  SignedHash sign(
      TenantId tenant, UUID approver, UUID versionId, byte[] contentHash, UUID esignSessionId);
}
