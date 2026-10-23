package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService.IncidentView;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService.NewIncident;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.IncidentRow;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalIncident;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalIncidentCreate;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalIncidentPage;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

class AppraisalIncidentControllerTest {

  private final UUID tenant = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private final IncidentService service = mock(IncidentService.class);
  private OpaInput.Subject subject =
      OpaInput.Subject.user(
          actor.toString(),
          tenant.toString(),
          unit.toString(),
          List.of("to_chuc_can_bo"),
          null,
          null,
          null);

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  @SuppressWarnings("unchecked")
  private AppraisalIncidentController controller() {
    AuthzSubjectResolver resolver =
        new AuthzSubjectResolver() {
          @Override
          public OpaInput.Subject currentSubject() {
            return subject;
          }
        };
    ObjectProvider<AuthzSubjectResolver> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(resolver);
    return new AppraisalIncidentController(service, provider);
  }

  private <T> T inTenant(java.util.concurrent.Callable<T> call) throws Exception {
    return TenantContext.call(TenantId.of(tenant), call);
  }

  @Test
  void ghiSuViecTra201KemViTriVaEtag() throws Exception {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    UUID person = UUID.randomUUID();
    IncidentView view = view(person);
    when(service.newIncident(eq(TenantId.of(tenant)), eq(actor), any(NewIncident.class)))
        .thenReturn(view);
    AppraisalIncidentCreate body =
        new AppraisalIncidentCreate(person, "KY_LUAT", LocalDate.of(2026, 5, 4), "12/QĐ");
    body.setSummary("Tóm tắt");
    ResponseEntity<AppraisalIncident> res =
        inTenant(() -> controller().createAppraisalIncident("k-1", body));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(res.getHeaders().getLocation().getPath())
        .isEqualTo(AppraisalIncidentController.BASE + "/" + view.row().id());
    assertThat(res.getHeaders().getETag()).isNotBlank();
    assertThat(res.getBody().getSummary()).isEqualTo("Tóm tắt");
    assertThat(res.getBody().getCreatedBy().getId()).isEqualTo(actor);
  }

  @Test
  void thieuTruongBatBuocBi422() {
    AppraisalIncidentCreate noDoc =
        new AppraisalIncidentCreate(UUID.randomUUID(), "KY_LUAT", LocalDate.now(), " ");
    AppraisalIncidentCreate noDate =
        new AppraisalIncidentCreate(UUID.randomUUID(), "KY_LUAT", null, "1");
    AppraisalIncidentCreate noPerson =
        new AppraisalIncidentCreate(null, "KY_LUAT", LocalDate.now(), "1");
    for (AppraisalIncidentCreate body : List.of(noDoc, noDate, noPerson)) {
      assertThatThrownBy(() -> inTenant(() -> controller().createAppraisalIncident(null, body)))
          .isInstanceOf(BusinessException.class)
          .hasMessage("COMMON-422-VALIDATION");
    }
    verifyNoInteractions(service);
  }

  @Test
  void danhSachPhanTrangTheoConTroVaPhamViDonVi() throws Exception {
    List<IncidentView> rows = List.of(view(null), view(null), view(null));
    when(service.list(eq(TenantId.of(tenant)), eq(unit), isNull(), isNull(), isNull(), eq(3)))
        .thenReturn(rows);
    AppraisalIncidentPage page =
        inTenant(() -> controller().listAppraisalIncidents(null, null, 2, null));
    assertThat(page.getItems()).hasSize(2);
    assertThat(page.getNextCursor())
        .isEqualTo(SelfAssessmentController.encodeCursor(rows.get(1).row().id()));

    when(service.list(any(), any(), any(), any(), any(), anyInt())).thenReturn(rows.subList(0, 1));
    assertThat(
            inTenant(() -> controller().listAppraisalIncidents(null, null, null, null))
                .getNextCursor())
        .isNull();
    assertThatThrownBy(
            () -> inTenant(() -> controller().listAppraisalIncidents(null, null, 0, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION");
  }

  @Test
  void ngoaiPhamViHoacKhongDangNhap() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.find(TenantId.of(tenant), unit, id)).thenThrow(new NoSuchElementException());
    assertThatThrownBy(() -> inTenant(() -> controller().getAppraisalIncident(id)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

    IncidentView view = view(null);
    when(service.find(TenantId.of(tenant), unit, view.row().id())).thenReturn(view);
    assertThat(inTenant(() -> controller().getAppraisalIncident(view.row().id())).getBody().getId())
        .isEqualTo(view.row().id());

    subject = null;
    assertThatThrownBy(() -> inTenant(() -> controller().getAppraisalIncident(id)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void khongCoDonViPhanCongThiPhamViRong() throws Exception {
    subject =
        OpaInput.Subject.user(
            actor.toString(), tenant.toString(), "khong-phai-uuid", List.of(), null, null, null);
    when(service.list(any(), isNull(), any(), any(), any(), anyInt())).thenReturn(List.of());
    assertThat(
            inTenant(() -> controller().listAppraisalIncidents(null, null, null, null)).getItems())
        .isEmpty();
  }

  private IncidentView view(UUID person) {
    IncidentRow row =
        new IncidentRow(
            UUID.randomUUID(),
            person == null ? UUID.randomUUID() : person,
            null,
            unit,
            "KY_LUAT",
            LocalDate.of(2026, 5, 4),
            "12/QĐ",
            null,
            null,
            null,
            actor,
            Instant.parse("2026-05-05T00:00:00Z"),
            0);
    return new IncidentView(row, "Tóm tắt");
  }
}
