package vn.viettel.okrkpi.config.application;

import java.time.LocalDate;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodMilestones;
import vn.viettel.okrkpi.shared.kernel.PeriodId;

/**
 * Lệnh tạo kỳ ({@code createPeriod}). {@code planDeadline}, {@code submissionDeadline}, {@code
 * milestones} trống thì lấy theo lịch của Policy Pack hoặc lịch mặc định.
 */
public record NewPeriod(
    String code,
    String name,
    PeriodKind kind,
    PeriodFlow flow,
    PeriodId parentId,
    LocalDate startDate,
    LocalDate endDate,
    LocalDate planDeadline,
    LocalDate submissionDeadline,
    PeriodMilestones milestones) {}
