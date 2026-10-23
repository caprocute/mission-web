package vn.viettel.okrkpi.task.infrastructure.reconcile;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.avro.Schema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerFields;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerSource;
import vn.vtokr.task.v1.KeHoachDaDuyet;
import vn.vtokr.task.v1.NhiemVuDaGiao;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/**
 * Nguồn R2 của MOD-02 (DL-12): EVT-03 {@code task.plan.approved.v1} so với {@code
 * task.plan_version}, {@code task.plan}; EVT-04 {@code task.task.assigned.v1} so với {@code
 * task.task}; EVT-05 {@code task.task.progressed.v1} so với {@code task.task_progress}.
 *
 * <ul>
 *   <li>EVT-03: phiên bản kế hoạch đã duyệt không sửa được (migration V5), nên mọi trường phải
 *       khớp: số phiên bản, kỳ, người duyệt, trạng thái {@code DA_DUYET}, loại và chủ sở hữu kế
 *       hoạch (đơn vị với {@code TAP_THE}, cá nhân với {@code CA_NHAN}), số dòng kế hoạch. Phiên
 *       bản bị thay thế chưa so vì bảng không có cột tương ứng.
 *   <li>EVT-05: dòng tiến độ chỉ ghi thêm nên mọi trường của tải trọng phải khớp dòng có {@code id
 *       = progressId} (người báo là {@code created_by}, tỷ lệ là {@code progress_pct} × 100 làm
 *       tròn như lúc phát).
 *   <li>EVT-04: {@code plan_item_id} không đổi sau khi giao nên luôn so (suy luận); các trường còn
 *       lại (tiêu đề khi không mật, then chốt, mật, hạn) chỉ so khi {@code row_version} hiện hành
 *       bằng {@code aggregateVersion} của sự kiện, vì giao lại, gia hạn hợp lệ đổi chúng. Người chủ
 *       trì và phối hợp chưa so (bảng {@code task_assignee} đổi theo phân công lại).
 * </ul>
 */
public class TaskLedgerSource implements LedgerSource {

  static final String PLAN_APPROVED = EventTopics.EVT_03_KE_HOACH_DA_DUYET;
  static final String ASSIGNED = EventTopics.EVT_04_NHIEM_VU_DA_GIAO;
  static final String PROGRESSED = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT;

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;

  public TaskLedgerSource(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
  }

  @Override
  public String schema() {
    return "task";
  }

  @Override
  public JdbcTemplate jdbc() {
    return jdbc;
  }

  @Override
  public PlatformTransactionManager tm() {
    return tm;
  }

  @Override
  public Map<String, Schema> topics() {
    return Map.of(
        PLAN_APPROVED, KeHoachDaDuyet.getClassSchema(),
        ASSIGNED, NhiemVuDaGiao.getClassSchema(),
        PROGRESSED, TienDoDaCapNhat.getClassSchema());
  }

  @Override
  public List<String> verify(UUID aggregateId, List<SourceEvent> events) {
    List<String> out = new ArrayList<>();
    for (SourceEvent e : events) {
      LedgerFields f = LedgerFields.of(e, out);
      switch (e.topic()) {
        case PLAN_APPROVED -> planApproved(aggregateId, f);
        case ASSIGNED -> assigned(aggregateId, e, f);
        case PROGRESSED -> progressed(aggregateId, f);
        default -> {}
      }
    }
    return LedgerFields.distinct(out);
  }

  private void planApproved(UUID planId, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT v.plan_id, v.version_no, v.period_id, v.status, v.approved_by, p.plan_type,"
                + " p.owner_unit_id, p.owner_person_id,"
                + " (SELECT count(*) FROM task.plan_item i WHERE i.plan_version_id = v.id) AS items"
                + " FROM task.plan_version v JOIN task.plan p ON p.id = v.plan_id"
                + " AND p.period_bucket = v.period_bucket WHERE v.id = ?",
            f.uuid("planVersionId"));
    if (rows.isEmpty() || !planId.equals(f.uuid("planId"))) {
      f.missing("planVersionId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.sameUuid("planId", r.get("plan_id"));
    f.sameInt("versionNo", r.get("version_no"));
    f.sameUuid("periodId", r.get("period_id"));
    f.sameUuid("approvedBy", r.get("approved_by"));
    f.check("planVersionId", "DA_DUYET".equals(r.get("status")));
    f.sameString("ownerType", r.get("plan_type"));
    f.sameUuid(
        "ownerId",
        "CA_NHAN".equals(r.get("plan_type")) ? r.get("owner_person_id") : r.get("owner_unit_id"));
    f.sameInt("itemCount", r.get("items"));
  }

  private void progressed(UUID taskId, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT task_id, period_id, created_by, reported_at, progress_pct, done_quantity"
                + " FROM task.task_progress WHERE id = ?",
            f.uuid("progressId"));
    if (rows.isEmpty()) {
      f.missing("progressId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.check("taskId", taskId.equals(f.uuid("taskId")) && taskId.equals(r.get("task_id")));
    f.sameUuid("periodId", r.get("period_id"));
    f.sameUuid("reporterId", r.get("created_by"));
    f.sameInstant("reportedAt", r.get("reported_at"));
    var pct = f.decimal("progressPct");
    f.check(
        "progressPct",
        pct != null
            && r.get("progress_pct") instanceof java.math.BigDecimal ratio
            && pct.movePointLeft(2).setScale(4, RoundingMode.HALF_UP).compareTo(ratio) == 0);
    f.sameDecimal("actualValue", r.get("done_quantity"));
  }

  private void assigned(UUID taskId, SourceEvent e, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT plan_item_id, title, is_key_task, is_restricted, due_date, row_version"
                + " FROM task.task WHERE id = ?",
            taskId);
    if (rows.isEmpty() || !taskId.equals(f.uuid("taskId"))) {
      f.missing("taskId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.sameUuid("planItemId", r.get("plan_item_id"));
    if (((Number) r.get("row_version")).longValue() != e.aggregateVersion()) {
      return;
    }
    f.sameBool("isSecret", r.get("is_restricted"));
    f.sameBool("isKeyTask", r.get("is_key_task"));
    f.sameDate("dueDate", r.get("due_date"));
    if (!Boolean.TRUE.equals(f.bool("isSecret"))) {
      f.sameString("title", r.get("title"));
    }
  }
}
