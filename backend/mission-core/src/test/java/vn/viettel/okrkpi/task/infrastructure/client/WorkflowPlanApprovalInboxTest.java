package vn.viettel.okrkpi.task.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowInboxClient;

/** Hộp việc duyệt kế hoạch dùng token người gọi; thiếu token hoặc client thì dự phòng. */
class WorkflowPlanApprovalInboxTest {

  @SuppressWarnings("unchecked")
  private final ObjectProvider<WorkflowInboxClient> provider = mock(ObjectProvider.class);

  private final WorkflowInboxClient client = mock(WorkflowInboxClient.class);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void docMaKeHoachVaBoMaSai() {
    UUID plan = UUID.randomUUID();
    when(provider.getIfAvailable()).thenReturn(client);
    when(client.businessIds("tok", "PLAN"))
        .thenReturn(Optional.of(List.of(plan.toString(), "khong-phai-uuid")));
    assertThat(new WorkflowPlanApprovalInbox(provider, () -> Optional.of("tok")).awaitingPlanIds())
        .contains(List.of(plan));
  }

  @Test
  void duPhongKhiThieuTokenHoacClient() {
    assertThat(new WorkflowPlanApprovalInbox(provider, () -> Optional.of("tok")).awaitingPlanIds())
        .isEmpty();
    when(provider.getIfAvailable()).thenReturn(client);
    assertThat(new WorkflowPlanApprovalInbox(provider).awaitingPlanIds()).isEmpty();
  }

  @Test
  void layTokenTuNguCanhBaoMat() {
    assertThat(WorkflowPlanApprovalInbox.currentBearer()).isEmpty();
    Jwt jwt =
        Jwt.withTokenValue("abc")
            .header("alg", "none")
            .subject("u")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    assertThat(WorkflowPlanApprovalInbox.currentBearer()).contains("abc");
  }
}
