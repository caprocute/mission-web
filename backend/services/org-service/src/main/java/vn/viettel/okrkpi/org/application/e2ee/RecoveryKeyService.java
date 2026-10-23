package vn.viettel.okrkpi.org.application.e2ee;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.Cursors;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;
import vn.viettel.okrkpi.org.application.Page;
import vn.viettel.okrkpi.org.application.port.DirectorySigner;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.PersonDirectory;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.PublicKeyStore;
import vn.viettel.okrkpi.org.application.port.PublicKeyStore.StoredKey;
import vn.viettel.okrkpi.org.application.port.RecoveryKeyStore;
import vn.viettel.okrkpi.org.application.port.RecoveryKeyStore.StoredRecovery;
import vn.viettel.okrkpi.org.application.port.RecoveryKeyStore.StoredRecoveryKey;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.e2ee.KeyMaterial;

/**
 * CORE-07-06 phần D · ADR-27 · TL-01 "Khôi phục khi mất khoá", TL-04 SEC-25: khoá phục hồi của tổ chức và nhật ký
 * các lần phục hồi. Máy chủ chỉ giữ khoá công khai phục hồi và nhật ký có ký; khoá riêng chia Shamir 3 trên 5, mảnh
 * khoá giao trực tiếp cho người giữ theo biên bản, việc giải mã và bọc lại khoá nội dung làm ở máy trạm cách ly.
 *
 * <p>{@code id} của khoá phục hồi là mã người nhận đại diện trong bản bọc khoá nội dung ở mission-core; vân tay là
 * {@code deviceId}. Cấu hình tenant {@code e2ee.recovery-key} của mission-core phải trùng khoá đang hiệu lực ở đây
 * (đồng bộ là việc tiếp theo, ghi ở wiki trang 14).
 */
@Service
public class RecoveryKeyService {

  static final String KIND = "RECOVERY_KEY";
  static final String AUDIT_ROTATE = "THAY_KHOA_PHUC_HOI";
  static final String AUDIT_RECOVER = "GHI_PHUC_HOI_KHOA_MAT";
  static final int SHARES = 5;
  static final int THRESHOLD = 3;
  static final Set<String> OBJECT_TYPES = Set.of("TASK_CONTENT", "EVIDENCE_FILE");
  /** Lệch đồng hồ cho phép giữa máy trạm cách ly và máy chủ. */
  static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

  private final RecoveryKeyStore store;
  private final PublicKeyStore publicKeys;
  private final PersonDirectory persons;
  private final DirectorySigner signer;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;
  private final E2eeLedger ledger;
  private final Clock clock;

  public RecoveryKeyService(RecoveryKeyStore store, PublicKeyStore publicKeys, PersonDirectory persons,
      DirectorySigner signer, IdempotencyPort idempotency, PolicyPort policy, E2eeLedger ledger, Clock clock) {
    this.store = store;
    this.publicKeys = publicKeys;
    this.persons = persons;
    this.signer = signer;
    this.idempotency = idempotency;
    this.policy = policy;
    this.ledger = ledger;
    this.clock = clock;
  }

  public record KeyInput(String keyAlg, String publicKeySpki, List<UUID> custodianIds, String ceremonyRef) {
  }

  public record RecoveryInput(UUID recoveryKeyId, String objectType, UUID objectId, UUID newRecipientId,
      String newKeyFingerprint, List<UUID> custodianIds, String reason, String ceremonyRef, Instant performedAt) {
  }

  @Transactional(readOnly = true)
  public StoredRecoveryKey current(Actor actor) {
    policy.authorize(actor, "getRecoveryKey");
    return store.active(false)
        .orElseThrow(() -> new OrgNotFoundException("Tổ chức chưa khai báo khoá phục hồi."));
  }

