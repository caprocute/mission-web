package vn.viettel.okrkpi.appraisal.application.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.transfer.port.RatingHistory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Phần kết quả xếp loại của gói chuyển liên cell (DV-13-10). */
class RatingTransferServiceTest {

  final TenantId tenant = TenantId.of(UUID.randomUUID());
  final UUID person = UUID.randomUUID();
  final UUID known = UUID.randomUUID();
  final UUID unknown = UUID.randomUUID();

  @Test
  void describesPeriodsAndFallsBackToIdWhenUnknown() {
    RatingHistory history =
        new RatingHistory() {
          @Override
          public List<PeriodRating> periodRatings(TenantId t, UUID p) {
            return List.of(new PeriodRating(known, "DANG", "HTXSNV", Instant.EPOCH));
          }

          @Override
          public List<AnnualRating> annualRatings(TenantId t, UUID p) {
            return List.of(
                new AnnualRating(unknown, "DANG", List.of("HTTNV"), BigDecimal.TEN, "HTTNV"));
          }
        };
    PeriodApi periods = mock(PeriodApi.class);
    when(periods.findById(tenant, PeriodId.of(known)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(known),
                    tenant,
                    "Q1-2026",
                    "Quý I năm 2026",
                    LocalDate.parse("2026-01-01"),
                    LocalDate.parse("2026-03-31"),
                    PeriodStatus.DA_KHOA)));
    when(periods.findById(tenant, PeriodId.of(unknown))).thenReturn(Optional.empty());
    RatingTransferService.Ratings r =
        new RatingTransferService(history, periods).ratings(tenant, person).orElseThrow();
    assertThat(r.version()).isEqualTo(1);
    assertThat(r.periods().get(0).period().name()).isEqualTo("Quý I năm 2026");
    assertThat(r.annual().get(0).period().code()).isEqualTo(unknown.toString());
    assertThat(
            new RatingTransferService(history, null)
                .ratings(tenant, person)
                .orElseThrow()
                .periods()
                .get(0)
                .period()
                .code())
        .isEqualTo(known.toString());
  }

  @Test
  void emptyWhenNothingDecided() {
    RatingHistory none =
        new RatingHistory() {
          @Override
          public List<PeriodRating> periodRatings(TenantId t, UUID p) {
            return List.of();
          }

          @Override
          public List<AnnualRating> annualRatings(TenantId t, UUID p) {
            return List.of();
          }
        };
    assertThat(new RatingTransferService(none, null).ratings(tenant, person)).isEmpty();
  }
}
