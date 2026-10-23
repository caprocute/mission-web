package vn.viettel.okrkpi.appraisal.application.annual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.AnnualRow;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.QuarterRef;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.UnitGroup;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class AnnualRatingServiceTest {

  private static final TenantId TENANT = new TenantId(UUID.randomUUID());
  private static final UUID PERSON = UUID.randomUUID();

  private final AnnualRatingRepository repository = mock(AnnualRatingRepository.class);
  private final ScoreSnapshotReader snapshots = mock(ScoreSnapshotReader.class);
  private final AnnualRatingService service = new AnnualRatingService(repository, snapshots);

  @Test
  void congThucVaCauLienThong() {
    List<QuarterRef> qs =
        List.of(
            quarter(1, "HTTNV", "91.8"),
            quarter(2, "KHTNV", "58.6"),
            quarter(3, null, "70"),
            quarter(4, null, "91.2"));
    assertThat(AnnualRatingService.formula(qs, new BigDecimal("80.533")))
        .isEqualTo("(91,8 + 58,6 + 91,2) / 3 = 80,5");
    assertThat(AnnualRatingService.formula(qs, null)).isNull();
    assertThat(AnnualRatingService.formula(List.of(), BigDecimal.ONE)).isNull();
    AnnualRow both = row(true, true, "HTNV");
    assertThat(AnnualRatingService.linkageText(both, qs))
        .contains("quý II")
        .contains("khoản 15.5")
        .contains("khoản 15.6")
        .doesNotContain("KHTNV");
    assertThat(AnnualRatingService.linkageText(row(false, false, "HTNV"), qs)).isNull();
    assertThat(AnnualRatingService.linkageNote(both)).contains("15.6");
    assertThat(AnnualRatingService.linkageNote(row(true, false, null))).contains("15.5");
    assertThat(AnnualRatingService.linkageNote(row(false, false, null))).isNull();
    assertThat(AnnualRatingService.roman(4)).isEqualTo("IV");
    assertThat(AnnualRatingService.roman(5)).isEqualTo("5");
  }

  @Test
  void giaiThichKiemQuyenVaChuaTinh() {
    AnnualRow r = row(true, false, null);
    when(repository.find(TENANT, r.id())).thenReturn(Optional.of(r));
    when(snapshots.current(TENANT, 20290, r.profileId())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.explain(TENANT, r.id(), UUID.randomUUID(), x -> false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service.explain(TENANT, r.id(), PERSON, x -> false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("APPRAISAL-409-NOT-COMPUTED"));
    AnnualRow scored = row(true, false, "HTNV");
    when(repository.find(TENANT, scored.id())).thenReturn(Optional.of(scored));
    when(repository.quarters(TENANT, 2029, PERSON, "DANG"))
        .thenReturn(List.of(quarter(2, "KHTNV", "50")));
    var e = service.explain(TENANT, scored.id(), UUID.randomUUID(), x -> true).orElseThrow();
    assertThat(e.systemLevel()).isEqualTo("HTNV");
    assertThat(e.legalRef()).contains("15.5");
    assertThat(service.explain(TENANT, UUID.randomUUID(), PERSON, x -> true)).isEmpty();
  }

  @Test
  void lapHoSoNamChiKhiQuyIv() {
    PeriodApi periods = mock(PeriodApi.class);
    PolicyQueryApi policies = mock(PolicyQueryApi.class);
    AppraisalSpecialCases cases = mock(AppraisalSpecialCases.class);
    AnnualSetupService setup = new AnnualSetupService(repository, periods, policies, cases);
    UUID q4 = UUID.randomUUID();
    UUID annual = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    Instant at = Instant.parse("2030-01-10T00:00:00Z");
    assertThat(setup.onQuarterLocked(TENANT, q4, 20293, at)).isZero();
    when(periods.findById(TENANT, PeriodId.of(q4))).thenReturn(Optional.of(period(q4, 10)));
    assertThat(setup.onQuarterLocked(TENANT, q4, 20294, at)).isZero();
    verify(repository, never())
        .createAnnual(any(), any(), anyInt(), anyString(), any(), any(), any());

    when(periods.findCovering(TENANT, "NAM", LocalDate.parse("2029-12-31")))
        .thenReturn(Optional.of(period(annual, 1)));
    when(repository.unitGroups(eq(TENANT), eq(20294), anyString()))
        .thenReturn(List.of(new UnitGroup(unit, null), new UnitGroup(unit, null)));
    when(policies.resolve(any(), any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new ResolvedPack(
                    UUID.randomUUID(),
                    "GOI",
                    version,
                    "2029.1",
                    "ab",
                    null,
                    UUID.randomUUID(),
                    true)));
    when(repository.createAnnual(any(), any(), anyInt(), anyString(), any(), any(), any()))
        .thenReturn(2);
    assertThat(setup.onQuarterLocked(TENANT, q4, 20294, at)).isEqualTo(4);
    verify(repository)
        .createAnnual(
            eq(TENANT),
            eq(annual),
            eq(2029),
            eq("DANG"),
            eq(java.util.Map.of(unit, version)),
            any(),
            any());
  }

  @Test
  void trangVaSoDem() {
    var filter =
        new AnnualRatingRepository.Filter(
            UUID.randomUUID(),
            null,
            "DANG",
            true,
            "an",
            vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope.NONE,
            null);
    var counts = new AnnualRatingRepository.Counts(1, 2, 1, 0, 2);
    when(repository.page(TENANT, filter, 11)).thenReturn(List.of(row(true, false, null)));
    when(repository.counts(TENANT, filter)).thenReturn(counts);
    var page = service.page(TENANT, filter, 11);
    assertThat(page.rows()).hasSize(1);
    assertThat(page.counts().profilesTotal()).isEqualTo(2);
    assertThat(filter.linkageOnly()).isTrue();
    assertThat(new UnitGroup(null, null).orgUnitId()).isNull();
  }

  private static PeriodSnapshot period(UUID id, int startMonth) {
    LocalDate start = LocalDate.of(2029, startMonth, 1);
    return new PeriodSnapshot(
        PeriodId.of(id),
        TENANT,
        "KY",
        "Kỳ",
        start,
        LocalDate.parse("2029-12-31"),
        PeriodStatus.DA_KHOA);
  }

  private static QuarterRef quarter(int q, String rating, String score) {
    return new QuarterRef(
        q,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "DA_KY_SO",
        rating,
        UUID.randomUUID(),
        new byte[] {1},
        new BigDecimal(score),
        "1.0",
        UUID.randomUUID());
  }

  private static AnnualRow row(boolean khtnv, boolean consecutive, String systemLevel) {
    return new AnnualRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        PERSON,
        "Nguyễn Văn A",
        UUID.randomUUID(),
        "Phòng Tổng hợp",
        "DANG",
        "CHUA_LAP",
        java.util.Arrays.asList("HTTNV", "KHTNV", null, null),
        systemLevel == null ? null : new BigDecimal("80.5"),
        null,
        systemLevel,
        null,
        khtnv,
        consecutive,
        khtnv ? "CO_QUY_KHTNV" : null,
        consecutive,
        false,
        1L,
        UUID.randomUUID(),
        2029,
        null,
        null);
  }
}
