package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Máy trạng thái và bất biến của aggregate kỳ (CORE-08-02, baseline mục 9.1). */
class PeriodTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID ACTOR = UUID.randomUUID();
  private static final Instant AT = Instant.parse("2027-01-04T01:00:00Z");

  static Period.Builder quarter(LocalDate start, LocalDate end) {
    return Period.builder()
        .id(PeriodId.of(UUID.randomUUID()))
        .tenantId(TENANT)
        .code("2027-Q1")
        .name("Quý I/2027")
        .kind(PeriodKind.QUY)
        .flow(PeriodFlow.CA_HAI)
        .startDate(start)
        .endDate(end)
        .schedule(DefaultPeriodCalendar.scheduleOf(start, end))
        .created(ACTOR, AT);
  }

  static Period q1() {
    return quarter(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31)).build();
  }

  @ParameterizedTest
  @CsvSource({
    "MO, DANG_KHOA, SAGA_CHOT_KY",
    "DANG_KHOA, MO, BU_TRU_SAGA",
    "DANG_KHOA, DA_KHOA, SAGA_CHOT_KY",
    "DA_KHOA, MO_LAI_HAU_KIEM, MO_LAI_HAU_KIEM",
    "MO_LAI_HAU_KIEM, DA_KHOA, KHOA_LAI"
  })
  void fiveValidTransitionsCarryTheirCause(
      PeriodState from, PeriodState to, PeriodChangeCause cause) {
    assertThat(from.causeOf(to)).contains(cause);
    assertThat(from.canMoveTo(to)).isTrue();
  }

  @Test
  void everyOtherTransitionIsRejected() {
    int valid = 0;
    for (PeriodState from : PeriodState.values()) {
      for (PeriodState to : PeriodState.values()) {
        if (from.canMoveTo(to)) {
          valid++;
        }
      }
    }
    assertThat(valid).isEqualTo(5);
    Period p = q1();
    assertThatThrownBy(() -> p.moveTo(PeriodState.DA_KHOA, UUID.randomUUID(), ACTOR, ACTOR, AT))
        .isInstanceOf(IllegalPeriodTransitionException.class)
        .satisfies(
            e -> {
              IllegalPeriodTransitionException ex = (IllegalPeriodTransitionException) e;
              assertThat(ex.from()).isEqualTo(PeriodState.MO);
              assertThat(ex.to()).isEqualTo(PeriodState.DA_KHOA);
            });
    assertThat(p.state()).isEqualTo(PeriodState.MO);
    assertThat(p.rowVersion()).isZero();
  }

  @Test
  void lockCycleSetsLockFieldsAndReopenCountsUp() {
    Period p = q1();
    UUID saga = UUID.randomUUID();
    PeriodTransition t1 = p.moveTo(PeriodState.DANG_KHOA, UUID.randomUUID(), saga, ACTOR, AT);
    assertThat(t1.cause()).isEqualTo(PeriodChangeCause.SAGA_CHOT_KY);
    assertThat(p.lockSagaId()).isEqualTo(saga);
    assertThat(p.state().writable()).isFalse();
    PeriodTransition t2 = p.moveTo(PeriodState.DA_KHOA, UUID.randomUUID(), saga, ACTOR, AT);
    assertThat(t2.rowVersion()).isEqualTo(2);
    assertThat(p.lockedAt()).isEqualTo(AT);
    assertThat(p.lockedBy()).isEqualTo(ACTOR);
    UUID reopen = UUID.randomUUID();
    p.moveTo(PeriodState.MO_LAI_HAU_KIEM, UUID.randomUUID(), reopen, ACTOR, AT);
    assertThat(p.reopenCount()).isEqualTo(1);
    assertThat(p.state().writable()).isFalse();
    assertThat(p.lockSagaId()).isEqualTo(saga);
    PeriodTransition t4 = p.moveTo(PeriodState.DA_KHOA, UUID.randomUUID(), reopen, ACTOR, AT);
    assertThat(t4.cause()).isEqualTo(PeriodChangeCause.KHOA_LAI);
    assertThat(t4.from()).isEqualTo(PeriodState.MO_LAI_HAU_KIEM);
    assertThat(p.rowVersion()).isEqualTo(4);
  }

  @ParameterizedTest
  @CsvSource({
    "QUY, 2026-10-01, 20264",
    "QUY, 2027-01-01, 20271",
    "THANG, 2027-05-01, 20272",
    "NAM, 2026-01-01, 20260",
    "NAM_HOC, 2026-09-05, 20260"
  })
  void bucketIsYearTimesTenPlusQuarter(PeriodKind kind, LocalDate start, int bucket) {
    assertThat(Period.bucketOf(kind, start)).isEqualTo(bucket);
  }

  @Test
  void notStartedOverlapsAndCovers() {
    Period p = q1();
    assertThat(p.notStarted(LocalDate.of(2026, 12, 31))).isTrue();
    assertThat(p.notStarted(LocalDate.of(2027, 1, 1))).isFalse();
    assertThat(p.covers(LocalDate.of(2027, 3, 31))).isTrue();
    assertThat(p.covers(LocalDate.of(2027, 4, 1))).isFalse();
    Period touching = quarter(LocalDate.of(2027, 3, 31), LocalDate.of(2027, 6, 30)).build();
    Period after = quarter(LocalDate.of(2027, 4, 1), LocalDate.of(2027, 6, 30)).build();
    Period otherFlow =
        quarter(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31)).flow(PeriodFlow.DANG).build();
    assertThat(p.overlaps(touching)).isTrue();
    assertThat(p.overlaps(after)).isFalse();
    assertThat(p.overlaps(otherFlow)).isFalse();
  }

  @Test
  void invariantsRejectBadInput() {
    assertThatThrownBy(() -> quarter(LocalDate.of(2027, 3, 31), LocalDate.of(2027, 1, 1)).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> quarter(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31)).code(" ").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                quarter(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))
                    .name("x".repeat(101))
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                quarter(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))
                    .state(PeriodState.DA_KHOA)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
    Period restored =
        quarter(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))
            .parentId(PeriodId.of(UUID.randomUUID()))
            .state(PeriodState.DA_KHOA)
            .locked(AT, ACTOR, UUID.randomUUID())
            .reopenCount(2)
            .rowVersion(9)
            .build();
    assertThat(restored.parentId()).isNotNull();
    assertThat(restored.reopenCount()).isEqualTo(2);
    assertThat(restored.rowVersion()).isEqualTo(9);
    assertThat(restored.createdBy()).isEqualTo(ACTOR);
    assertThat(restored.name()).isEqualTo("Quý I/2027");
  }

  @ParameterizedTest
  @EnumSource(PeriodState.class)
  void writableOnlyWhenOpen(PeriodState s) {
    assertThat(s.writable()).isEqualTo(s == PeriodState.MO);
  }
}
