package vn.viettel.okrkpi.product.application.e2ee;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.NewWrap;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.RecoveryKeys;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.RecoveryKeys.RecoveryKey;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.RecoveryRecords;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.StoredWrap;
import vn.viettel.okrkpi.product.application.evidence.EvidenceActor;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.VersionDetail;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;

/**
 * Khoá nội dung đã bọc của phiên bản minh chứng C3 (CORE-07-06, ADR-27): {@code
 * addEvidenceVersionKeyWraps} và khoá của chính người gọi trong {@code getEvidenceVersion}.
 *
 * <p>Hợp đồng {@code EvidenceKeyWrap} chỉ có {@code recipientId}, {@code wrappedKey}, {@code alg},
 * không có vân tay khoá thiết bị, nên mỗi người nhận một bản bọc cho mỗi phiên bản: cột {@code
 * recipient_key_fingerprint} ghi SHA-256 của {@code "EVIDENCE_FILE:" + recipientId} (suy luận, chờ
 * hợp đồng bổ sung {@code deviceId} như {@code TaskKeyWrap}); bản bọc cho khoá phục hồi ghi vân tay
 * thật của khoá phục hồi đầu tiên trong cấu hình. Thuật toán ECDH-ES gói khoá tạm trong {@code
 * wrappedKey}.
 *
 * <p>Quyền chia sẻ (TL-01 "Chia sẻ và thu hồi"): người gọi đang giữ bản bọc, hoặc chưa ai giữ và
 * người gọi là người tham gia nhiệm vụ (người nộp); người nhận phải là người giao, người nghiệm
 * thu, người chủ trì của nhiệm vụ, hoặc khoá phục hồi. Sau khi ghi, tập bọc còn hiệu lực phải có
 * bản cho khoá phục hồi tổ chức.
 */
public class EvidenceKeyWrapService {

  static final String TX = "productTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";
  static final String INVALID_STATE = "PRODUCT-409-INVALID-STATE";
  static final String AUDIT_SHARE = "CHIA_SE_KHOA_MINH_CHUNG_MAT";
  static final String OBJECT_TYPE = "evidence_version";
  static final Set<String> ALGORITHMS = Set.of("ECDH-ES+A256KW", "RSA-OAEP-256");
  static final int MAX_WRAPS = 200;
  static final int MAX_WRAPPED_KEY_BYTES = 1024;
  static final String AUDIT_RESTORE = "KHOI_PHUC_KHOA_MINH_CHUNG_MAT";
  static final String RECOVERY_OBJECT_TYPE = "EVIDENCE_FILE";

  /** Biên bản khôi phục chỉ dùng để nạp khoá bọc trong 24 giờ kể từ lúc ghi (suy luận). */
  static final Duration RECOVERY_WINDOW = Duration.ofHours(24);

  private final EvidenceSubmissionService versions;
  private final EvidenceKeyWraps wraps;
  private final RecoveryKeys recovery;
  private final RecoveryRecords recoveries;
  private final TaskApi tasks;
  private final AuditLogger audit;
  private final Clock clock;

  public EvidenceKeyWrapService(
      EvidenceSubmissionService versions,
      EvidenceKeyWraps wraps,
      RecoveryKeys recovery,
      TaskApi tasks,
      AuditLogger audit,
      Clock clock) {
    this(
        versions,
        wraps,
        recovery,
        (tenant, id) -> {
          throw new DependencyUnavailableException(
              "org-service", DependencyUnavailableException.Reason.IO_ERROR, null, null);
        },
        tasks,
        audit,
        clock);
  }

