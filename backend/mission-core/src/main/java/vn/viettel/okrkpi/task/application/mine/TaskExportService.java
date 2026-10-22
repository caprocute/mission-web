package vn.viettel.okrkpi.task.application.mine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.TaskQueryService;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.UnitTaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.mine.port.TaskExportFiles;
import vn.viettel.okrkpi.task.application.mine.port.TaskExportJobs;
import vn.viettel.okrkpi.task.application.mine.port.TaskExportSheets;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Xuất Excel danh sách đang lọc của SCR-0503 hoặc việc của đơn vị (202 + job): chạy nền, tối đa
 * {@link #MAX_ROWS} dòng, chỉ các cột đang hiển thị với nhãn nghiệp vụ, không có dữ liệu C2. Tệp
 * giữ tạm và chỉ người tạo tác vụ tải được qua {@code downloadTaskExport}.
 */
public class TaskExportService {

  public static final int MAX_ROWS = 100_000;
  public static final String RUNNING = "TASK-429-EXPORT-RUNNING";
  public static final String LOCATION = "/api/v1/tasks/exports/";
  static final int PAGE = 500;
  static final String RESTRICTED = "Nội dung hạn chế";

  static final Map<String, String> STATUS =
      Map.of(
          "NHAP", "Nháp",
          "DA_GIAO", "Đã giao",
          "DANG_THUC_HIEN", "Đang thực hiện",
          "CHO_NGHIEM_THU", "Chờ nghiệm thu",
          "HOAN_THANH", "Hoàn thành",
          "TAM_DUNG", "Tạm dừng",
          "HUY", "Huỷ");

  /** Vai trò được xem việc của đơn vị ({@code listUnitTasks}). */
  static final java.util.Set<String> UNIT_ROLES =
      java.util.Set.of("manager", "leader", "inspector", "appraiser", "ai_readonly");

  static final Map<String, String> ROLE = Map.of("CHU_TRI", "Chủ trì", "PHOI_HOP", "Phối hợp");

  private final MyTaskService mine;
  private final TaskQueryService units;
  private final TaskExportSheets sheets;
  private final TaskExportFiles files;
  private final TaskExportJobs jobs;
  private final Executor executor;

  public TaskExportService(
      MyTaskService mine,
      TaskQueryService units,
      TaskExportSheets sheets,
      TaskExportFiles files,
      TaskExportJobs jobs,
      Executor executor) {
    this.mine = Objects.requireNonNull(mine, "mine");
    this.units = Objects.requireNonNull(units, "units");
    this.sheets = Objects.requireNonNull(sheets, "sheets");
    this.files = Objects.requireNonNull(files, "files");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /** {@code unit} khác {@code null} khi xuất việc của đơn vị, ngược lại xuất "Việc của tôi". */
  public record ExportQuery(
      MyTaskService.MineQuery mine, TaskQueryService.UnitQuery unit, boolean forUnit) {}

  /** Tạo tác vụ xuất; người dùng đang có tác vụ chưa xong thì 429. Trả mã tác vụ. */
  public String export(TenantId tenant, ExportQuery query, TaskActor actor) {
    if (query.forUnit() && actor.roles().stream().noneMatch(UNIT_ROLES::contains)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Không có quyền xuất danh sách nhiệm vụ của đơn vị");
    }
    if (jobs.running(tenant, actor.personId()) > 0) {
      throw new BusinessException(
          RUNNING, "Đang có một yêu cầu xuất danh sách chưa xong. Vui lòng chờ trong giây lát.");
    }
    String jobId = UUID.randomUUID().toString();
    jobs.accepted(tenant, jobId, actor.personId());
    executor.execute(
        () -> {
          try {
            TenantContext.call(
                tenant,
                () -> {
                  run(tenant, query, actor, jobId);
                  return null;
                });
          } catch (Exception e) {
            jobs.failed(tenant, jobId, "Không xuất được danh sách. Vui lòng thử lại.");
          }
        });
    return jobId;
  }

  /** Tệp đã xuất của người tạo tác vụ. */
  public Optional<byte[]> file(TenantId tenant, String jobId, UUID person) {
    return files.find(tenant, jobId, person);
  }

  void run(TenantId tenant, ExportQuery query, TaskActor actor, String jobId) throws IOException {
    List<List<Object>> rows =
        query.forUnit() ? unitRows(tenant, query, actor) : mineRows(tenant, query, actor);
    List<String> header =
        query.forUnit()
            ? List.of("Mã", "Nhiệm vụ", "Hạn", "Tiến độ (%)", "Tình trạng", "Trạng thái")
            : List.of(
                "Mã", "Nhiệm vụ", "Vai trò", "Hạn", "Tiến độ (%)", "Tình trạng", "Trạng thái");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    sheets.write(out, "Nhiệm vụ", header, rows);
    files.put(tenant, jobId, actor.personId(), out.toByteArray());
    jobs.finished(tenant, jobId, rows.size(), LOCATION + jobId);
  }

  private List<List<Object>> mineRows(TenantId tenant, ExportQuery query, TaskActor actor) {
    List<List<Object>> rows = new ArrayList<>();
    LocalDate afterDue = null;
    UUID afterId = null;
    while (rows.size() < MAX_ROWS) {
      List<MyTaskService.MineItem> page =
          mine.rowsForExport(
              tenant,
              query.mine(),
              actor.personId(),
              afterDue,
              afterId,
              Math.min(PAGE, MAX_ROWS - rows.size()));
      for (MyTaskService.MineItem item : page) {
        TaskRow t = item.row().task();
        rows.add(
            List.of(
                t.code(),
                t.title(),
                ROLE.getOrDefault(item.row().role(), ""),
                item.row().effectiveDue(),
                percent(t.progressPct()),
                situation(t, item.workingDaysLeft()),
                STATUS.getOrDefault(t.status().name(), "")));
      }
      if (page.size() < PAGE) {
        break;
      }
      MyTaskService.MineItem last = page.get(page.size() - 1);
      afterDue = last.row().effectiveDue();
      afterId = last.row().task().id();
    }
    return rows;
  }

  private List<List<Object>> unitRows(TenantId tenant, ExportQuery query, TaskActor actor) {
    List<List<Object>> rows = new ArrayList<>();
    UUID afterId = null;
    while (rows.size() < MAX_ROWS) {
      List<UnitTaskRow> page =
          units.listUnitTasks(
              tenant, query.unit(), actor, afterId, Math.min(PAGE, MAX_ROWS - rows.size()));
      for (UnitTaskRow r : page) {
        TaskRow t = r.task();
        boolean visible =
            !r.restricted()
                || actor.personId().equals(t.assignerId())
                || actor.personId().equals(r.leadAssigneeId());
        LocalDate due = t.extendedDueDate() != null ? t.extendedDueDate() : t.dueDate();
        rows.add(
            List.of(
                t.code(),
                visible ? t.title() : RESTRICTED,
                due,
                percent(t.progressPct()),
                t.overdue() ? "Quá hạn" : "",
                STATUS.getOrDefault(t.status().name(), "")));
      }
      if (page.size() < PAGE) {
        break;
      }
      afterId = page.get(page.size() - 1).task().id();
    }
    return rows;
  }

  /**
   * Cột "Tình trạng" của SCR-0503: "Trễ n ngày", "Hôm nay", "Còn n ngày" hoặc trống khi đã đóng.
   */
  static String situation(TaskRow t, int workingDaysLeft) {
    if (!"DA_GIAO".equals(t.status().name()) && !"DANG_THUC_HIEN".equals(t.status().name())) {
      return "";
    }
    if (workingDaysLeft < 0 || t.overdue()) {
      return "Trễ " + Math.max(1, -workingDaysLeft) + " ngày";
    }
    if (workingDaysLeft == 0) {
      return "Hôm nay";
    }
    return "Còn " + workingDaysLeft + " ngày";
  }

  private static int percent(BigDecimal ratio) {
    return ratio == null ? 0 : ratio.movePointRight(2).intValue();
  }
}
