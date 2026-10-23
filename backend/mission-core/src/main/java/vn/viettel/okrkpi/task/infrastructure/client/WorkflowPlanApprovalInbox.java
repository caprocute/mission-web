package vn.viettel.okrkpi.task.infrastructure.client;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowInboxClient;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalInbox;

/**
 * {@link PlanApprovalInbox} qua {@link WorkflowInboxClient} với token của người gọi hiện tại. Không
 * có token người dùng (kiểm thử, tác vụ nền) hoặc chưa cấu hình SVC-06 thì trả rỗng.
 */
public class WorkflowPlanApprovalInbox implements PlanApprovalInbox {

  /** Loại đối tượng của mẫu {@code duyet-ke-hoach-quy} (DV-06-03). */
  static final String BUSINESS_TYPE = "PLAN";

  private final ObjectProvider<WorkflowInboxClient> client;
  private final Supplier<Optional<String>> bearer;

  public WorkflowPlanApprovalInbox(ObjectProvider<WorkflowInboxClient> client) {
    this(client, WorkflowPlanApprovalInbox::currentBearer);
  }

  WorkflowPlanApprovalInbox(
      ObjectProvider<WorkflowInboxClient> client, Supplier<Optional<String>> bearer) {
    this.client = Objects.requireNonNull(client, "client");
    this.bearer = Objects.requireNonNull(bearer, "bearer");
  }

  @Override
  public Optional<List<UUID>> awaitingPlanIds() {
    WorkflowInboxClient c = client.getIfAvailable();
    Optional<String> token = bearer.get();
    if (c == null || token.isEmpty()) {
      return Optional.empty();
    }
    return c.businessIds(token.get(), BUSINESS_TYPE)
        .map(
            ids ->
                ids.stream()
                    .map(WorkflowPlanApprovalInbox::uuid)
                    .flatMap(Optional::stream)
                    .toList());
  }

  static Optional<String> currentBearer() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth instanceof JwtAuthenticationToken jwt
        ? Optional.of(jwt.getToken().getTokenValue())
        : Optional.empty();
  }

  private static Optional<UUID> uuid(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
