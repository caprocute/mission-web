package vn.viettel.okrkpi.dossier.domain.specialcase;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Dữ kiện của một người trong một kỳ để đối chiếu 13 trường hợp: phân công đọc từ bản sao tổ chức
 * và các dữ kiện nhân sự bổ sung ({@link Supplement}) mà mission-core chưa có nguồn (nghỉ dài ngày,
 * kỷ luật, xác minh, tổ chức lại đơn vị…); trường bổ sung rỗng nghĩa là chưa biết, bộ phát hiện bỏ
 * qua trường hợp tương ứng.
 */
public record PersonPeriodFacts(
    UUID personId,
    LocalDate periodStart,
    LocalDate periodEnd,
    List<AssignmentFact> assignments,
    Supplement supplement) {

  public PersonPeriodFacts {
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(periodStart, "periodStart");
    Objects.requireNonNull(periodEnd, "periodEnd");
    assignments = assignments == null ? List.of() : List.copyOf(assignments);
    supplement = supplement == null ? Supplement.NONE : supplement;
  }

  /** Số ngày của kỳ, tính cả hai đầu. */
  public long periodDays() {
    return ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
  }

  /**
   * Dữ kiện nhân sự ngoài bản sao tổ chức.
   *
   * @param leaveDays số ngày nghỉ thai sản, ốm đau trong kỳ (§4)
   * @param longTermStudy đang đi học dài hạn (§5)
   * @param underInvestigation đang bị xem xét, xác minh, kiểm tra (§7)
   * @param disciplinedOn ngày quyết định kỷ luật (§8)
   * @param disciplineForm hình thức kỷ luật (§8)
   * @param unitReorganizedOn ngày đơn vị được thành lập, sáp nhập, chia tách (§9)
   * @param outsidePoliticalSystem đảng viên công tác ngoài hệ thống chính trị (§11)
   * @param proposedLevelIneligible không đủ điều kiện của mức đề xuất (§12)
   * @param subordinateUnits số đơn vị người đứng đầu phụ trách (§13)
   * @param subordinateUnitsFailed số đơn vị phụ trách xếp loại Không hoàn thành nhiệm vụ (§13)
   */
  public record Supplement(
      Integer leaveDays,
      Boolean longTermStudy,
      Boolean underInvestigation,
      LocalDate disciplinedOn,
      String disciplineForm,
      LocalDate unitReorganizedOn,
      Boolean outsidePoliticalSystem,
      Boolean proposedLevelIneligible,
      Integer subordinateUnits,
      Integer subordinateUnitsFailed) {

    public static final Supplement NONE =
        new Supplement(null, null, null, null, null, null, null, null, null, null);
  }
}
