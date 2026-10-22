package vn.viettel.okrkpi.iam.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.util.JsonSerialization;

/**
 * DV-02-08 · Mã khôi phục lưu thành credential loại {@value #TYPE} của người dùng trong Keycloak: {@code secretData}
 * giữ muối và băm, {@code credentialData} giữ mã định danh, hạn dùng, người cấp, hình thức xác minh và băm khoá
 * Idempotency-Key. Mỗi người tối đa một mã còn hiệu lực: cấp mã mới thì thu hồi mã cũ.
 */
final class RecoveryCodeStore {

  static final String TYPE = "mission-recovery-code";

  record Issued(UUID codeId, String code, Instant issuedAt, Instant expiresAt) {
  }

  record Meta(String codeId, long expiresAt, String issuedBy, String verificationMethod, String idempotency) {
  }

  private RecoveryCodeStore() {
  }

  static Issued issue(UserModel user, UUID issuedBy, String verificationMethod, int ttlMinutes, String idempotency,
      Instant now) {
    revokeAll(user);
    String code = RecoveryCodes.generate();
    UUID codeId = EventMapper.uuidV7(now);
    Instant expires = now.plusSeconds(ttlMinutes * 60L);
    RecoveryCodes.Hashed h = RecoveryCodes.hash(code);
    CredentialModel c = new CredentialModel();
    c.setType(TYPE);
    c.setUserLabel("Mã khôi phục");
    c.setCreatedDate(now.toEpochMilli());
    c.setSecretData(json(Map.of("salt", h.salt(), "hash", h.hash(), "iterations", h.iterations())));
    c.setCredentialData(json(new Meta(codeId.toString(), expires.toEpochMilli(), issuedBy.toString(),
        verificationMethod, idempotency)));
    user.credentialManager().createStoredCredential(c);
    return new Issued(codeId, code, now, expires);
  }

  /** Thu hồi mọi mã của người dùng (kể cả mã đã hết hạn); trả số mã còn hiệu lực đã thu hồi. */
  static int revokeAll(UserModel user) {
    List<CredentialModel> all = user.credentialManager().getStoredCredentialsByTypeStream(TYPE).toList();
    long now = System.currentTimeMillis();
    int active = 0;
    for (CredentialModel c : all) {
      if (meta(c).expiresAt() > now) {
        active++;
      }
      user.credentialManager().removeStoredCredentialById(c.getId());
    }
    return active;
  }

  static Optional<CredentialModel> active(UserModel user, Instant now) {
    return user.credentialManager().getStoredCredentialsByTypeStream(TYPE)
        .filter(c -> meta(c).expiresAt() > now.toEpochMilli()).findFirst();
  }

  static Optional<CredentialModel> byIdempotency(UserModel user, String idempotency) {
    return user.credentialManager().getStoredCredentialsByTypeStream(TYPE)
        .filter(c -> idempotency.equals(meta(c).idempotency())).findFirst();
  }

  static boolean matches(CredentialModel c, String input) {
    try {
      RecoveryCodes.Hashed h = JsonSerialization.readValue(c.getSecretData(), RecoveryCodes.Hashed.class);
      return RecoveryCodes.matches(input, h);
    } catch (IOException e) {
      return false;
    }
  }

  static Meta meta(CredentialModel c) {
    try {
      return JsonSerialization.readValue(c.getCredentialData(), Meta.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String json(Object o) {
    try {
      return JsonSerialization.writeValueAsString(o);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
