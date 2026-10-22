package vn.viettel.okrkpi.task.application.mine.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.mine.QuickFilter;

/**
 * Truy vấn đọc "Việc của tôi" trên một phân vùng kỳ qua chỉ mục {@code (tenant_id, person_id,
 * period_bucket)} của {@code task.task_assignee}; tập gốc là việc người gọi chủ trì hoặc phối hợp
 * còn hiệu lực (trừ {@code NHAP}) cộng việc đăng ký {@code NHAP} của chính người gọi.
 */
public interface MyTaskQueries {

  /** Điều kiện lọc; {@code quick} rỗng tương đương {@link QuickFilter#TAT_CA}. */
  record MineFilter(
      UUID personId,
      UUID periodId,
      QuickFilter quick,
      boolean todayView,
      Set<String> statuses,
      Boolean overdue,
      String role,
      String group,
      UUID keyResultId,
      String q,
      LocalDate today,
      LocalDate soonUntil) {}

  /** Một dòng: nhiệm vụ, vai trò của người gọi, số vướng mắc đang mở, hạn hiệu lực. */
  record MineRow(TaskRow task, String role, int openBlockers, LocalDate effectiveDue) {}

  /** Một trang theo khoá {@code (hạn hiệu lực, id)} tăng dần. */
  List<MineRow> listMine(
      TenantId tenant,
      int periodBucket,
      MineFilter filter,
      LocalDate afterDue,
      UUID afterId,
      int limit);

  /** Số đếm từng bộ lọc nhanh trên cùng điều kiện vai trò, nhóm, KR, từ khoá (một truy vấn). */
  Map<QuickFilter, Integer> countMine(TenantId tenant, int periodBucket, MineFilter filter);

  /** KR liên kết của tập gốc, để dựng danh sách lọc theo KR. */
  List<UUID> keyResultsOfMine(TenantId tenant, int periodBucket, UUID personId, UUID periodId);

  /** Hạn hiệu lực và vai trò của việc còn mở người này đang chủ trì hoặc phối hợp trong kỳ. */
  record OpenAssignment(String role, LocalDate effectiveDue) {}

  /** Vai trò còn hiệu lực của người này trên nhiệm vụ ({@code CHU_TRI}, {@code PHOI_HOP}). */
  java.util.Optional<String> roleOn(TenantId tenant, UUID taskId, UUID personId);

  List<OpenAssignment> openAssignments(
      TenantId tenant, int periodBucket, UUID personId, UUID periodId);
}
