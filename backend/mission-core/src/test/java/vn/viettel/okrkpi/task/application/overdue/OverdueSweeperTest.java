package vn.viettel.okrkpi.task.application.overdue;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.overdue.port.OverdueTasks;
import vn.viettel.okrkpi.task.application.overdue.port.OverdueTasks.OpenTask;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;

class OverdueSweeperTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());
  private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
  private static final LocalDate SATURDAY = FRIDAY.plusDays(1);
  private static final LocalDate MONDAY = FRIDAY.plusDays(3);

  /** Kho nhiệm vụ trong bộ nhớ; {@code refuse} giả lập bản sao khác đã đổi cờ trước. */
  static final class Tasks implements OverdueTasks {
    final List<OpenTask> rows = new ArrayList<>();
    final Set<UUID> flagged = new HashSet<>();
    boolean refuse;
    int pages;

    @Override
    public List<OpenTask> page(TenantId tenant, UUID afterId, int limit) {
      pages++;
      return rows.stream()
          .filter(r -> afterId == null || r.id().compareTo(afterId) > 0)
          .sorted((a, b) -> a.id().compareTo(b.id()))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean flag(TenantId tenant, int periodBucket, UUID taskId, boolean overdue) {
      if (refuse) {
        return false;
      }
      return overdue ? flagged.add(taskId) : flagged.remove(taskId);
    }
  }

  private final Tasks tasks = new Tasks();
  private final List<TaskReminderNotices.Notice> sent = new ArrayList<>();

  private OverdueSweeper sweeper(int batch) {
    return new OverdueSweeper(
        tasks,
        sent::add,
        null,
        TransactionOperations.withoutTransaction(),
        new BigDecimal("0.05"),
        batch);
  }

  private OpenTask task(long n, String status, boolean overdue, UUID lead) {
    return new OpenTask(
        new UUID(0L, n),
        202604,
        "NV-" + n,
        "Nhiệm vụ " + n,
        UUID.randomUUID(),
        lead,
        FRIDAY,
        status,
        overdue,
        3L);
  }

  @Test
  void dueFridayRunMondayIsOneWorkingDayLateAndNotifiesLeadAndAssigner() {
    tasks.rows.add(task(1, "DANG_THUC_HIEN", false, UUID.randomUUID()));

    OverdueSweeper.Outcome o = sweeper(10).sweep(T, MONDAY);

    assertThat(o).isEqualTo(new OverdueSweeper.Outcome(1, 0));
    assertThat(sent)
        .singleElement()
        .satisfies(
            n -> {
              assertThat(n.template()).isEqualTo("NHIEM_VU_QUA_HAN");
              assertThat(n.recipients()).hasSize(2);
              assertThat(n.params())
                  .containsEntry("soNgayCham", "1")
                  .containsEntry("heSoC", "0.95");
              assertThat(n.taskVersion()).isEqualTo(3L);
            });
  }

  @Test
  void weekendAfterDueDateIsNotLate() {
    tasks.rows.add(task(1, "DA_GIAO", false, null));
    assertThat(sweeper(10).sweep(T, SATURDAY)).isEqualTo(new OverdueSweeper.Outcome(0, 0));
    assertThat(sweeper(10).sweep(T, FRIDAY)).isEqualTo(new OverdueSweeper.Outcome(0, 0));
    assertThat(sent).isEmpty();
  }

  @Test
  void suspendedTaskIsNeverFlaggedAndOldFlagIsCleared() {
    tasks.rows.add(task(1, "TAM_DUNG", true, null));
    tasks.flagged.add(new UUID(0L, 1));

    assertThat(sweeper(10).sweep(T, MONDAY)).isEqualTo(new OverdueSweeper.Outcome(0, 1));
    assertThat(tasks.flagged).isEmpty();
    assertThat(sent).isEmpty();
  }

  @Test
  void holidayFromCalendarClearsFlag() {
    tasks.rows.add(task(1, "DANG_THUC_HIEN", true, null));
    tasks.flagged.add(new UUID(0L, 1));
    var calendar = org.mockito.Mockito.mock(vn.viettel.okrkpi.config.api.WorkingCalendarApi.class);
    org.mockito.Mockito.when(calendar.countWorkingDays(T, FRIDAY, MONDAY)).thenReturn(0L);
    OverdueSweeper s =
        new OverdueSweeper(
            tasks,
            sent::add,
            calendar,
            TransactionOperations.withoutTransaction(),
            BigDecimal.ONE,
            10);

    assertThat(s.sweep(T, MONDAY)).isEqualTo(new OverdueSweeper.Outcome(0, 1));
  }

  @Test
  void lostFlipSendsNoNoticeAndNoAssigneeMeansNoNotice() {
    tasks.rows.add(task(1, "DANG_THUC_HIEN", false, null));
    tasks.refuse = true;
    assertThat(sweeper(10).sweep(T, MONDAY)).isEqualTo(new OverdueSweeper.Outcome(0, 0));
    assertThat(sent).isEmpty();

    tasks.refuse = false;
    tasks.rows.clear();
    tasks.rows.add(
        new OpenTask(
            new UUID(0L, 2), 202604, "NV-2", "x", null, null, FRIDAY, "DA_GIAO", false, 1L));
    assertThat(sweeper(10).sweep(T, MONDAY)).isEqualTo(new OverdueSweeper.Outcome(1, 0));
    assertThat(sent).isEmpty();
  }

  @Test
  void pagesAcrossBatchesAndSkipsAlreadyCorrectFlags() {
    for (long i = 1; i <= 5; i++) {
      tasks.rows.add(task(i, "DANG_THUC_HIEN", i == 5, UUID.randomUUID()));
    }
    tasks.flagged.add(new UUID(0L, 5));

    assertThat(sweeper(2).sweep(T, MONDAY)).isEqualTo(new OverdueSweeper.Outcome(4, 0));
    assertThat(tasks.pages).isEqualTo(3);
    assertThat(sent).hasSize(4);
  }

  @Test
  void missingScoreDueDateIsNotLate() {
    tasks.rows.add(
        new OpenTask(new UUID(0L, 1), 202604, "NV-1", "x", null, null, null, "DA_GIAO", false, 1L));
    assertThat(sweeper(10).sweep(T, MONDAY)).isEqualTo(new OverdueSweeper.Outcome(0, 0));
  }
}
