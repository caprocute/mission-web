package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualRatingService;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualRatingService.Explanation;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualReassignmentService;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.AnnualRow;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.QuarterRef;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader.Snapshot;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualRatingExplanation;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualRatingRow;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualReassignmentInput;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevel;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.OpaSqlFilter;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

class AnnualRatingControllerTest {

  @Test
  void dongNamVaGiaiThichTheoHopDong() {
    AnnualRow r =
        new AnnualRow(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "Phòng Tổng hợp",
            "DANG",
            "CHO_QUYET_DINH",
            Arrays.asList("HTTNV", "KHTNV", "HTNV", null),
            new BigDecimal("80.5"),
            "HTTNV",
            "HTNV",
            null,
            true,
            false,
            "CO_QUY_KHTNV",
            false,
            false,
            4L,
            UUID.randomUUID(),
            2029,
            null,
            null);
    AnnualRatingRow row = AnnualRatingController.row(r);
    assertThat(row.getQuarterRatings())
        .containsExactly(RatingLevel.HTTNV, RatingLevel.KHTNV, RatingLevel.HTNV, null);
    assertThat(row.getPerson().getName()).isEmpty();
    assertThat(row.getSystemLevel()).isEqualTo(RatingLevel.HTNV);
    assertThat(row.getRatingCode()).isNull();
    assertThat(row.getLinkageNote()).contains("15.5");
    assertThat(row.getRowVersion()).isEqualTo(4);

    QuarterRef q2 =
        new QuarterRef(
            2,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "DA_KY_SO",
            "KHTNV",
            UUID.randomUUID(),
            new byte[] {1},
            new BigDecimal("55"),
            "1.4",
            UUID.randomUUID());
    Snapshot s =
        new Snapshot(
            UUID.randomUUID(),
            r.profileId(),
            "DANG",
            "CHOT_KY",
            UUID.randomUUID(),
            null,
            "2.0",
            null,
            new byte[] {0x0a},
            new byte[] {0x0b},
            new BigDecimal("80.5"),
            null,
            null,
            null,
            null,
            new BigDecimal("0.4"),
            null,
            null,
            "HTNV",
            "[{\"ratingCode\":\"HTXSNV\",\"label\":\"Không có quý không hoàn thành\",\"met\":false}]",
            "[]",
            "{}",
            null,
            Instant.parse("2030-01-11T00:00:00Z"),
            null);
    AnnualRatingExplanation e =
        AnnualRatingController.explanation(
            new Explanation(r, List.of(q2), s, "HTNV", "(1 + 2) / 2 = 1,5", "Câu", "QĐ"));
    assertThat(e.getQuarters()).hasSize(1);
    assertThat(e.getQuarters().get(0).getPeriod().getLabel()).isEqualTo("Quý II/2029");
    assertThat(e.getQuarters().get(0).getPeriod().getEndDate()).hasToString("2029-06-30");
    assertThat(e.getConditions()).hasSize(1);
    assertThat(e.getInputHash()).isEqualTo("0a");
    assertThat(e.getPolicyVersion()).isEqualTo("2.0");
    assertThat(e.getFormula()).isEqualTo("(1 + 2) / 2 = 1,5");
    AnnualRatingExplanation bare =
        AnnualRatingController.explanation(
            new Explanation(r, List.of(), null, "HTNV", null, null, null));
    assertThat(bare.getConditions()).isEmpty();
    assertThat(bare.getText()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void deXuatBoTriLaiTraVeViecVaPhienBanMoi() throws Exception {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    UUID me = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID task = UUID.randomUUID();
    OpaInput.Subject subject =
        new OpaInput.Subject(
            "user",
            me.toString(),
            null,
            tenant.value().toString(),
            null,
            List.of("appraiser"),
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
    ObjectProvider<OpaSqlFilter> filters = mock(ObjectProvider.class);
    AppraisalReviewGuard guard = mock(AppraisalReviewGuard.class);
    ObjectProvider<AppraisalReviewGuard> guards = mock(ObjectProvider.class);
    when(guards.getIfAvailable()).thenReturn(guard);
    AnnualReassignmentService proposals = mock(AnnualReassignmentService.class);
    ObjectProvider<AnnualReassignmentService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(proposals);
    AnnualRatingRepository.ProposalTarget target =
        new AnnualRatingRepository.ProposalTarget(
            id,
            20290,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "DANG",
            true,
            false,
            2,
            null,
            null);
    when(proposals.propose(eq(tenant), eq(id), eq(2L), any(), any()))
        .thenAnswer(
            inv -> {
              ((java.util.function.Consumer<AnnualRatingRepository.ProposalTarget>)
                      inv.getArgument(4))
                  .accept(target);
              return new AnnualReassignmentService.Proposal(id, task, 3);
            });
    AnnualRatingController controller =
        new AnnualRatingController(
            mock(AnnualRatingService.class), subjects, filters, guards, provider);

    var response =
        TenantContext.call(
            tenant, () -> controller.createAnnualReassignmentProposal(id, "\"2\"", null));
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"3\"");
    assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/tasks/" + task);
    assertThat(response.getBody().getTaskId()).isEqualTo(task);
    assertThat(response.getBody().getRelocationProposed()).isTrue();
    verify(guard).reassign(any());

    AnnualReassignmentInput longNote = new AnnualReassignmentInput();
    longNote.setNote("x".repeat(1001));
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant,
                    () -> controller.createAnnualReassignmentProposal(id, "\"2\"", longNote)))
        .isInstanceOf(BusinessException.class);
    when(proposals.propose(eq(tenant), eq(id), eq(5L), any(), any()))
        .thenThrow(new java.util.NoSuchElementException());
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant, () -> controller.createAnnualReassignmentProposal(id, "\"5\"", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    AnnualRatingController without =
        new AnnualRatingController(mock(AnnualRatingService.class), subjects, filters, guards);
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant, () -> without.createAnnualReassignmentProposal(id, "\"2\"", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("501");
  }
}
