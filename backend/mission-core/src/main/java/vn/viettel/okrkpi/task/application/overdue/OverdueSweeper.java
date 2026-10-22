package vn.viettel.okrkpi.task.application.overdue;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.overdue.port.OverdueTasks;
import vn.viettel.okrkpi.task.application.overdue.port.OverdueTasks.OpenTask;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.domain.change.ChangeRules;

/**
 * Đặt cờ quá hạn (QT-06 bước 8, CORE-02-23): nhiệm vụ {@code DA_GIAO}, {@code DANG_THUC_HIEN} đã
 * qua hạn tính điểm ít nhất một ngày làm việc thì quá hạn; {@code TAM_DUNG} không bị đặt cờ
 * (BR-06-10). Duyệt theo lô khoá {@code id}, mỗi lô một giao dịch nên chạy lại sau khi pod khởi
 * động lại không sai. Nhiệm vụ vừa chuyển sang quá hạn được nhắc một lần (MSG-06-03) tới chủ trì và
 * người giao kèm số ngày chậm và hệ số C dự kiến; đổi cờ có điều kiện nên hai bản sao chạy cùng lúc
 * chỉ sinh một bộ EVT-21. Cùng thủ tục được gọi lại khi lịch nghỉ đổi để tính lại cờ.
 */
public class OverdueSweeper {

  static final String TEMPLATE = "NHIEM_VU_QUA_HAN";
  static final UUID SYSTEM = new UUID(0L, 0L);

  private final OverdueTasks tasks;
  private final TaskReminderNotices notices;
  private final WorkingCalendarApi calendar;
  private final TransactionOperations tx;
  private final BigDecimal coefficientStep;
  private final int batchSize;

  public OverdueSweeper(
      OverdueTasks tasks,
      TaskReminderNotices notices,
      WorkingCalendarApi calendar,
      TransactionOperations tx,
      BigDecimal coefficientStep,
      int batchSize) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.calendar = calendar;
    this.tx = Objects.requireNonNull(tx, "tx");
    this.coefficientStep = Objects.requireNonNull(coefficientStep, "coefficientStep");
    this.batchSize = Math.max(1, batchSize);
  }

  /** Kết quả một lượt: số nhiệm vụ mới quá hạn và số nhiệm vụ hết quá hạn. */
  public record Outcome(int flagged, int cleared) {}

  /** Quét toàn bộ nhiệm vụ đang mở của tenant theo ngày {@code today} (giờ Việt Nam). */
  public Outcome sweep(TenantId tenant, LocalDate today) {
    int[] counts = {0, 0};
    UUID after = null;
    while (true) {
      UUID from = after;
      List<OpenTask> page =
          Objects.requireNonNull(tx.execute(s -> process(tenant, from, today, counts)));
      if (page.size() < batchSize) {
        return new Outcome(counts[0], counts[1]);
      }
      after = page.get(page.size() - 1).id();
    }
  }

  private List<OpenTask> process(TenantId tenant, UUID after, LocalDate today, int[] counts) {
    List<OpenTask> page = tasks.page(tenant, after, batchSize);
    for (OpenTask t : page) {
      long late = lateDays(tenant, t, today);
      boolean overdue = late > 0;
      if (overdue == t.overdue() || !tasks.flag(tenant, t.periodBucket(), t.id(), overdue)) {
        continue;
      }
      if (!overdue) {
        counts[1]++;
        continue;
      }
      counts[0]++;
      List<UUID> to =
          Stream.of(t.leadId(), t.assignerId()).filter(Objects::nonNull).distinct().toList();
      if (to.isEmpty()) {
        continue;
      }
      Map<String, String> p = new LinkedHashMap<>();
      p.put("maNhiemVu", t.code());
      p.put("tenNhiemVu", t.title());
      p.put("hanTinhDiem", t.scoreDueDate().toString());
      p.put("soNgayCham", String.valueOf(late));
      p.put("heSoC", ChangeRules.coefficient(coefficientStep, late).toPlainString());
      notices.notice(
          new TaskReminderNotices.Notice(tenant, t.id(), t.rowVersion(), TEMPLATE, to, p, SYSTEM));
    }
    return page;
  }

  /** Số ngày làm việc chậm so với hạn tính điểm; {@code TAM_DUNG} luôn 0. */
  long lateDays(TenantId tenant, OpenTask t, LocalDate today) {
    if ("TAM_DUNG".equals(t.status()) || t.scoreDueDate() == null) {
      return 0;
    }
    if (!today.isAfter(t.scoreDueDate())) {
      return 0;
    }
    if (calendar != null) {
      return calendar.countWorkingDays(tenant, t.scoreDueDate(), today);
    }
    long n = 0;
    for (LocalDate d = t.scoreDueDate().plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
      n += d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : 1;
    }
    return n;
  }
}
