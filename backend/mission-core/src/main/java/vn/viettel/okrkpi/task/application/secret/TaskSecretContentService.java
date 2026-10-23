package vn.viettel.okrkpi.task.application.secret;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.secret.port.RecoveryKeys;
import vn.viettel.okrkpi.task.application.secret.port.TaskSecretContents;
import vn.viettel.okrkpi.task.application.secret.port.TaskSecretContents.Content;
import vn.viettel.okrkpi.task.application.secret.port.TaskSecretContents.TaskFacts;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.secret.KeyWrap;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.RecoveryKey;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.Sealed;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.Violation;

/**
 * Nội dung nhiệm vụ mật C3 phía máy chủ (CORE-07-06, ADR-27): {@code getTaskSecretContent}, {@code
 * saveTaskSecretContent}, {@code shareTaskSecretContent}, {@code revokeTaskSecretShare}.
 *
 * <p>Máy chủ không bao giờ có khoá nội dung hay nội dung rõ: chỉ lưu bản mã, khoá đã bọc và vân tay
 * khoá công khai. Bản mã không đi vào outbox, nhật ký hay sự kiện kiểm toán; kiểm toán chỉ ghi mã
 * nhiệm vụ và mã người nhận.
 *
 * <p>Quyền: chỉ người đang giữ bản bọc còn hiệu lực đọc được bản mã (OPA {@code c3_not_recipient},
 * kiểm lại ở đây). Lưu lần đầu và người nhận mới phải là người giao, người nghiệm thu hoặc cá nhân
 * đang thực hiện nhiệm vụ; mỗi lần lưu hay chia sẻ, tập bọc còn hiệu lực phải giữ một bản cho khoá
 * phục hồi tổ chức. Thu hồi đặt {@code revoked_at}, không xoá dòng; người bị thu hồi không lấy được
 * bản mã nữa (không thu hồi được khoá đã tải về).
 */
public class TaskSecretContentService {

  static final String TX = "taskTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";
  public static final String TYPE = "task_secret_content";
  static final String AUDIT_READ = "XEM_NOI_DUNG_MAT";
  static final String AUDIT_SAVE = "LUU_NOI_DUNG_MAT";
  static final String AUDIT_SHARE = "CHIA_SE_KHOA_NOI_DUNG_MAT";
  static final String AUDIT_REVOKE = "THU_HOI_KHOA_NOI_DUNG_MAT";
  static final Set<String> REVOKER_ROLES = Set.of("manager", "leader");

  private final TaskSecretContents store;
  private final RecoveryKeys recovery;
  private final AuditLogger audit;
  private final Clock clock;

