package vn.viettel.okrkpi.shared.error.testapp;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;

/** Controller thử ném lần lượt các lỗi chuẩn (tiêu chí hoàn thành NEN-009 mục 6). */
@RestController
@RequestMapping("/probe")
class ProbeController {

  private final Validator validator;

  ProbeController(Validator validator) {
    this.validator = validator;
  }

  record Item(@NotBlank String name) {}

  record ProgressInput(
      @NotNull @Min(0) @Max(100) Integer percent,
      @Size(max = 10) String note,
      @NotBlank(message = "Phải nêu lý do điều chỉnh") String reason,
      List<@Valid Item> items) {}

  @GetMapping("/business")
  void business() {
    throw new BusinessException(
        "TASK-409-PERIOD-LOCKED",
        "Kỳ Quý IV/2026 của đơn vị đã khoá. Muốn sửa cần đề nghị mở lại kỳ.");
  }

  @GetMapping("/business-msg")
  void businessWithMsg() {
    throw new BusinessException("OKR-403-SELF-APPROVAL");
  }

  @GetMapping("/business-fields")
  void businessWithFields() {
    throw new BusinessException(
        "OKR-422-VALIDATION",
        null,
        List.of(ProblemFieldError.of("weights", "Tổng trọng số phải bằng 100%", "BR-02-05")),
        "MSG-02-05");
  }

  @PostMapping("/progress")
  ProgressInput progress(@Valid @RequestBody ProgressInput input) {
    return input;
  }

  @GetMapping("/items")
  List<String> items(@RequestParam @Max(200) int limit) {
    return List.of();
  }

  @GetMapping("/tasks/{id}")
  UUID getTask(@PathVariable UUID id) {
    return id;
  }

  @PutMapping("/tasks/{id}")
  ResponseEntity<Void> updateTask(
      @PathVariable UUID id, @RequestHeader("If-Match") String ifMatch) {
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/optimistic")
  void optimistic() {
    throw new OptimisticLockingFailureException(
        "Row was updated or deleted by another transaction: update task.task set row_version=? where id=?");
  }

  @GetMapping("/version-conflict")
  void versionConflict() {
    throw VersionConflictException.of("TASK", 6, 7);
  }

  @GetMapping("/precondition")
  void precondition() {
    throw new PreconditionRequiredException();
  }

  @GetMapping("/constraint")
  void constraint() {
    throw new jakarta.validation.ConstraintViolationException(
        validator.validate(new ProgressInput(150, "ghi chú quá dài vượt mức", " ", List.of())));
  }

  @GetMapping("/forbidden")
  void forbidden() {
    throw new AccessDeniedException("Access denied by policy okr.allow for org.springframework");
  }

  @GetMapping("/unauthenticated")
  void unauthenticated() {
    throw new BadCredentialsException("Bad credentials for user nguyenvana");
  }

  @GetMapping("/boom")
  void boom() {
    throw new IllegalStateException("SELECT * FROM appraisal.score WHERE tenant_id = 'x'");
  }
}
