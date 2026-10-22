package vn.viettel.okrkpi.appraisal.application.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Command;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Prepared;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Hai giao dịch của lệnh ký lô và lỗi SVC-10 (CORE-04-07). */
class BatchDecisionSubmissionTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private final BatchDecisionService service = mock(BatchDecisionService.class);
  private final SignRequests signs = mock(SignRequests.class);
  private final BatchDecisionSubmission submission = new BatchDecisionSubmission(service, signs);
  private final UUID actor = UUID.randomUUID();
  private final UUID batch = UUID.randomUUID();
  private final Command cmd =
      new Command(UUID.randomUUID(), "DANG", "1-QĐ", LocalDate.now(), false, List.of());

  @Test
  void moYeuCauKyBangKhoaTheoMaLo() {
    Prepared prepared = new Prepared(batch, 20264, null);
    when(service.prepare(TENANT, actor, cmd, Scope.NONE)).thenReturn(prepared);
    UUID sign = UUID.randomUUID();
    when(signs.requestDecision(TENANT, batch, "MYSIGN", "batch-decision:" + batch, "Bearer x"))
        .thenReturn(sign);
    assertThat(submission.decide(TENANT, actor, cmd, Scope.NONE, "MYSIGN", "Bearer x"))
        .isEqualTo(batch);
    verify(service).attachSignRequest(TENANT, prepared, sign, actor);
  }

  @Test
  void tiepTucLoDaMoYeuCauKy() {
    when(service.prepare(TENANT, actor, cmd, Scope.NONE))
        .thenReturn(new Prepared(batch, 20264, UUID.randomUUID()));
    assertThat(submission.decide(TENANT, actor, cmd, Scope.NONE, null, null)).isEqualTo(batch);
    verify(signs, never()).requestDecision(any(), any(), any(), any(), any());
  }

  @Test
  void loiDichVuKyGiuLoNhap() {
    when(service.prepare(TENANT, actor, cmd, Scope.NONE)).thenReturn(new Prepared(batch, 1, null));
    when(signs.requestDecision(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("down"));
    assertThatThrownBy(() -> submission.decide(TENANT, actor, cmd, Scope.NONE, null, null))
        .hasMessage("ESIGN-422-PROVIDER-UNAVAILABLE");
    BusinessException own = new BusinessException("COMMON-422-VALIDATION", "x");
    doThrow(own).when(signs).requestDecision(any(), any(), any(), any(), any());
    assertThatThrownBy(() -> submission.decide(TENANT, actor, cmd, Scope.NONE, null, null))
        .isSameAs(own);
    IllegalArgumentException cause = new IllegalArgumentException("401");
    doThrow(new SignRequests.Relayed(cause))
        .when(signs)
        .requestDecision(any(), any(), any(), any(), any());
    assertThatThrownBy(() -> submission.decide(TENANT, actor, cmd, Scope.NONE, null, null))
        .isSameAs(cause);
    verify(service, never()).attachSignRequest(any(), any(), any(), any());
  }
}
