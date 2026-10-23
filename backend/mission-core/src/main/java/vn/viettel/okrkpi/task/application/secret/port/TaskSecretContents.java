package vn.viettel.okrkpi.task.application.secret.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.secret.KeyWrap;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.Sealed;

/** {@code task.task_secret_content} và {@code task.e2ee_key_wrap} ({@code TASK_CONTENT}). */
public interface TaskSecretContents {

  /**
   * Thông tin không mật của nhiệm vụ dùng để kiểm quyền: người giao, người nghiệm thu, cá nhân đang
   * thực hiện (chủ trì, phối hợp).
   */
  record TaskFacts(
      UUID id,
      UUID periodId,
      int periodBucket,
      boolean restricted,
      UUID assignerId,
      UUID acceptorId,
      Set<UUID> assignees) {

    public TaskFacts {
      assignees = Set.copyOf(assignees);
    }

    /** Người được giao, người giao và người nghiệm thu của nhiệm vụ. */
    public boolean participant(UUID person) {
      return person.equals(assignerId) || person.equals(acceptorId) || assignees.contains(person);
    }
  }

  /** Bản mã đã lưu. */
  record Content(
      UUID id,
      UUID taskId,
      Sealed sealed,
      byte[] senderFingerprint,
      Instant updatedAt,
      long rowVersion) {}

  Optional<TaskFacts> task(TenantId tenant, UUID taskId);

  /** Bản mã của nhiệm vụ, khoá dòng khi {@code lock}. */
  Optional<Content> content(TenantId tenant, int periodBucket, UUID taskId, boolean lock);

  /**
   * Thêm bản mã đầu tiên.
   *
   * @return {@code false} khi đã có bản mã (lưu đồng thời)
   */
  boolean insert(
      TenantId tenant,
      TaskFacts task,
      UUID id,
      Sealed sealed,
      byte[] sender,
      UUID actor,
      Instant at);

  /**
   * Thay bản mã khi phiên bản dòng còn là {@code expected}.
   *
   * @return phiên bản mới, hoặc {@code -1} khi lệch phiên bản
   */
  long update(
      TenantId tenant,
      int periodBucket,
      UUID taskId,
      long expected,
      Sealed sealed,
      byte[] sender,
      UUID actor,
      Instant at);

  /** Đặt lại phiên bản dòng khi chỉ danh sách bọc đổi, để ETag của người đọc thay đổi. */
  long touch(TenantId tenant, int periodBucket, UUID taskId, UUID actor, Instant at);

  /** Mọi bản bọc của nhiệm vụ, kể cả đã thu hồi, sắp theo thời điểm tạo. */
  List<KeyWrap> wraps(TenantId tenant, UUID taskId);

  /**
   * Thêm hoặc thay bản bọc theo vân tay khoá (UQ {@code (tenant_id, object_id,
   * recipient_key_fingerprint)}); bản bọc cũ cùng khoá được bật lại nếu đã thu hồi.
   */
  void upsert(TenantId tenant, UUID taskId, List<KeyWrap> wraps, UUID actor, Instant at);

  /** Thu hồi các bản bọc còn hiệu lực có vân tay ngoài {@code keep}. */
  int revokeExcept(TenantId tenant, UUID taskId, Set<String> keep, UUID actor, Instant at);

  /** Thu hồi mọi bản bọc còn hiệu lực của một người nhận. */
  int revokePerson(TenantId tenant, UUID taskId, UUID personId, UUID actor, Instant at);
}