  @Transactional
  public StoredRecoveryKey rotate(Actor actor, IdempotencyRequest idem, KeyInput in) {
    policy.authorize(actor, "rotateRecoveryKey");
    Optional<StoredRecoveryKey> replay = idempotency.begin(actor, idem, StoredRecoveryKey.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (!"ECDH-P256".equals(in.keyAlg()) && !"RSA-OAEP-3072".equals(in.keyAlg())) {
      throw KeyMaterial.invalid("keyAlg", "Khoá phục hồi chỉ dùng ECDH P-256 hoặc RSA-OAEP 3072.");
    }
    byte[] spki = KeyMaterial.spki(in.keyAlg(), in.publicKeySpki(), "publicKeySpki");
    byte[] fingerprint = KeyMaterial.fingerprint(spki);
    List<UUID> custodians = distinct(in.custodianIds(), "custodianIds");
    if (custodians.size() != SHARES) {
      throw KeyMaterial.invalid("custodianIds", "Cần đúng " + SHARES + " cán bộ khác nhau giữ mảnh khoá.");
    }
    requirePersons(custodians, "custodianIds");
    String ceremony = text(in.ceremonyRef(), 1, 200, "ceremonyRef", "Số biên bản từ 1 đến 200 ký tự.");
    if (store.fingerprintExists(fingerprint)) {
      throw new OrgConflictException("ORG-409-PUBLIC-KEY-EXISTS", "Khoá phục hồi này đã được khai báo.");
    }
    Instant now = KeyMaterial.micros(clock.instant());
    store.active(true).ifPresent(old -> store.retire(old.id(), actor.actorId(), now));
    UUID id = UuidV7.next(clock);
    DirectorySigner.Signed signed = signer.sign(actor.tenantId(), KeyMaterial.canonical(actor.tenantId(), KIND, id,
        null, in.keyAlg(), fingerprint, now, null).getBytes(StandardCharsets.UTF_8));
    StoredRecoveryKey key = new StoredRecoveryKey(id, in.keyAlg(), spki, fingerprint, THRESHOLD, custodians,
        ceremony, "HIEU_LUC", now, null, signed.keyVersion(), signed.signature(), now, 0);
    store.insert(key, actor.actorId());
    ledger.record(actor, AUDIT_ROTATE, "RECOVERY_KEY", id, "RecoveryKey", 0);
    idempotency.complete(actor, idem, 201, key, id.toString());
    return key;
  }

  @Transactional
  public StoredRecovery record(Actor actor, IdempotencyRequest idem, RecoveryInput in) {
    policy.authorize(actor, "recordE2eeRecovery");
    Optional<StoredRecovery> replay = idempotency.begin(actor, idem, StoredRecovery.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (in.recoveryKeyId() == null || in.objectId() == null || in.newRecipientId() == null
        || in.performedAt() == null) {
      throw KeyMaterial.invalid(in.recoveryKeyId() == null ? "recoveryKeyId" : in.objectId() == null ? "objectId"
          : in.newRecipientId() == null ? "newRecipientId" : "performedAt", "Thiếu giá trị bắt buộc.");
    }
    if (!OBJECT_TYPES.contains(in.objectType())) {
      throw KeyMaterial.invalid("objectType", "Loại đối tượng không hợp lệ.");
    }
    StoredRecoveryKey key = store.find(in.recoveryKeyId())
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy khoá phục hồi."));
    List<UUID> present = distinct(in.custodianIds(), "custodianIds");
    if (present.size() < key.threshold() || !key.custodianIds().containsAll(present)) {
      throw KeyMaterial.invalid("custodianIds", "Cần ít nhất " + key.threshold()
          + " người giữ mảnh của đúng khoá phục hồi này cùng có mặt.");
    }
    byte[] fingerprint = KeyMaterial.hexFingerprint(in.newKeyFingerprint(), "newKeyFingerprint");
    Instant now = KeyMaterial.micros(clock.instant());
    StoredKey target = publicKeys.byFingerprint(fingerprint)
        .filter(k -> k.personId().equals(in.newRecipientId()))
        .filter(k -> "HIEU_LUC".equals(PublicKeyDirectoryService.view(k, now).status()))
        .orElseThrow(() -> KeyMaterial.invalid("newKeyFingerprint",
            "Khoá thiết bị mới phải là khoá còn hiệu lực của người nhận trong danh bạ."));
    Instant performed = KeyMaterial.micros(in.performedAt());
    if (performed.isAfter(now.plus(CLOCK_SKEW)) || performed.isBefore(key.validFrom())) {
      throw KeyMaterial.invalid("performedAt", "Thời điểm phục hồi không hợp lệ.");
    }
    String reason = text(in.reason(), 10, 2000, "reason", "Lý do từ 10 đến 2000 ký tự.");
    String ceremony = text(in.ceremonyRef(), 1, 200, "ceremonyRef", "Số biên bản từ 1 đến 200 ký tự.");
    UUID id = UuidV7.next(clock);
    DirectorySigner.Signed signed = signer.sign(actor.tenantId(), KeyMaterial.recoveryCanonical(actor.tenantId(), id,
        key.id(), in.objectType(), in.objectId(), target.personId(), fingerprint, present, performed)
        .getBytes(StandardCharsets.UTF_8));
    StoredRecovery log = new StoredRecovery(id, key.id(), in.objectType(), in.objectId(), target.personId(),
        fingerprint, present, reason, ceremony, performed, signed.keyVersion(), signed.signature(), now,
        actor.actorId(), now);
    store.insertLog(log, actor.actorId());
    ledger.record(actor, AUDIT_RECOVER, in.objectType(), in.objectId(), "E2eeRecovery", 0);
    idempotency.complete(actor, idem, 201, log, id.toString());
    return log;
  }

  /** Một biên bản khôi phục cho mission-core đối chiếu trước khi nhận khoá bọc khôi phục. */
  @Transactional(readOnly = true)
  public StoredRecovery recovery(Actor actor, UUID id) {
    policy.authorize(actor, "getE2eeRecovery");
    return store.log(id).orElseThrow(() -> new OrgNotFoundException("Không có biên bản khôi phục này."));
  }

  @Transactional(readOnly = true)
  public Page<StoredRecovery> list(Actor actor, UUID objectId, int limit, String cursor) {
    policy.authorize(actor, "listE2eeRecoveries");
    int size = Math.max(1, Math.min(limit, 200));
    Instant afterAt = null;
    UUID afterId = null;
    if (cursor != null && !cursor.isBlank()) {
      List<String> keys = Cursors.decode(cursor, 2);
      try {
        afterAt = Instant.parse(keys.get(0));
        afterId = UUID.fromString(keys.get(1));
      } catch (RuntimeException e) {
        throw KeyMaterial.invalid("cursor", "Con trỏ trang không hợp lệ.");
      }
    }
    List<StoredRecovery> rows = store.logs(objectId, afterAt, afterId, size + 1);
    if (rows.size() <= size) {
      return new Page<>(rows, null);
    }
    StoredRecovery last = rows.get(size - 1);
    return new Page<>(rows.subList(0, size), Cursors.encode(last.recordedAt().toString(), last.id().toString()));
  }

  private void requirePersons(List<UUID> ids, String field) {
    if (!persons.currentNames(ids).keySet().containsAll(ids)) {
      throw KeyMaterial.invalid(field, "Có cán bộ không thuộc tổ chức.");
    }
  }

  private static List<UUID> distinct(List<UUID> ids, String field) {
    if (ids == null || ids.isEmpty() || ids.contains(null)) {
      throw KeyMaterial.invalid(field, "Thiếu danh sách cán bộ giữ mảnh khoá.");
    }
    Set<UUID> set = new LinkedHashSet<>(ids);
    if (set.size() != ids.size()) {
      throw KeyMaterial.invalid(field, "Danh sách cán bộ giữ mảnh khoá bị lặp.");
    }
    return List.copyOf(set);
  }

  private static String text(String value, int min, int max, String field, String message) {
    String v = value == null ? "" : value.trim();
    if (v.length() < min || v.length() > max) {
      throw KeyMaterial.invalid(field, message);
    }
    return v;
  }
}
