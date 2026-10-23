package vn.viettel.okrkpi.task.infrastructure.web;

import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.TaskKeyWrap;
import vn.viettel.okrkpi.contract.tasks.model.TaskSecretContent;
import vn.viettel.okrkpi.contract.tasks.model.TaskSecretContentInput;
import vn.viettel.okrkpi.contract.tasks.model.TaskSecretContentMeta;
import vn.viettel.okrkpi.contract.tasks.model.TaskSecretRecoveryWrapRequest;
import vn.viettel.okrkpi.contract.tasks.model.TaskSecretShareRequest;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService.Meta;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService.SaveInput;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService.View;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService.WrapInput;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.secret.KeyWrap;

/**
 * Nội dung nhiệm vụ mật C3 của {@code tasks.yaml} (ADR-27, QT-05, QT-06, QT-16): đọc, lưu, chia sẻ
 * và thu hồi khoá đã bọc. Thân yêu cầu và phản hồi chỉ có bản mã base64, không có nội dung rõ.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskSecretContentController {

  private final TaskSecretContentService secrets;
  private final TaskSecretGuard guard;
  private final TaskCallers callers;

  public TaskSecretContentController(
      TaskSecretContentService secrets, TaskSecretGuard guard, TaskCallers callers) {
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @GetMapping("/{id}/secret-content")
  @RequiresPermission(operationId = "getTaskSecretContent", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskSecretContent> getTaskSecretContent(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    TaskActor actor = callers.current();
    View v =
        found(
            () -> {
              guard.read(secrets.authz(tenant, id));
              return secrets.get(tenant, id, actor);
            });
    TaskSecretContent dto = new TaskSecretContent();
    dto.setTaskId(id);
    dto.setCiphertext(b64(v.content().sealed().ciphertext()));
    dto.setNonce(b64(v.content().sealed().nonce()));
    dto.setAadHash(hex(v.content().sealed().aadHash()));
    dto.setSha256(hex(v.content().sealed().sha256()));
    dto.setKeyWrap(wrap(v.wrap()));
    dto.setUpdatedAt(v.content().updatedAt().atOffset(ZoneOffset.UTC));
    dto.setRowVersion((int) v.content().rowVersion());
    return ETags.ok(dto, v.content().rowVersion());
  }

  @PutMapping("/{id}/secret-content")
  @Idempotent
  @RequiresPermission(
      operationId = "saveTaskSecretContent",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<TaskSecretContentMeta> saveTaskSecretContent(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody TaskSecretContentInput body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    TaskActor actor = callers.current();
    SaveInput in =
        new SaveInput(
            body.getCiphertext(),
            body.getNonce(),
            body.getAadHash(),
            body.getSha256(),
            inputs(body.getKeyWraps()));
    Meta m =
        found(
            () -> {
              guard.save(secrets.authz(tenant, id));
              return secrets.save(tenant, id, expected, in, actor);
            });
    return ETags.ok(meta(m), m.rowVersion());
  }

  @PostMapping("/{id}/secret-content/shares")
  @Idempotent
  @RequiresPermission(
      operationId = "shareTaskSecretContent",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<TaskSecretContentMeta> shareTaskSecretContent(
      @PathVariable("id") UUID id, @RequestBody TaskSecretShareRequest body) {
    TenantId tenant = TenantContext.require();
    TaskActor actor = callers.current();
    List<WrapInput> wraps = inputs(body == null ? null : body.getKeyWraps());
    Meta m =
        found(
            () -> {
              guard.share(secrets.authz(tenant, id));
              return secrets.share(tenant, id, wraps, actor);
            });
    return ResponseEntity.ok(meta(m));
  }

  @PostMapping("/{id}/secret-content/recovery-wraps")
  @Idempotent
  @RequiresPermission(
      operationId = "restoreTaskSecretKeyWrap",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<TaskSecretContentMeta> restoreTaskSecretKeyWrap(
      @PathVariable("id") UUID id, @RequestBody TaskSecretRecoveryWrapRequest body) {
    TenantId tenant = TenantContext.require();
    TaskActor actor = callers.current();
    List<WrapInput> wrap =
        inputs(body == null || body.getKeyWrap() == null ? null : List.of(body.getKeyWrap()));
    Meta m =
        found(
            () ->
                secrets.restore(
                    tenant,
                    id,
                    body == null ? null : body.getRecoveryId(),
                    wrap == null ? null : wrap.get(0),
                    actor));
    return ResponseEntity.ok(meta(m));
  }

  @DeleteMapping("/{id}/secret-content/shares/{personId}")
  @Idempotent
  @RequiresPermission(
      operationId = "revokeTaskSecretShare",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<Void> revokeTaskSecretShare(
      @PathVariable("id") UUID id, @PathVariable("personId") UUID personId) {
    TenantId tenant = TenantContext.require();
    TaskActor actor = callers.current();
    found(
        () -> {
          secrets.revoke(tenant, id, personId, actor);
          return null;
        });
    return ResponseEntity.noContent().build();
  }

  private static List<WrapInput> inputs(List<TaskKeyWrap> wraps) {
    if (wraps == null) {
      return null;
    }
    return wraps.stream()
        .map(
            w ->
                w == null
                    ? null
                    : new WrapInput(
                        w.getRecipientPersonId(),
                        w.getDeviceId(),
                        w.getAlgorithm() == null ? null : w.getAlgorithm().getValue(),
                        w.getEphemeralPublicKey(),
                        w.getWrappedKey()))
        .toList();
  }

  private static TaskKeyWrap wrap(KeyWrap w) {
    TaskKeyWrap dto = new TaskKeyWrap();
    dto.setRecipientPersonId(w.recipientId());
    dto.setDeviceId(w.deviceId());
    dto.setAlgorithm(TaskKeyWrap.AlgorithmEnum.fromValue(w.algorithm()));
    if (w.ephemeralPublicKey() != null) {
      dto.setEphemeralPublicKey(b64(w.ephemeralPublicKey()));
    }
    dto.setWrappedKey(b64(w.wrappedKey()));
    dto.setCreatedAt(w.createdAt().atOffset(ZoneOffset.UTC));
    return dto;
  }

  private static TaskSecretContentMeta meta(Meta m) {
    TaskSecretContentMeta dto = new TaskSecretContentMeta();
    dto.setTaskId(m.taskId());
    dto.setSha256(hex(m.sha256()));
    dto.setRecipientCount(m.recipientCount());
    dto.setUpdatedAt(m.updatedAt().atOffset(ZoneOffset.UTC));
    dto.setRowVersion((int) m.rowVersion());
    return dto;
  }

  private static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  private static String hex(byte[] b) {
    return HexFormat.of().formatHex(b);
  }

  private static <T> T found(Supplier<T> call) {
    try {
      return call.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
