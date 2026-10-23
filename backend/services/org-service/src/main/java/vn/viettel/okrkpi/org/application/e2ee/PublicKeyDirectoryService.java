package vn.viettel.okrkpi.org.application.e2ee;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.ForbiddenOperationException;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;
import vn.viettel.okrkpi.org.application.port.DirectorySigner;
import vn.viettel.okrkpi.org.application.port.DirectorySigner.VerificationKey;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.PublicKeyStore;
import vn.viettel.okrkpi.org.application.port.PublicKeyStore.StoredKey;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.VersionConflictException;
import vn.viettel.okrkpi.org.domain.e2ee.KeyMaterial;

/**
 * CORE-07-06 phần A · ADR-27 · TL-01 "Quản lý khoá người nhận": danh bạ khoá công khai có ký. Đăng ký khi đăng nhập
 * MFA lần đầu trên thiết bị ({@code registerMyPublicKey}, AAL2), tra cứu khoá của người nhận
 * ({@code listPersonPublicKeys}), thu hồi ({@code revokePublicKey}) và khoá kiểm chữ ký ({@code listE2eeDirectoryKeys}).
 *
 * <p>Mỗi bản ghi được ký bằng khoá danh bạ của tenant trên chuỗi {@link KeyMaterial#canonical} và ghi sổ cái trong
 * cùng giao dịch, nên máy chủ không thay được khoá của ai mà không để lại dấu vết. Hết hạn tính lúc đọc
 * ({@code HET_HAN} khi quá {@code valid_to}), không cần tác vụ nền.
 */
@Service
public class PublicKeyDirectoryService {

  static final String KIND = "PERSON_KEY";
  static final String OBJECT_TYPE = "PERSON_PUBLIC_KEY";
  static final String AUDIT_REGISTER = "DANG_KY_KHOA_CONG_KHAI";
  static final String AUDIT_REVOKE = "THU_HOI_KHOA_CONG_KHAI";
  /** Hiệu lực mặc định và tối đa của khoá (suy luận, TL chưa nêu; ghi ở wiki trang 14). */
  static final Duration MAX_VALIDITY = Duration.ofDays(3 * 365);
  /** Số khoá còn hiệu lực tối đa của một người (nhiều thiết bị, chống lạm dụng; suy luận). */
  static final int MAX_ACTIVE_KEYS = 10;

  private final PublicKeyStore keys;
  private final DirectorySigner signer;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;
  private final E2eeLedger ledger;
  private final Clock clock;

  public PublicKeyDirectoryService(PublicKeyStore keys, DirectorySigner signer, IdempotencyPort idempotency,
      PolicyPort policy, E2eeLedger ledger, Clock clock) {
    this.keys = keys;
    this.signer = signer;
    this.idempotency = idempotency;
    this.policy = policy;
    this.ledger = ledger;
    this.clock = clock;
  }

  /** Yêu cầu đăng ký: thuật toán, SPKI base64, nguồn ({@code THIET_BI}, {@code CHUNG_THU}), hết hạn tuỳ chọn. */
  public record Registration(String keyAlg, String publicKeySpki, String source, Instant validTo) {
  }

  /** Khoá kèm trạng thái tính tại thời điểm đọc. */
  public record KeyView(StoredKey key, String status) {
  }

