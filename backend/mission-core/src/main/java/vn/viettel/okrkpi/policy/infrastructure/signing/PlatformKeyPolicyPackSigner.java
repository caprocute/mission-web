package vn.viettel.okrkpi.policy.infrastructure.signing;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.policy.application.port.PolicyCaseSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ký phê duyệt phát hành tạm bằng khoá nền tảng của ca kiểm thử vàng (ES256) trên {@code
 * content_hash}, trong khi SVC-10 chưa trả chữ ký CAdES cho module nguồn (hợp đồng {@code
 * getEsignRequest} không có trường chữ ký; suy luận, wiki trang 14). {@code signer_cert_ref} là
 * UUID tên theo mã khoá; không có dấu thời gian RFC 3161.
 */
public class PlatformKeyPolicyPackSigner implements PolicyPackSigner {

  private final PolicyCaseSigner key;
  private final Clock clock;

  public PlatformKeyPolicyPackSigner(PolicyCaseSigner key, Clock clock) {
    this.key = Objects.requireNonNull(key, "key");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public SignedHash sign(
      TenantId tenant, UUID approver, UUID versionId, byte[] contentHash, UUID esignSessionId) {
    Objects.requireNonNull(contentHash, "contentHash");
    byte[] signature;
    try {
      signature = key.sign(contentHash);
    } catch (RuntimeException e) {
      throw new SigningFailedException("Không ký được bằng khoá nền tảng", e);
    }
    UUID certificate =
        UUID.nameUUIDFromBytes(("policy-publish:" + key.keyId()).getBytes(StandardCharsets.UTF_8));
    return new SignedHash(signature, certificate, clock.instant(), null);
  }
}