  public EvidenceKeyWrapService(
      EvidenceSubmissionService versions,
      EvidenceKeyWraps wraps,
      RecoveryKeys recovery,
      RecoveryRecords recoveries,
      TaskApi tasks,
      AuditLogger audit,
      Clock clock) {
    this.versions = Objects.requireNonNull(versions, "versions");
    this.wraps = Objects.requireNonNull(wraps, "wraps");
    this.recovery = Objects.requireNonNull(recovery, "recovery");
    this.recoveries = Objects.requireNonNull(recoveries, "recoveries");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Một bản bọc theo hợp đồng {@code EvidenceKeyWrap}. */
  public record WrapInput(UUID recipientId, String wrappedKey, String alg) {}

  /** Bản bọc của chính người gọi, rỗng khi phiên bản không phải C3 hoặc người gọi không giữ. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<StoredWrap> mine(TenantId tenant, VersionView v, UUID personId) {
    if (!v.hasE2ee()) {
      return Optional.empty();
    }
    return wraps.activeFor(tenant, v.id(), personId);
  }

  @Transactional(TX)
  public VersionDetail add(
      TenantId tenant, UUID evidenceId, int versionNo, List<WrapInput> input, EvidenceActor actor) {
    VersionDetail d = versions.getVersion(tenant, evidenceId, versionNo);
    VersionView v = d.version();
    if (!v.hasE2ee()) {
      throw new BusinessException(INVALID_STATE, "Phiên bản không có tệp mã hoá đầu cuối");
    }
    TaskSummary task = tasks.find(tenant, v.taskId()).orElseThrow(NoSuchElementException::new);
    RecoveryKey key = recovery.of(tenant).orElse(null);
    if (key == null || key.fingerprints().isEmpty()) {
      throw invalid("keyWraps", "Tổ chức chưa khai báo khoá phục hồi cho nội dung mật");
    }
    List<StoredWrap> existing = wraps.of(tenant, v.id());
    Set<UUID> holders =
        existing.stream()
            .filter(StoredWrap::active)
            .filter(w -> !isRecovery(w, key))
            .map(StoredWrap::recipientId)
            .collect(Collectors.toSet());
    UUID me = actor.personId();
    if (!holders.contains(me) && !(holders.isEmpty() && participant(task, me))) {
      throw new BusinessException(
          FORBIDDEN, "Người dùng không giữ khoá của minh chứng mật nên không chia sẻ được");
    }
    List<NewWrap> next = parse(input, key, task);
    boolean recoveryCovered =
        existing.stream().anyMatch(w -> w.active() && isRecovery(w, key))
            || next.stream().anyMatch(w -> w.recipientId().equals(key.recipientId()));
    if (!recoveryCovered) {
      throw invalid("keyWraps", "Thiếu khoá đã bọc cho khoá phục hồi của tổ chức");
    }
    Instant now = clock.instant();
    wraps.upsert(tenant, v.id(), next, me, now);
    audit.record(
        AuditEvent.builder(AuditEventType.PERMISSION_CHANGE, ActorId.of(me), AUDIT_SHARE)
            .outcome(AuditOutcome.SUCCESS)
            .tenant(tenant)
            .object(OBJECT_TYPE, v.id().toString())
            .attribute(
                "recipients",
                next.stream()
                    .map(w -> w.recipientId().toString())
                    .distinct()
                    .collect(Collectors.joining(",")))
            .build());
    return d;
  }

  /**
   * Nạp khoá bọc cho khoá mới của người nhận sau buổi khôi phục (TL-01 "Khôi phục khi mất khoá"):
   * biên bản SVC-04 phải đúng phiên bản, người nhận và ghi trong {@link #RECOVERY_WINDOW}; người
   * nhận phải đang có hoặc từng có bản bọc trên phiên bản. Bản bọc mới thay bản cũ của người đó.
   */
  @Transactional(TX)
  public VersionDetail restore(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      UUID recoveryId,
      WrapInput input,
      EvidenceActor actor) {
    VersionDetail d = versions.getVersion(tenant, evidenceId, versionNo);
    VersionView v = d.version();
    if (!v.hasE2ee()) {
      throw new BusinessException(INVALID_STATE, "Phiên bản không có tệp mã hoá đầu cuối");
    }
    TaskSummary task = tasks.find(tenant, v.taskId()).orElseThrow(NoSuchElementException::new);
    RecoveryKey key = recovery.of(tenant).orElse(null);
    if (key == null || key.fingerprints().isEmpty()) {
      throw invalid("keyWrap", "Tổ chức chưa khai báo khoá phục hồi cho nội dung mật");
    }
    if (recoveryId == null) {
      throw invalid("recoveryId", "Thiếu biên bản khôi phục");
    }
    if (input == null) {
      throw invalid("keyWrap", "Thiếu khoá đã bọc");
    }
    if (key.recipientId().equals(input.recipientId())) {
      throw invalid("keyWrap.recipientId", "Không nạp khoá phục hồi của tổ chức qua khôi phục");
    }
    List<NewWrap> next = parse(List.of(input), key, task);
    UUID recipient = next.get(0).recipientId();
    RecoveryRecords.Recovery r =
        recoveries
            .find(tenant, recoveryId)
            .orElseThrow(() -> invalid("recoveryId", "Không có biên bản khôi phục này"));
    Instant now = clock.instant();
    if (!RECOVERY_OBJECT_TYPE.equals(r.objectType())
        || !v.id().equals(r.objectId())
        || !recipient.equals(r.newRecipientId())) {
      throw invalid(
          "recoveryId", "Biên bản khôi phục không khớp phiên bản minh chứng hoặc người nhận");
    }
    if (r.recordedAt().isBefore(now.minus(RECOVERY_WINDOW))) {
      throw invalid("recoveryId", "Biên bản khôi phục đã quá 24 giờ");
    }
    if (wraps.of(tenant, v.id()).stream().noneMatch(w -> w.recipientId().equals(recipient))) {
      throw new BusinessException(
          FORBIDDEN, "Người nhận chưa từng được cấp khoá của minh chứng mật này");
    }
    wraps.upsert(tenant, v.id(), next, actor.personId(), now);
    audit.record(
        AuditEvent.builder(
                AuditEventType.PERMISSION_CHANGE, ActorId.of(actor.personId()), AUDIT_RESTORE)
            .outcome(AuditOutcome.SUCCESS)
            .tenant(tenant)
            .object(OBJECT_TYPE, v.id().toString())
            .attribute("recipients", recipient.toString())
            .attribute("recoveryId", recoveryId.toString())
            .build());
    return d;
  }

  /**
   * Cổng nội bộ cho SVC-08 trước khi cấp URL tải bản mã C3 (CORE-07-06): người bị thu hồi không còn
   * bản bọc hiệu lực nên không lấy được bản mã nữa.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public EvidenceKeyWraps.FileAccess fileAccess(TenantId tenant, UUID objectId, UUID personId) {
    return wraps.fileAccess(tenant, objectId, personId);
  }

  /** Bản bọc cho khoá phục hồi nhận theo vân tay, kể cả khoá đã thay (giữ trong cấu hình). */
  private static boolean isRecovery(StoredWrap w, RecoveryKey key) {
    return key.recipientId().equals(w.recipientId())
        || key.fingerprints().contains(HexFormat.of().formatHex(w.fingerprint()));
  }

  private static List<NewWrap> parse(List<WrapInput> input, RecoveryKey key, TaskSummary task) {
    if (input == null || input.isEmpty()) {
      throw invalid("keyWraps", "Cần ít nhất một khoá đã bọc");
    }
    if (input.size() > MAX_WRAPS) {
      throw invalid("keyWraps", "Tối đa " + MAX_WRAPS + " khoá đã bọc mỗi lần");
    }
    Set<UUID> seen = new HashSet<>();
    List<NewWrap> out = new ArrayList<>();
    for (int i = 0; i < input.size(); i++) {
      String at = "keyWraps[" + i + "].";
      WrapInput w = input.get(i);
      if (w == null || w.recipientId() == null) {
        throw invalid(at + "recipientId", "Thiếu người nhận");
      }
      if (!seen.add(w.recipientId())) {
        throw invalid(at + "recipientId", "Người nhận bị lặp");
      }
      if (w.alg() == null || !ALGORITHMS.contains(w.alg())) {
        throw invalid(at + "alg", "Thuật toán bọc khoá không được hỗ trợ");
      }
      byte[] wrapped;
      try {
        wrapped = Base64.getDecoder().decode(w.wrappedKey() == null ? "" : w.wrappedKey());
      } catch (IllegalArgumentException e) {
        throw invalid(at + "wrappedKey", "Giá trị không phải base64");
      }
      if (wrapped.length == 0 || wrapped.length > MAX_WRAPPED_KEY_BYTES) {
        throw invalid(at + "wrappedKey", "Khoá đã bọc rỗng hoặc quá dài");
      }
      boolean isRecovery = w.recipientId().equals(key.recipientId());
      if (!isRecovery && !participant(task, w.recipientId())) {
        throw new BusinessException(
            FORBIDDEN, "Người nhận không phải người tham gia nhiệm vụ nên không được cấp khoá");
      }
      byte[] fingerprint =
          isRecovery
              ? HexFormat.of().parseHex(key.fingerprints().get(0))
              : personFingerprint(w.recipientId());
      out.add(new NewWrap(w.recipientId(), fingerprint, w.alg(), wrapped));
    }
    return out;
  }

  private static boolean participant(TaskSummary task, UUID person) {
    return person.equals(task.assignerId())
        || person.equals(task.acceptorId())
        || person.equals(task.leadAssigneeId());
  }

  static byte[] personFingerprint(UUID personId) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(("EVIDENCE_FILE:" + personId).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }
}
