package vn.viettel.okrkpi.org.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.domain.AssignmentRules.AssignmentPlan;
import vn.viettel.okrkpi.org.domain.AssignmentRules.EvaluationUnitResolution;

/**
 * Kiểm thử đơn vị cho quy tắc nghiệp vụ phân công, kiêm nhiệm, điều động ({@code BR-01-08})
 * và xác định đơn vị đánh giá khi điều động giữa kỳ ({@code BR-01-09}).
 */
class AssignmentRulesTest {

  private static final UUID PERSON_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID TITLE_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
  private static final UUID UNIT_OLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID UNIT_NEW = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  private static AssignmentVersion assignment(
      UUID logicalId,
      UUID unitId,
      String kind,
      String branch,
      LocalDate validFrom,
      LocalDate validTo) {
    return new AssignmentVersion(
        UUID.randomUUID(),
        logicalId,
        PERSON_ID,
        unitId,
        null,
        TITLE_ID,
        kind,
        branch,
        false,
        null,
        new ValidPeriod(validFrom, validTo),
        T0,
        null,
        0);
  }

  @Test
  void br0108_rejectsOverlappingPrimaryAssignmentOnSameBranchWithoutTransfer() {
    AssignmentVersion existingPrimary =
        assignment(
            UUID.randomUUID(),
            UNIT_OLD,
            "CHINH",
            "CHINH_QUYEN",
            LocalDate.of(2026, 1, 1),
            null);
    AssignmentVersion conflictingPrimary =
        assignment(
            UUID.randomUUID(),
            UNIT_NEW,
            "CHINH",
            "CHINH_QUYEN",
            LocalDate.of(2026, 4, 1),
            null);

    assertThatThrownBy(
            () ->
                AssignmentRules.planNewAssignment(
                    List.of(existingPrimary), conflictingPrimary, false))
        .isInstanceOf(OrgRuleViolation.class)
        .satisfies(
            ex -> {
              OrgRuleViolation v = (OrgRuleViolation) ex;
              assertThat(v.code()).isEqualTo("ORG-422-PRIMARY-OVERLAP");
              assertThat(v.rule()).isEqualTo("BR-01-08");
            });
  }

  @Test
  void br0108_allowsPrimaryOnDifferentBranchesAndMultipleConcurrentAssignments() {
    AssignmentVersion cqPrimary =
        assignment(
            UUID.randomUUID(),
            UNIT_OLD,
            "CHINH",
            "CHINH_QUYEN",
            LocalDate.of(2026, 1, 1),
            null);
    AssignmentVersion partyPrimary =
        assignment(
            UUID.randomUUID(),
            UNIT_OLD,
            "CHINH",
            "DANG",
            LocalDate.of(2026, 1, 1),
            null);
    AssignmentVersion concurrent =
        assignment(
            UUID.randomUUID(),
            UNIT_NEW,
            "KIEM_NHIEM",
            "CHINH_QUYEN",
            LocalDate.of(2026, 2, 1),
            null);
    AssignmentVersion secondment =
        assignment(
            UUID.randomUUID(),
            UNIT_NEW,
            "BIET_PHAI",
            "CHINH_QUYEN",
            LocalDate.of(2026, 2, 15),
            LocalDate.of(2026, 8, 15));

    AssignmentPlan partyPlan =
        AssignmentRules.planNewAssignment(List.of(cqPrimary), partyPrimary, false);
    assertThat(partyPlan.versionPlan().toClose()).isEmpty();
    assertThat(partyPlan.versionPlan().toInsert()).containsExactly(partyPrimary);

    AssignmentPlan concurrentPlan =
        AssignmentRules.planNewAssignment(
            List.of(cqPrimary, partyPrimary), concurrent, false);
    assertThat(concurrentPlan.versionPlan().toClose()).isEmpty();
    assertThat(concurrentPlan.versionPlan().toInsert()).containsExactly(concurrent);

    AssignmentPlan secondmentPlan =
        AssignmentRules.planNewAssignment(
            List.of(cqPrimary, partyPrimary, concurrent), secondment, false);
    assertThat(secondmentPlan.versionPlan().toClose()).isEmpty();
    assertThat(secondmentPlan.versionPlan().toInsert()).containsExactly(secondment);
  }