  @Transactional
  public KeyView register(Actor actor, IdempotencyRequest idem, Registration in) {
    policy.authorize(actor, "registerMyPublicKey");
    Optional<KeyView> replay = idempotency.begin(actor, idem, KeyView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (!"THIET_BI".equals(in.source()) && !"CHUNG_THU".equals(in.source())) {
      throw KeyMaterial.invalid("source", "Nguồn khoá không hợp lệ.");
    }
    byte[] spki = KeyMaterial.spki(in.keyAlg(), in.publicKeySpki(), "publicKeySpki");
    byte[] fingerprint = KeyMaterial.fingerprint(spki);
    Instant now = KeyMaterial.micros(clock.instant());
    Instant validTo = in.validTo() == null ? now.plus(MAX_VALIDITY) : KeyMaterial.micros(in.validTo());
    if (!validTo.isAfter(now) || validTo.isAfter(now.plus(MAX_VALIDITY))) {
      throw KeyMaterial.invalid("validTo", "Hạn khoá phải sau hiện tại và không quá ba năm.");
    }
    if (keys.fingerprintExists(fingerprint)) {
      throw new OrgConflictException("ORG-409-PUBLIC-KEY-EXISTS", "Khoá này đã có trong danh bạ.");
    }
    if (keys.countActive(actor.actorId(), now) >= MAX_ACTIVE_KEYS) {
      throw KeyMaterial.invalid("publicKeySpki",
          "Mỗi người có tối đa " + MAX_ACTIVE_KEYS + " khoá còn hiệu lực; hãy thu hồi khoá của thiết bị không dùng.");
    }
    UUID id = UuidV7.next(clock);
    DirectorySigner.Signed signed = signer.sign(actor.tenantId(), KeyMaterial.canonical(actor.tenantId(), KIND, id,
        actor.actorId(), in.keyAlg(), fingerprint, now, validTo).getBytes(StandardCharsets.UTF_8));
    StoredKey key = new StoredKey(id, actor.actorId(), in.keyAlg(), spki, fingerprint, in.source(), "HIEU_LUC", now,
        validTo, signed.keyVersion(), signed.signature(), now, 0);
    keys.insert(key, actor.actorId());
    ledger.record(actor, AUDIT_REGISTER, OBJECT_TYPE, id, "PersonPublicKey", 0);
    KeyView view = new KeyView(key, "HIEU_LUC");
    idempotency.complete(actor, idem, 201, view, id.toString());
    return view;
  }

  @Transactional(readOnly = true)
  public List<KeyView> list(Actor actor, UUID personId, boolean includeInactive) {
    policy.authorize(actor, "listPersonPublicKeys");
    Instant now = clock.instant();
    return keys.byPerson(personId).stream().map(k -> view(k, now))
        .filter(v -> includeInactive || "HIEU_LUC".equals(v.status())).toList();
  }

  @Transactional
  public KeyView revoke(Actor actor, IdempotencyRequest idem, UUID id, int expectedVersion, String reason) {
    policy.authorize(actor, "revokePublicKey");
    Optional<KeyView> replay = idempotency.begin(actor, idem, KeyView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    StoredKey key = keys.find(id).orElseThrow(() -> new OrgNotFoundException("Không tìm thấy khoá."));
    if (!key.personId().equals(actor.actorId()) && !policy.hasTenantScope(actor)) {
      throw new ForbiddenOperationException("Chỉ chủ khoá hoặc quản trị tổ chức được thu hồi khoá.");
    }
    String why = reason == null ? "" : reason.trim();
    if (why.length() < 5 || why.length() > 1000) {
      throw KeyMaterial.invalid("reason", "Lý do thu hồi từ 5 đến 1000 ký tự.");
    }
    Instant now = KeyMaterial.micros(clock.instant());
    KeyView view;
    if ("THU_HOI".equals(key.status())) {
      view = view(key, now);
    } else {
      if (key.rowVersion() != expectedVersion
          || keys.revoke(id, expectedVersion, actor.actorId(), why, now) == 0) {
        throw new VersionConflictException(expectedVersion, key.rowVersion());
      }
      StoredKey revoked = keys.find(id).orElseThrow();
      ledger.record(actor, AUDIT_REVOKE, OBJECT_TYPE, id, "PersonPublicKey", revoked.rowVersion());
      view = view(revoked, now);
    }
    idempotency.complete(actor, idem, 200, view, id.toString());
    return view;
  }

  public List<VerificationKey> directoryKeys(Actor actor) {
    policy.authorize(actor, "listE2eeDirectoryKeys");
    return signer.verificationKeys(actor.tenantId());
  }

  static KeyView view(StoredKey k, Instant now) {
    boolean expired = "HIEU_LUC".equals(k.status()) && !k.validTo().isAfter(now);
    return new KeyView(k, expired ? "HET_HAN" : k.status());
  }
}
