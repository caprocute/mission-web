package vn.viettel.okrkpi.appraisal.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.AppraisalDraft;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.Prepared;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lệnh ký kết luận thẩm định qua SVC-10 (CORE-04-05). */
class ReviewSubmissionTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private final UUID id = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private final AppraisalDraft draft = new AppraisalDraft(RatingLevel.HTTNV, "Tốt", null, null);
  private final ReviewService service = mock(ReviewService.class);
  private final SignRequests signs = mock(SignRequests.class);
  private final ReviewSubmission submission = new ReviewSubmission(service, signs);

  @Test
  void moYeuCauKyRoiGanVaoKetLuan() {
    Prepared p = new Prepared(id, UUID.randomUUID(), 20271, null);
    UUID sign = UUID.randomUUID();
    when(service.prepareAppraisal(TENANT, id, 3L, actor, draft)).thenReturn(p);
    when(signs.requestAppraisal(TENANT, id, "MYSIGN", "k", "Bearer x")).thenReturn(sign);
    assertThat(submission.appraise(TENANT, id, 3L, actor, draft, "MYSIGN", "k", "Bearer x"))
        .isEqualTo(sign);
    verify(service).attachSignRequest(TENANT, p, sign);

    Prepared again = new Prepared(id, p.proposalId(), 20271, sign);
    when(service.prepareAppraisal(TENANT, id, 4L, actor, draft)).thenReturn(again);
    assertThat(submission.appraise(TENANT, id, 4L, actor, draft, null, "k", null)).isEqualTo(sign);
  }

  @Test
  void loiSvc10() {
    when(service.prepareAppraisal(TENANT, id, 3L, actor, draft))
        .thenReturn(new Prepared(id, UUID.randomUUID(), 20271, null));
    when(signs.requestAppraisal(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("down"));
    assertThatThrownBy(() -> submission.appraise(TENANT, id, 3L, actor, draft, null, "k", null))
        .hasMessage("ESIGN-422-PROVIDER-UNAVAILABLE");
    ResponseStatusException unauthorized = new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    doThrow(new SignRequests.Relayed(unauthorized))
        .when(signs)
        .requestAppraisal(any(), any(), any(), any(), any());
    assertThatThrownBy(() -> submission.appraise(TENANT, id, 3L, actor, draft, null, "k", null))
        .isSameAs(unauthorized);
    doThrow(new BusinessException("COMMON-403-OUT-OF-SCOPE"))
        .when(signs)
        .requestAppraisal(any(), any(), any(), any(), any());
    assertThatThrownBy(() -> submission.appraise(TENANT, id, 3L, actor, draft, null, "k", null))
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    verify(service, never()).attachSignRequest(any(), any(), any());
    SignRequests none = (t, p, provider, key, auth) -> UUID.randomUUID();
    assertThatThrownBy(() -> none.requestAppraisal(TENANT, id, null, "k", null))
        .isInstanceOf(IllegalStateException.class);
  }
}
