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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.Extensions;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.Responsibility;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.WorkItemPage;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.IncidentRow;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoringInputPage;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.api.TaskApi.WorkItemSnapshot;

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
    return new AppraisalScoringInputController(service, extensions, provider);
  }

  private ScoringExtensionService extensions;

  private void serviceClient() {
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
  }

  @Test
  void phanMoRongDienVaoTrangDauVao() throws Exception {
    serviceClient();
    extensions = mock(ScoringExtensionService.class);
    ScoringInput in = input();
    UUID after = UUID.randomUUID();
    WorkItemSnapshot w =
        new WorkItemSnapshot(
            UUID.randomUUID(),
            null,
            new BigDecimal("2.5"),
            3,
            Map.of("DO_KHO", "CAO"),
            "THUONG_XUYEN",
            "TRUC_1",
            null,
            "HOAN_THANH",
            null,
            0,
            0,
            true,
            false,
            false);
    Responsibility r =
        new Responsibility(
            "NHIEM_VU", UUID.randomUUID(), BigDecimal.TEN, null, BigDecimal.ONE, null, null, 0);
    IncidentRow incident =
        new IncidentRow(
            UUID.randomUUID(),
            in.personId(),
            period,
            in.orgUnitId(),
            "KY_LUAT",
            LocalDate.of(2026, 3, 1),
            "1/QĐ",
            null,
            "LOAI_TRU",
            null,
            null,
            null,
            0);
    when(service.page(any(), eq(period), any(), anyInt())).thenReturn(List.of(in));
    when(extensions.extend(any(), eq(period), any()))
        .thenReturn(
            Map.of(
                in.profileId(), new Extensions(List.of(w), after, List.of(r), List.of(incident))));
    AppraisalScoringInputPage page =
        TenantContext.call(
            TenantId.of(tenant),
            () -> controller().listAppraisalScoringInputs(period, null, null).getBody());
    var item = page.getItems().get(0);
    assertThat(item.getWorkItems()).hasSize(1);
    assertThat(item.getWorkItems().get(0).getExceeded()).isTrue();
    assertThat(item.getWorkItems().get(0).getQualityLevel()).isNull();
    assertThat(item.getWorkItemsNextCursor())
        .isEqualTo(SelfAssessmentController.encodeCursor(after));
    assertThat(item.getResponsibilityScope()).hasSize(1);
    assertThat(item.getIncidents()).hasSize(1);
    assertThat(item.getIncidents().get(0).getIncidentType()).isEqualTo("KY_LUAT");
  }

  @Test
  void trangViecLaTheoHoSo() throws Exception {
    serviceClient();
    extensions = mock(ScoringExtensionService.class);
    UUID profile = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(extensions.workItems(any(), eq(profile), any(), eq(200)))
        .thenReturn(new WorkItemPage(List.of(), next));
    assertThat(
            TenantContext.call(
                    TenantId.of(tenant),
                    () -> controller().listAppraisalWorkItems(profile, null, null).getBody())
                .getNextCursor())
        .isEqualTo(SelfAssessmentController.encodeCursor(next));
    UUID unknown = UUID.randomUUID();
    when(extensions.workItems(any(), eq(unknown), any(), anyInt()))
        .thenThrow(new NoSuchElementException());
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    TenantId.of(tenant),
                    () -> controller().listAppraisalWorkItems(unknown, 5, null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    TenantId.of(tenant),
                    () -> controller().listAppraisalWorkItems(profile, 201, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION");
    extensions = null;
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    TenantId.of(tenant),
                    () -> controller().listAppraisalWorkItems(profile, null, null)))
        .isInstanceOf(ResponseStatusException.class);
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
