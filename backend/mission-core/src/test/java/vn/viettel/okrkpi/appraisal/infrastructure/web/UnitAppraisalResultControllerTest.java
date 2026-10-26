package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.unitresult.UnitResultService;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository.UnitResultRow;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfileStatus;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevel;
import vn.viettel.okrkpi.contract.appraisals.model.UnitAppraisalResultPage;
import vn.viettel.okrkpi.contract.appraisals.model.UnitAppraisalResultRow;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.OpaSqlFilter;
import vn.viettel.okrkpi.shared.authz.SqlPredicate;
import vn.viettel.okrkpi.shared.authz.UntranslatablePolicyException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

class UnitAppraisalResultControllerTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID unit = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();

  private OpaInput.Subject subject(String unitId) {
    return new OpaInput.Subject(
        "user",
        UUID.randomUUID().toString(),
        null,
        tenant.value().toString(),
        unitId,
        List.of("leader"),
        null,
        null,
        List.of());
  }

  private static AuthzSubjectResolver resolver(OpaInput.Subject subject) {
    return new AuthzSubjectResolver() {
      @Override
      public OpaInput.Subject currentSubject() {
        return subject;
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(value);
    return p;
  }

  private static UnitResultRow row(UUID id, UUID snapshot, String auto, String fin) {
    return new UnitResultRow(
        id,
        UUID.randomUUID(),
        snapshot == null ? null : "Trần Minh Hoàng",
        UUID.randomUUID(),
        null,
        "DANG",
        snapshot == null ? "TU_DANH_GIA" : "DA_KY_SO",
        snapshot,
        snapshot == null ? null : new BigDecimal("91.2"),
        auto,
        fin);
  }

  @Test
  void trangKetQuaTheoHopDongVaPhamViSCR1603() throws Exception {
    UUID p1 = new UUID(0L, 1L);
    UUID p2 = new UUID(0L, 2L);
    UUID p3 = new UUID(0L, 3L);
    UUID snap = UUID.randomUUID();
    UnitResultRepository repo =
        (t, f, limit) -> {
          assertThat(f.unitId()).isEqualTo(unit);
          assertThat(f.periodId()).isEqualTo(period);
          assertThat(f.scope().sql()).isEqualTo("p.org_unit_id = ANY(?)");
          return List.of(
              row(p1, snap, "HTTNV", "HTTNV"), row(p2, null, null, null), row(p3, null, "", null));
        };
    OpaSqlFilter opa = mock(OpaSqlFilter.class);
    when(opa.buildPredicate(any(), eq("SCR-1603.xem"), any()))
        .thenReturn(new SqlPredicate("p.org_unit_id = ANY(?)", List.of(unit)));
    AuthzSubjectResolver resolver = resolver(subject(unit.toString()));
    UnitAppraisalResultController controller =
        new UnitAppraisalResultController(
            new UnitResultService(repo), provider(resolver), provider(opa));

    UnitAppraisalResultPage page =
        TenantContext.call(
                tenant, () -> controller.listUnitAppraisalResults(unit, period, "DANG", 2, null))
            .getBody();

    assertThat(page.getItems()).hasSize(2);
    UnitAppraisalResultRow first = page.getItems().get(0);
    assertThat(first.getProfileId()).isEqualTo(p1);
    assertThat(first.getPerson().getName()).isEqualTo("Trần Minh Hoàng");
    assertThat(first.getOrgUnit().getName()).isEmpty();
    assertThat(first.getScoreSnapshotId()).isEqualTo(snap);
    assertThat(first.getTotalScore()).isEqualByComparingTo("91.2");
    assertThat(first.getRatingCode()).isEqualTo(RatingLevel.HTTNV);
    assertThat(first.getStatus()).isEqualTo(AppraisalProfileStatus.DA_KY_SO);
    UnitAppraisalResultRow second = page.getItems().get(1);
    assertThat(second.getScoreSnapshotId()).isNull();
    assertThat(second.getPerson().getName()).isEmpty();
    assertThat(second.getAutoRatingCode()).isNull();
    assertThat(page.getNextCursor()).isEqualTo(SelfAssessmentController.encodeCursor(p2));
    assertThat(UnitAppraisalResultController.level("")).isNull();
  }

  @Test
  void khongCoOpaThiChiDonViMinhChinhSachKhongDichDuocTra403() throws Exception {
    UnitResultRepository repo = (t, f, limit) -> List.of();
    UnitAppraisalResultController noOpa =
        new UnitAppraisalResultController(
            new UnitResultService(repo),
            provider(resolver(subject(unit.toString()))),
            provider((OpaSqlFilter) null));
    Scope own = noOpa.scope(null);
    assertThat(own.sql()).isEqualTo("p.org_unit_id = ?");
    assertThat(own.params()).containsExactly(unit);
    UnitAppraisalResultController noUnit =
        new UnitAppraisalResultController(
            new UnitResultService(repo),
            provider(resolver(subject(null))),
            provider((OpaSqlFilter) null));
    assertThat(noUnit.scope("DANG")).isSameAs(Scope.NONE);
    var empty =
        TenantContext.call(
            tenant, () -> noUnit.listUnitAppraisalResults(unit, period, null, null, null));
    assertThat(empty.getBody().getItems()).isEmpty();
    assertThat(empty.getBody().getNextCursor()).isNull();

    OpaSqlFilter opa = mock(OpaSqlFilter.class);
    when(opa.buildPredicate(any(), anyString(), any()))
        .thenThrow(new UntranslatablePolicyException("không dịch được"));
    UnitAppraisalResultController blocked =
        new UnitAppraisalResultController(
            new UnitResultService(repo),
            provider(resolver(subject(unit.toString()))),
            provider(opa));
    assertThatThrownBy(() -> blocked.scope("NHA_NUOC"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("không có quyền"));

    UnitAppraisalResultController anonymous =
        new UnitAppraisalResultController(
            new UnitResultService(repo),
            provider((AuthzSubjectResolver) null),
            provider((OpaSqlFilter) null));
    assertThatThrownBy(() -> anonymous.scope(null)).isInstanceOf(ResponseStatusException.class);
  }
}
