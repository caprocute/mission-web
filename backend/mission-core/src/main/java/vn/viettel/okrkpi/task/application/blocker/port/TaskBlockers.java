package vn.viettel.okrkpi.task.application.blocker.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code task.task_blocker} trên phân vùng kỳ của nhiệm vụ. */
public interface TaskBlockers {

  /** Một điểm nghẽn; {@code createdBy} là người báo. */
  record BlockerRow(
      UUID id,
      String code,
      UUID taskId,
      UUID periodId,
      int periodBucket,
      String content,
      String causeCategory,
      String causeClaimed,
      String impactLevel,
      UUID handlerId,
      LocalDate resolveBy,
      String status,
      String resolution,
      UUID escalatedTo,
      UUID createdBy,
      Instant createdAt,
      long rowVersion) {}

  /** Điểm nghẽn mới ở {@code MO}. */
  record NewBlocker(
      UUID id,
      String code,
      UUID taskId,
      UUID periodId,
      int periodBucket,
      String content,
      String causeCategory,
      String causeClaimed,
      String impactLevel,
      LocalDate resolveBy,
      UUID reporter) {}

  /** Giá trị sau khi xử lý (đã gộp với bản hiện tại). */
  record Change(
      UUID handlerId,
      LocalDate resolveBy,
      String status,
      String resolution,
      Instant resolvedAt,
      UUID escalatedTo,
      UUID actorId) {}

  /** Khoá dòng nhiệm vụ rồi trả số thứ tự điểm nghẽn kế tiếp của nhiệm vụ. */
  int nextSeq(TenantId tenant, int periodBucket, UUID taskId);

  void insert(TenantId tenant, NewBlocker blocker, Instant at);

  Optional<BlockerRow> find(TenantId tenant, int periodBucket, UUID taskId, UUID blockerId);

  /** Ghi thay đổi khi {@code row_version} khớp; {@code false} khi lệch. */
  boolean change(
      TenantId tenant,
      int periodBucket,
      UUID blockerId,
      long expectedVersion,
      Change c,
      Instant at);

  List<BlockerRow> listOf(TenantId tenant, int periodBucket, UUID taskId);
}
