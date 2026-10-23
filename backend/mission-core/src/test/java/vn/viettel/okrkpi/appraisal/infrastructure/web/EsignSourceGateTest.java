package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.appraisal.infrastructure.reconcile.ScoreReconcileGate;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/** R3 của DL-12: hồ sơ lệch điểm và ảnh chụp thì cổng tài liệu ký trả chưa sẵn sàng. */
class EsignSourceGateTest {

  private final ReviewService reviews = mock(ReviewService.class);
  private final BatchDecisionService decisions = mock(BatchDecisionService.class);
  private final ScoreReconcileGate gate = mock(ScoreReconcileGate.class);
  private final UUID profile = UUID.randomUUID();

  private EsignSourceController controller(boolean withGate) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("reviews", reviews);
    beans.addBean("decisions", decisions);
    if (withGate) {
      beans.addBean("gate", gate);
    }
    return new EsignSourceController(
        mock(SelfAssessmentService.class),
        beans.getBeanProvider(ReviewService.class),
        beans.getBeanProvider(BatchDecisionService.class),
        beans.getBeanProvider(ScoreReconcileGate.class));
  }

  @Test
  void hoSoLechThiKetLuanThamDinhChuaSanSangKy() throws Exception {
    when(reviews.signSource(any(), any()))
        .thenReturn(
            Optional.of(
                new ReviewService.SignSource(
                    true, UUID.randomUUID(), UUID.randomUUID(), new byte[32])));
    when(gate.anyBlocked(anyCollection())).thenReturn(true);
    var blocked = call(() -> controller(true).source(profile, "THAM_DINH").getBody());
    assertThat(blocked.ready()).isFalse();
    assertThat(blocked.documents()).isEmpty();
    var open = call(() -> controller(false).source(profile, "THAM_DINH").getBody());
    assertThat(open.ready()).isTrue();
    assertThat(open.documents()).hasSize(1);
  }

  @Test
  void motHoSoLechThiCaLoQuyetDinhChuaSanSangKy() throws Exception {
    when(decisions.signSource(any(), any()))
        .thenReturn(
            Optional.of(
                new BatchDecisionService.SignSource(
                    true,
                    UUID.randomUUID(),
                    List.of(new BatchDecisionService.SignDoc(profile, new byte[32])))));
    when(gate.anyBlocked(List.of(profile))).thenReturn(true);
    var body = call(() -> controller(true).source(UUID.randomUUID(), "QUYET_DINH").getBody());
    assertThat(body.ready()).isFalse();
    assertThat(body.documents()).isEmpty();
  }

  private static <T> T call(java.util.concurrent.Callable<T> work) throws Exception {
    return TenantContext.call(TenantId.of(UUID.randomUUID()), work);
  }
}
