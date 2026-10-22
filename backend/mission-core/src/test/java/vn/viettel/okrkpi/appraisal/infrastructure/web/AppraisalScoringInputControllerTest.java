package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoringInputPage;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

class AppraisalScoringInputControllerTest {

  private final UUID tenant = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private final ScoringInputService service = mock(ScoringInputService.class);
  private OpaInput.Subject subject;

  @SuppressWarnings("unchecked")
  private AppraisalScoringInputController controller() {
    AuthzSubjectResolver resolver =
        new AuthzSubjectResolver() {
          @Override
          public OpaInput.Subject currentSubject() {
            return subject;
          }
        };
    ObjectProvider<AuthzSubjectResolver> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(resolver);
    return new AppraisalScoringInputController(service, provider);
  }

  @Test
  void nguoiDungThuongBi403() {
    subject =
        OpaInput.Subject.user(
            UUID.randomUUID().toString(),
            tenant.toString(),
            null,
            List.of("can_bo"),
            null,
            null,
            null);
    assertThatThrownBy(() -> controller().listAppraisalScoringInputs(period, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    subject = null;
    assertThatThrownBy(() -> controller().listAppraisalScoringInputs(period, null, null))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(service);
  }

  @Test
  void taiKhoanDichVuDocTrangKhongCoTruongC2() throws Exception {
    subject =
        new OpaInput.Subject(
            "client",
            null,
            "scoring-service",
            tenant.toString(),
            null,
            List.of(),
            null,
            null,
            null);
    List<ScoringInput> rows = List.of(input(), input(), input());
    when(service.page(any(), eq(period), any(), anyInt())).thenReturn(rows);
    AppraisalScoringInputPage page =
        TenantContext.call(
            TenantId.of(tenant),
            () -> controller().listAppraisalScoringInputs(period, 2, null).getBody());
    assertThat(page.getItems()).hasSize(2);
    assertThat(page.getNextCursor())
        .isEqualTo(SelfAssessmentController.encodeCursor(rows.get(1).profileId()));
    assertThat(page.getItems().get(0).getGeneralCriteria()).hasSize(1);
    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(page);
    assertThat(json).doesNotContain("comment", "reason", "content", "Enc");
    assertThat(page.getItems().get(0).getWorkItems()).isNull();

    when(service.page(any(), eq(period), any(), anyInt())).thenReturn(List.of(input()));
    assertThat(
            TenantContext.call(
                    TenantId.of(tenant),
                    () -> controller().listAppraisalScoringInputs(period, null, null).getBody())
                .getNextCursor())
        .isNull();
  }

  @Test
  void gioiHanNgoaiKhoangBi422() {
    subject =
        new OpaInput.Subject(
            "user",
            UUID.randomUUID().toString(),
            null,
            tenant.toString(),
            null,
            List.of("service_client"),
            null,
            null,
            null);
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    TenantId.of(tenant),
                    () -> controller().listAppraisalScoringInputs(period, 201, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION");
  }

  private static ScoringInput input() {
    return new ScoringInput(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "DANG",
        UUID.randomUUID(),
        null,
        false,
        true,
        UUID.randomUUID(),
        List.of(new SelfAssessmentForm.Criterion("TCC-01", true)));
  }
}
