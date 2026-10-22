package vn.viettel.okrkpi.task.infrastructure.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Người gọi của các API MOD-02: bean {@link TaskCaller} nếu có (kiểm thử), ngược lại chủ thể của
 * {@link AuthzSubjectResolver}; không xác định được thì 401.
 */
@Component
public class TaskCallers {

  static final String SERVICE_CLIENT = "service_client";

  private final ObjectProvider<TaskCaller> callers;
  private final ObjectProvider<AuthzSubjectResolver> subjects;

  public TaskCallers(
      ObjectProvider<TaskCaller> callers, ObjectProvider<AuthzSubjectResolver> subjects) {
    this.callers = Objects.requireNonNull(callers, "callers");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
  }

  public TaskActor current() {
    TaskCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.current().orElseThrow(TaskCallers::unauthorized);
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null) {
      throw unauthorized();
    }
    try {
      Set<String> roles =
          new LinkedHashSet<>(subject.roles() == null ? List.of() : subject.roles());
      return new TaskActor(UUID.fromString(subject.userId()), roles);
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  /**
   * Người gọi là tài khoản dịch vụ (token client credentials hoặc vai trò {@code service_client}),
   * dùng cho cổng nội bộ như {@code setTaskDelayRisk} (CORE-02-26).
   */
  public boolean serviceClient() {
    TaskCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.current().map(a -> a.roles().contains(SERVICE_CLIENT)).orElse(false);
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null) {
      throw unauthorized();
    }
    return "client".equals(subject.kind())
        || (subject.roles() != null && subject.roles().contains(SERVICE_CLIENT));
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
