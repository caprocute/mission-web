package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.BoardView;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.EntryView;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardSubmission;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectGroupBoardInput;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectGroupBoardInputEntriesInner;
import vn.viettel.okrkpi.contract.appraisals.model.ProspectGroupCode;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/** Ánh xạ hợp đồng của SCR-1002 (CORE-04-12): ETag, nhóm, gói quy định, kiểm quyền đơn vị. */
class ProspectGroupControllerTest {

  final TenantId tenant = TenantId.of(UUID.randomUUID());
  final UUID me = UUID.randomUUID();
  final UUID period = UUID.randomUUID();
  final UUID unit = UUID.randomUUID();
  final UUID person = UUID.randomUUID();
  final ProspectBoardService service = mock(ProspectBoardService.class);
  final ProspectBoardSubmission submission = mock(ProspectBoardSubmission.class);
  final AppraisalReviewGuard guard = mock(AppraisalReviewGuard.class);
  final PolicyQueryApi policies = mock(PolicyQueryApi.class);

  @SuppressWarnings("unchecked")
  ProspectGroupController controller(String userId) {
    OpaInput.Subject subject =
        new OpaInput.Subject(
            "user",
            userId,
            null,
            tenant.value().toString(),
            null,
            List.of("leader"),
            null,
            null,
            List.of());
    AuthzSubjectResolver resolver =
        new AuthzSubjectResolver() {
          @Override
          public OpaInput.Subject currentSubject() {
            return subject;
          }
        };
    ObjectProvider<AuthzSubjectResolver> subjects = mock(ObjectProvider.class);
    when(subjects.getIfAvailable()).thenReturn(resolver);
    ObjectProvider<AppraisalReviewGuard> guards = mock(ObjectProvider.class);
    when(guards.getIfAvailable()).thenReturn(guard);
    ObjectProvider<PolicyQueryApi> packs = mock(ObjectProvider.class);
    when(packs.getIfAvailable()).thenReturn(policies);
    return new ProspectGroupController(service, submission, subjects, guards, packs);
  }

  BoardView view(String status, long version) {
    return new BoardView(
        UUID.randomUUID(),
        period,
        unit,
        status,
        1,
        version,
        "Lý do",
        null,
        "DANG",
        List.of(
            new EntryView(
                UUID.randomUUID(),
                person,
                "Lê Văn C",
                "HTTNV",
                false,
                "NHOM_1",
                "NHOM_2",
                1,
                "Lý do điều chỉnh",
                "KH-1",
                Instant.parse("2030-01-20T03:00:00Z")),
            new EntryView(
                null,
                UUID.randomUUID(),
                null,
                null,
                true,
                "NHOM_3",
                null,
                null,
                null,
                null,
                null)));
  }

  @Test
  void xemLuuTrinhTraBangVaEtag() throws Exception {
    ProspectGroupController c = controller(me.toString());
    UUID pack = UUID.randomUUID();
    when(policies.resolve(tenant, PeriodId.of(period), "DANG", null, unit))
        .thenReturn(
            Optional.of(
                new PolicyQueryApi.ResolvedPack(
                    pack,
                    "KHOI_DANG",
                    UUID.randomUUID(),
                    "v2.0",
                    "h",
                    null,
                    UUID.randomUUID(),
                    false)));
    when(service.view(tenant, period, unit)).thenReturn(view("NHAP", 0));
    var got = TenantContext.call(tenant, () -> c.getProspectGroupBoard(period, unit));
    assertThat(got.getHeaders().getETag()).isEqualTo("\"0\"");
    assertThat(got.getBody().getPolicyPackId()).isEqualTo(pack);
    assertThat(got.getBody().getPolicyVersion()).isEqualTo("KHOI_DANG v2.0");
    assertThat(got.getBody().getRejectionReason()).isEqualTo("Lý do");
    assertThat(got.getBody().getEntries().get(0).getSuggestedGroup())
        .isEqualTo(ProspectGroupCode.NHOM_2);
    assertThat(got.getBody().getEntries().get(0).getDecidedAt()).isNotNull();
    assertThat(got.getBody().getEntries().get(1).getAnnualRatingCode()).isNull();
    verify(guard).viewProspects(any());

    ProspectGroupBoardInput body = new ProspectGroupBoardInput();
    ProspectGroupBoardInputEntriesInner row =
        new ProspectGroupBoardInputEntriesInner(person, ProspectGroupCode.NHOM_1, 1);
    row.setReason("Lý do điều chỉnh đủ hai mươi ký tự");
    body.setEntries(List.of(row));
    when(service.save(eq(tenant), eq(period), eq(unit), eq(0L), any(), eq(me)))
        .thenReturn(view("NHAP", 1));
    var saved =
        TenantContext.call(tenant, () -> c.saveProspectGroupBoard(period, unit, "\"0\"", body));
    assertThat(saved.getHeaders().getETag()).isEqualTo("\"1\"");
    verify(guard).arrangeProspects(any());
    when(service.save(eq(tenant), eq(period), eq(unit), eq(1L), eq(List.of()), eq(me)))
        .thenReturn(view("NHAP", 2));
    TenantContext.call(tenant, () -> c.saveProspectGroupBoard(period, unit, "\"1\"", null));

    when(submission.submit(tenant, period, unit, 2, me)).thenReturn(view("DA_TRINH", 3));
    var submitted =
        TenantContext.call(tenant, () -> c.submitProspectGroupBoard(period, unit, "\"2\""));
    assertThat(submitted.getBody().getStatus().getValue()).isEqualTo("DA_TRINH");
    verify(guard).submitProspects(any());
  }

  @Test
  void khongCoBangHoacKhongDangNhap() {
    when(service.view(tenant, period, unit)).thenThrow(new NoSuchElementException());
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant, () -> controller(me.toString()).getProspectGroupBoard(period, unit)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant,
                    () ->
                        controller("khong-phai-uuid")
                            .submitProspectGroupBoard(period, unit, "\"0\"")))
        .hasMessageContaining("401");
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant, () -> controller(null).submitProspectGroupBoard(period, unit, "\"0\"")))
        .hasMessageContaining("401");
  }
}