  @Test
  void br0108_futureTransferAutoClosesCurrentPrimaryAtTransferEffectiveDate() {
    UUID oldLogicalId = UUID.randomUUID();
    AssignmentVersion existingPrimary =
        assignment(
            oldLogicalId,
            UNIT_OLD,
            "CHINH",
            "CHINH_QUYEN",
            LocalDate.of(2026, 1, 1),
            null);
    LocalDate transferDate = LocalDate.of(2026, 5, 1);
    AssignmentVersion transferAssignment =
        assignment(
            UUID.randomUUID(),
            UNIT_NEW,
            "CHINH",
            "CHINH_QUYEN",
            transferDate,
            null);

    AssignmentPlan plan =
        AssignmentRules.planNewAssignment(
            List.of(existingPrimary), transferAssignment, true);

    assertThat(plan.versionPlan().toClose()).containsExactly(existingPrimary.versionId());
    assertThat(plan.versionPlan().toInsert()).hasSize(2);
    AssignmentVersion shortenedOld = plan.versionPlan().toInsert().get(0);
    assertThat(shortenedOld.id()).isEqualTo(oldLogicalId);
    assertThat(shortenedOld.validPeriod().from()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(shortenedOld.validPeriod().to()).isEqualTo(transferDate);
    assertThat(shortenedOld.validPeriod().contains(LocalDate.of(2026, 4, 30))).isTrue();
    assertThat(shortenedOld.validPeriod().contains(transferDate)).isFalse();
    assertThat(plan.versionPlan().toInsert().get(1)).isEqualTo(transferAssignment);
    assertThat(plan.changedAssignmentIds()).containsExactly(oldLogicalId, transferAssignment.id());
  }

  @Test
  void br0109_boundary_1Month29DaysBeforePeriodEnd_evaluatesAtOldUnit() {
    // Kỳ 6 tháng đầu năm: 01/01/2026 .. 30/06/2026 (periodEndExclusive = 01/07/2026)
    // Điều động ngày 02/05/2026 -> làm việc tại đơn vị mới từ 02/05 đến hết 30/06 = 1 tháng 29 ngày (< 2 tháng)
    LocalDate periodStart = LocalDate.of(2026, 1, 1);
    LocalDate periodEnd = LocalDate.of(2026, 6, 30);
    LocalDate transferDate = LocalDate.of(2026, 5, 2);

    AssignmentVersion oldUnitAssign =
        assignment(
            UUID.randomUUID(),
            UNIT_OLD,
            "CHINH",
            "CHINH_QUYEN",
            LocalDate.of(2025, 1, 1),
            transferDate);
    AssignmentVersion newUnitAssign =
        assignment(
            UUID.randomUUID(),
            UNIT_NEW,
            "CHINH",
            "CHINH_QUYEN",
            transferDate,
            null);

    EvaluationUnitResolution res =
        AssignmentRules.danhGiaTaiDonVi(
            List.of(oldUnitAssign, newUnitAssign), periodStart, periodEnd);

    assertThat(res.transferredMidPeriod()).isTrue();
    assertThat(res.transferredUnderTwoMonths()).isTrue();
    assertThat(res.evaluatingUnitId()).isEqualTo(UNIT_OLD);
    assertThat(res.currentUnitId()).isEqualTo(UNIT_NEW);
    assertThat(res.previousUnitId()).isEqualTo(UNIT_OLD);
  }

  @Test
  void br0109_boundary_exact2MonthsBeforePeriodEnd_evaluatesAtNewUnit() {
    // Kỳ 6 tháng đầu năm: 01/01/2026 .. 30/06/2026 (periodEndExclusive = 01/07/2026)
    // Điều động ngày 01/05/2026 -> làm việc tại đơn vị mới từ 01/05 đến hết 30/06 = đúng 2 tháng (>= 2 tháng)
    LocalDate periodStart = LocalDate.of(2026, 1, 1);
    LocalDate periodEnd = LocalDate.of(2026, 6, 30);
    LocalDate transferDate = LocalDate.of(2026, 5, 1);

    AssignmentVersion oldUnitAssign =
        assignment(
            UUID.randomUUID(),
            UNIT_OLD,
            "CHINH",
            "CHINH_QUYEN",
            LocalDate.of(2025, 1, 1),
            transferDate);
    AssignmentVersion newUnitAssign =
        assignment(
            UUID.randomUUID(),
            UNIT_NEW,
            "CHINH",
            "CHINH_QUYEN",
            transferDate,
            null);

    EvaluationUnitResolution res =
        AssignmentRules.danhGiaTaiDonVi(
            List.of(oldUnitAssign, newUnitAssign), periodStart, periodEnd);

    assertThat(res.transferredMidPeriod()).isTrue();
    assertThat(res.transferredUnderTwoMonths()).isFalse();
    assertThat(res.evaluatingUnitId()).isEqualTo(UNIT_NEW);
    assertThat(res.currentUnitId()).isEqualTo(UNIT_NEW);
    assertThat(res.previousUnitId()).isEqualTo(UNIT_OLD);
  }

  @Test
  void br0109_boundary_2Months1DayBeforePeriodEnd_evaluatesAtNewUnit() {
    // Kỳ 6 tháng đầu năm: 01/01/2026 .. 30/06/2026 (periodEndExclusive = 01/07/2026)
    // Điều động ngày 30/04/2026 -> làm việc tại đơn vị mới từ 30/04 đến hết 30/06 = 2 tháng 1 ngày (>= 2 tháng)
    LocalDate periodStart = LocalDate.of(2026, 1, 1);
    LocalDate periodEnd = LocalDate.of(2026, 6, 30);
    LocalDate transferDate = LocalDate.of(2026, 4, 30);

    AssignmentVersion oldUnitAssign =
        assignment(
            UUID.randomUUID(),
            UNIT_OLD,
            "CHINH",
            "CHINH_QUYEN",
            LocalDate.of(2025, 1, 1),
            transferDate);
    AssignmentVersion newUnitAssign =
        assignment(
            UUID.randomUUID(),
            UNIT_NEW,
            "CHINH",
            "CHINH_QUYEN",
            transferDate,
            null);

    EvaluationUnitResolution res =
        AssignmentRules.danhGiaTaiDonVi(
            List.of(oldUnitAssign, newUnitAssign), periodStart, periodEnd);

    assertThat(res.transferredMidPeriod()).isTrue();
    assertThat(res.transferredUnderTwoMonths()).isFalse();
    assertThat(res.evaluatingUnitId()).isEqualTo(UNIT_NEW);
    assertThat(res.currentUnitId()).isEqualTo(UNIT_NEW);
    assertThat(res.previousUnitId()).isEqualTo(UNIT_OLD);
  }

  @Test
  void br0109_singleUnitThroughoutPeriod_returnsCurrentUnitWithoutTransfer() {
    LocalDate periodStart = LocalDate.of(2026, 1, 1);
    LocalDate periodEnd = LocalDate.of(2026, 12, 31);
    AssignmentVersion assign =
        assignment(
            UUID.randomUUID(),
            UNIT_OLD,
            "CHINH",
            "CHINH_QUYEN",
            LocalDate.of(2025, 1, 1),
            null);

    EvaluationUnitResolution res =
        AssignmentRules.danhGiaTaiDonVi(List.of(assign), periodStart, periodEnd);

    assertThat(res.transferredMidPeriod()).isFalse();
    assertThat(res.transferredUnderTwoMonths()).isFalse();
    assertThat(res.evaluatingUnitId()).isEqualTo(UNIT_OLD);
    assertThat(res.previousUnitId()).isNull();
  }

  @Test
  void reportingLine_validatesSelfReportingAndPlansBitemporalReplacement() {
    UUID sup1 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    UUID sup2 = UUID.fromString("55555555-5555-5555-5555-555555555555");

    assertThatThrownBy(
            () ->
                new ReportingLineVersion(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    PERSON_ID,
                    PERSON_ID,
                    "TRUC_TIEP",
                    "CHINH_QUYEN",
                    null,
                    new ValidPeriod(LocalDate.of(2026, 1, 1), null),
                    T0,
                    null,
                    0))
        .isInstanceOf(OrgRuleViolation.class)
        .extracting(ex -> ((OrgRuleViolation) ex).code())
        .isEqualTo("ORG-422-SELF-REPORTING");

    ReportingLineVersion line1 =
        new ReportingLineVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PERSON_ID,
            sup1,
            "TRUC_TIEP",
            "CHINH_QUYEN",
            null,
            new ValidPeriod(LocalDate.of(2026, 1, 1), null),
            T0,
            null,
            0);

    ReportingLineVersion line2 =
        new ReportingLineVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PERSON_ID,
            sup2,
            "TRUC_TIEP",
            "CHINH_QUYEN",
            null,
            new ValidPeriod(LocalDate.of(2026, 6, 1), null),
            T0,
            null,
            0);

    VersionPlan<ReportingLineVersion> diff =
        AssignmentRules.planReportingLine(List.of(line1), line2, true);
    assertThat(diff.toClose()).containsExactly(line1.versionId());
    assertThat(diff.toInsert()).hasSize(2);
    assertThat(diff.toInsert().get(0).validPeriod().to()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(diff.toInsert().get(1)).isEqualTo(line2);
  }
}