  public TaskSecretContentService(
      TaskSecretContents store, RecoveryKeys recovery, AuditLogger audit, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.recovery = Objects.requireNonNull(recovery, "recovery");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Bản mã kèm khoá bọc của chính người gọi. */
  public record View(Content content, KeyWrap wrap) {}

  /** Siêu dữ liệu sau khi lưu hoặc chia sẻ. */
  public record Meta(
      UUID taskId, byte[] sha256, int recipientCount, Instant updatedAt, long rowVersion) {}

  /** Một khoá đã bọc theo hợp đồng {@code TaskKeyWrap}. */
  public record WrapInput(
      UUID recipientPersonId,
      String deviceId,
      String algorithm,
      String ephemeralPublicKey,
      String wrappedKey) {}

  /** Thân {@code TaskSecretContentInput}. */
  public record SaveInput(
      String ciphertext, String nonce, String aadHash, String sha256, List<WrapInput> keyWraps) {}

  /**
   * Tài nguyên C3 hỏi OPA: {@code recipientIds} là người được đọc ở thời điểm hỏi (người giữ bản
   * bọc; lưu lần đầu là người tham gia nhiệm vụ).
   */
  public record SecretAuthz(AuthzResource resource) implements AuthzResourceProvider {

    @Override
    public AuthzResource toAuthzResource() {
      return resource;
    }
  }

  /** Tài nguyên kiểm OPA trước khi đọc, lưu hoặc chia sẻ. */
  @Transactional(transactionManager = TX, readOnly = true)
  public SecretAuthz authz(TenantId tenant, UUID taskId) {
    TaskFacts task = store.task(tenant, taskId).orElseThrow(NoSuchElementException::new);
    boolean exists = store.content(tenant, task.periodBucket(), taskId, false).isPresent();
    Set<UUID> allowed = new LinkedHashSet<>();
    if (exists) {
      allowed.addAll(holders(store.wraps(tenant, taskId), recovery.of(tenant).orElse(null)));
    } else {
      allowed.add(task.assignerId());
      if (task.acceptorId() != null) {
        allowed.add(task.acceptorId());
      }
      allowed.addAll(task.assignees());
    }
    return new SecretAuthz(
        AuthzResource.builder()
            .type(TYPE)
            .id(taskId.toString())
            .tenantId(tenant.value().toString())
            .classification("C3")
            .recipientIds(allowed.stream().map(UUID::toString).toList())
            .build());
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public View get(TenantId tenant, UUID taskId, TaskActor actor) {
    TaskFacts task = store.task(tenant, taskId).orElseThrow(NoSuchElementException::new);
    Content content =
        store
            .content(tenant, task.periodBucket(), taskId, false)
            .orElseThrow(NoSuchElementException::new);
    KeyWrap mine =
        store.wraps(tenant, taskId).stream()
            .filter(KeyWrap::active)
            .filter(w -> w.recipientId().equals(actor.personId()))
            .max(Comparator.comparing(KeyWrap::createdAt))
            .orElseThrow(() -> notRecipient());
    trail(tenant, actor, AUDIT_READ, taskId, AuditEventType.SENSITIVE_DATA_ACCESS, List.of());
    return new View(content, mine);
  }

  /**
   * Lưu bản mã và thay toàn bộ danh sách bọc: bản bọc có trong danh sách mới được thêm hoặc thay,
   * bản bọc còn hiệu lực vắng mặt bị thu hồi (khoá nội dung mới thì bản bọc cũ hết giá trị).
   *
   * @param expected phiên bản trong {@code If-Match}; bỏ qua khi lưu lần đầu
   */
  @Transactional(TX)
  public Meta save(TenantId tenant, UUID taskId, long expected, SaveInput in, TaskActor actor) {
    TaskFacts task = store.task(tenant, taskId).orElseThrow(NoSuchElementException::new);
    if (!task.restricted()) {
      throw new BusinessException(INVALID_STATE, "Nhiệm vụ không phải nhiệm vụ mật");
    }
    RecoveryKey key = recovery.of(tenant).orElse(null);
    Sealed sealed;
    List<KeyWrap> wraps;
    try {
      sealed = SecretContentRules.seal(in.ciphertext(), in.nonce(), in.aadHash(), in.sha256());
      wraps = parse(in.keyWraps(), actor);
      SecretContentRules.requireRecovery(wraps, key);
      SecretContentRules.requireRecoveryRecipient(wraps, key);
    } catch (Violation v) {
      throw invalid(v);
    }
    KeyWrap own =
        wraps.stream()
            .filter(w -> w.recipientId().equals(actor.personId()))
            .findFirst()
            .orElseThrow(
                () -> invalid(new Violation("keyWraps", "Cần khoá đã bọc cho chính người lưu")));
    requireParticipants(task, wraps, key);
    Instant now = clock.instant();
    Content current = store.content(tenant, task.periodBucket(), taskId, true).orElse(null);
    long version;
    if (current == null) {
      if (!task.participant(actor.personId())) {
        throw new BusinessException(FORBIDDEN, "Chỉ người tham gia nhiệm vụ mới lưu nội dung mật");
      }
      if (!store.insert(
          tenant, task, Ids.newUuidV7(), sealed, own.fingerprint(), actor.personId(), now)) {
        throw new VersionConflictException("TASK", expected, 0L);
      }
      version = 0;
    } else {
      if (!holders(store.wraps(tenant, taskId), key).contains(actor.personId())) {
        throw notRecipient();
      }
      if (current.rowVersion() != expected) {
        throw new VersionConflictException("TASK", expected, current.rowVersion());
      }
      version =
          store.update(
              tenant,
              task.periodBucket(),
              taskId,
              expected,
              sealed,
              own.fingerprint(),
              actor.personId(),
              now);
      if (version < 0) {
        throw new VersionConflictException("TASK", expected, current.rowVersion() + 1);
      }
    }
    store.upsert(tenant, taskId, wraps, actor.personId(), now);
    store.revokeExcept(
        tenant,
        taskId,
        wraps.stream().map(KeyWrap::deviceId).collect(Collectors.toSet()),
        actor.personId(),
        now);
    trail(tenant, actor, AUDIT_SAVE, taskId, AuditEventType.ADMIN_ACTION, recipients(wraps));
    return new Meta(taskId, sealed.sha256(), count(wraps), now, version);
  }

  /** Thêm bản bọc cho người mới được giao hoặc uỷ quyền; người chia sẻ phải đang giữ bản bọc. */
  @Transactional(TX)
  public Meta share(TenantId tenant, UUID taskId, List<WrapInput> input, TaskActor actor) {
    TaskFacts task = store.task(tenant, taskId).orElseThrow(NoSuchElementException::new);
    Content content =
        store
            .content(tenant, task.periodBucket(), taskId, true)
            .orElseThrow(NoSuchElementException::new);
    RecoveryKey key = recovery.of(tenant).orElse(null);
    List<KeyWrap> existing = store.wraps(tenant, taskId);
    if (!holders(existing, key).contains(actor.personId())) {
      throw notRecipient();
    }
    List<KeyWrap> wraps;
    try {
      wraps = parse(input, actor);
      if (key != null) {
        SecretContentRules.requireRecoveryRecipient(wraps, key);
      }
      SecretContentRules.requireRecovery(existing, key);
    } catch (Violation v) {
      throw invalid(v);
    }
    requireParticipants(task, wraps, key);
    Instant now = clock.instant();
    store.upsert(tenant, taskId, wraps, actor.personId(), now);
    long version = store.touch(tenant, task.periodBucket(), taskId, actor.personId(), now);
    trail(tenant, actor, AUDIT_SHARE, taskId, AuditEventType.PERMISSION_CHANGE, recipients(wraps));
    return new Meta(
        taskId, content.sealed().sha256(), count(store.wraps(tenant, taskId)), now, version);
  }

  /**
   * Thu hồi bản bọc của một người: người giao nhiệm vụ, hoặc lãnh đạo, quản lý đang giữ bản bọc.
   * Không thu hồi được khoá phục hồi tổ chức. Gọi lại khi đã thu hồi vẫn thành công.
   */
  @Transactional(TX)
  public void revoke(TenantId tenant, UUID taskId, UUID personId, TaskActor actor) {
    TaskFacts task = store.task(tenant, taskId).orElseThrow(NoSuchElementException::new);
    store
        .content(tenant, task.periodBucket(), taskId, true)
        .orElseThrow(NoSuchElementException::new);
    RecoveryKey key = recovery.of(tenant).orElse(null);
    List<KeyWrap> existing = store.wraps(tenant, taskId);
    boolean assigner = actor.personId().equals(task.assignerId());
    boolean holder =
        actor.roles().stream().anyMatch(REVOKER_ROLES::contains)
            && holders(existing, key).contains(actor.personId());
    if (!assigner && !holder) {
      throw new BusinessException(
          FORBIDDEN, "Chỉ người giao nhiệm vụ hoặc lãnh đạo đang được đọc mới thu hồi được");
    }
    if (key != null && personId.equals(key.recipientId())) {
      throw invalid(new Violation("personId", "Không thu hồi khoá phục hồi của tổ chức"));
    }
    Instant now = clock.instant();
    if (store.revokePerson(tenant, taskId, personId, actor.personId(), now) > 0) {
      store.touch(tenant, task.periodBucket(), taskId, actor.personId(), now);
      trail(
          tenant, actor, AUDIT_REVOKE, taskId, AuditEventType.PERMISSION_CHANGE, List.of(personId));
    }
  }

  private static List<KeyWrap> parse(List<WrapInput> input, TaskActor actor) {
    if (input == null) {
      throw new Violation("keyWraps", "Cần ít nhất một khoá đã bọc");
    }
    List<KeyWrap> out = new ArrayList<>();
    for (int i = 0; i < input.size(); i++) {
      WrapInput w = input.get(i);
      if (w == null) {
        throw new Violation("keyWraps[" + i + "]", "Thiếu khoá đã bọc");
      }
      out.add(
          SecretContentRules.wrap(
                  i,
                  w.recipientPersonId(),
                  w.deviceId(),
                  w.algorithm(),
                  w.ephemeralPublicKey(),
                  w.wrappedKey())
              .grantedBy(actor.personId()));
    }
    return SecretContentRules.distinct(out);
  }

  /** ABAC người nhận: chỉ người tham gia nhiệm vụ hoặc khoá phục hồi tổ chức. */
  private static void requireParticipants(TaskFacts task, List<KeyWrap> wraps, RecoveryKey key) {
    for (KeyWrap w : wraps) {
      if (key != null && key.covers(w)) {
        continue;
      }
      if (!task.participant(w.recipientId())) {
        throw new BusinessException(
            FORBIDDEN, "Người nhận không phải người tham gia nhiệm vụ mật nên không được cấp khoá");
      }
    }
  }

  private static Set<UUID> holders(List<KeyWrap> wraps, RecoveryKey key) {
    return wraps.stream()
        .filter(KeyWrap::active)
        .filter(w -> key == null || !key.covers(w))
        .map(KeyWrap::recipientId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static int count(List<KeyWrap> wraps) {
    return (int)
        wraps.stream().filter(KeyWrap::active).map(KeyWrap::recipientId).distinct().count();
  }

  private static List<UUID> recipients(List<KeyWrap> wraps) {
    return wraps.stream().map(KeyWrap::recipientId).distinct().toList();
  }

  private void trail(
      TenantId tenant,
      TaskActor actor,
      String action,
      UUID taskId,
      AuditEventType type,
      List<UUID> recipients) {
    AuditEvent.Builder b =
        AuditEvent.builder(type, ActorId.of(actor.personId()), action)
            .outcome(AuditOutcome.SUCCESS)
            .tenant(tenant)
            .object(TYPE, taskId.toString());
    if (type == AuditEventType.SENSITIVE_DATA_ACCESS) {
      b.classification(Classification.C3);
    }
    if (!recipients.isEmpty()) {
      b.attribute(
          "recipients", recipients.stream().map(UUID::toString).collect(Collectors.joining(",")));
    }
    audit.record(b.build());
  }

  private static BusinessException notRecipient() {
    return new BusinessException(FORBIDDEN, "Người dùng không được cấp khoá đọc nội dung mật này");
  }

  private static BusinessException invalid(Violation v) {
    return new BusinessException(
        VALIDATION,
        v.getMessage(),
        List.of(new ProblemFieldError(v.field(), v.getMessage(), null)));
  }
}
