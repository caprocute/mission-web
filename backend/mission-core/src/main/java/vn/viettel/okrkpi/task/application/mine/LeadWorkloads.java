package vn.viettel.okrkpi.task.application.mine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries.OpenAssignment;
import vn.viettel.okrkpi.task.domain.assign.AssignmentRules;
import vn.viettel.okrkpi.task.domain.assign.AssignmentRules.CheckItem;
import vn.viettel.okrkpi.task.domain.mine.Workload;

/**
 * Khối lượng việc của người chủ trì cho kiểm tra trước khi giao (BR-05-11): đếm việc còn mở người
 * đó chủ trì có hạn trong tuần chứa hạn của việc đang soạn; vượt ngưỡng thì cảnh báo MSG-05-04.
 * Ngưỡng là tham số triển khai tới khi Policy Pack có tham số này (CORE-05).
 */
public class LeadWorkloads {

  /** Số tuần trả về cho biểu đồ khối lượng: tuần trước, tuần chứa hạn và hai tuần sau. */
  static final int WEEKS = 4;

  private final MyTaskQueries queries;
  private final int weeklyThreshold;

  public LeadWorkloads(MyTaskQueries queries, int weeklyThreshold) {
    this.queries = Objects.requireNonNull(queries, "queries");
    this.weeklyThreshold = weeklyThreshold;
  }

  /** Khối lượng của một người; {@code inDueWeek} gồm cả việc đang soạn. */
  public record Result(
      UUID personId,
      int leadCount,
      int coCount,
      int threshold,
      Map<LocalDate, Integer> weeks,
      int inDueWeek,
      CheckItem check) {}

  public Result of(TenantId tenant, int periodBucket, UUID periodId, UUID person, LocalDate due) {
    List<OpenAssignment> open = queries.openAssignments(tenant, periodBucket, person, periodId);
    List<LocalDate> lead =
        open.stream()
            .filter(a -> "CHU_TRI".equals(a.role()))
            .map(OpenAssignment::effectiveDue)
            .toList();
    int co = (int) open.stream().filter(a -> "PHOI_HOP".equals(a.role())).count();
    int inWeek = Workload.inWeekOf(lead, due);
    CheckItem check =
        new CheckItem(
            "BR-05-11",
            AssignmentRules.Level.CANH_BAO,
            inWeek <= weeklyThreshold,
            Workload.message(null, inWeek, due));
    return new Result(
        person,
        lead.size(),
        co,
        weeklyThreshold,
        Workload.byWeek(lead, due.minusWeeks(1), WEEKS),
        inWeek,
        check);
  }
}
